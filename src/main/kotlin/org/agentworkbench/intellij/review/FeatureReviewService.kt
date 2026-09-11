package org.agentworkbench.intellij.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.agentworkbench.intellij.WorkbenchSettings
import org.agentworkbench.intellij.git.NativeGit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 当前 Feature 的临时评审查询状态；结果不写入 Kit、Git 或磁盘。 */
@Service(Service.Level.PROJECT)
internal class FeatureReviewService(private val project: Project) : Disposable {
    private data class Request(
        val kitRoot: String,
        val slug: String,
        val bindings: List<ReviewBinding>,
        val hosts: List<ReviewHost>,
    )

    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "agent-workbench-review").apply { isDaemon = true }
    }
    private val sequence = AtomicLong()
    private val callbacks = CopyOnWriteArrayList<(List<ReviewRow>) -> Unit>()
    private val remoteChoices = mutableMapOf<String, String>()
    private val candidateChoices = mutableMapOf<String, String>()
    private val lastSuccessful = mutableMapOf<String, ReviewCandidate>()
    @Volatile private var collector: Future<*>? = null
    @Volatile private var workers = emptyList<Future<*>>()
    @Volatile private var active: Request? = null
    @Volatile private var rows = emptyList<ReviewRow>()

    fun subscribe(parent: Disposable, callback: (List<ReviewRow>) -> Unit) {
        callbacks += callback
        Disposer.register(parent) { callbacks.remove(callback) }
    }

    fun refresh(kitRoot: String, slug: String, bindings: List<ReviewBinding>) {
        val normalizedRoot = kitRoot.trim()
        val request = Request(normalizedRoot, slug, bindings, WorkbenchSettings.getInstance().reviewHosts(normalizedRoot))
        active = request
        start(request)
    }

    fun chooseRemote(repository: String, remote: String) {
        val request = active ?: return
        remoteChoices[choiceKey(request, repository)] = remote
        start(request)
    }

    fun chooseCandidate(repository: String, candidateId: String) {
        val request = active ?: return
        candidateChoices[choiceKey(request, repository)] = candidateId
        start(request)
    }

    fun invalidate() {
        active = null
        cancel()
        lastSuccessful.clear()
        rows = emptyList()
        deliver(rows)
    }

    private fun start(request: Request) {
        cancel()
        val generation = sequence.incrementAndGet()
        val initial = request.bindings.map { binding ->
            if (binding.workBranch.isNullOrBlank()) ReviewRow.Error(binding.repository, "Feature 未记录工作分支") else ReviewRow.Loading(binding.repository)
        }
        rows = initial
        deliver(initial)
        if (request.bindings.none { !it.workBranch.isNullOrBlank() }) return

        val completion = ExecutorCompletionService<Pair<String, ReviewRow>>(executor)
        val submitted = request.bindings.filter { !it.workBranch.isNullOrBlank() }.map { binding ->
            completion.submit(Callable { runReviewLookup(binding.repository) { lookup(request, binding) } })
        }
        workers = submitted
        collector = ApplicationManager.getApplication().executeOnPooledThread {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(QUERY_TIMEOUT_SECONDS)
            repeat(submitted.size) {
                val remaining = deadline - System.nanoTime()
                val future = if (remaining > 0) completion.poll(remaining, TimeUnit.NANOSECONDS) else null
                if (future == null) {
                    workers.forEach { it.cancel(true) }
                    if (generation == sequence.get() && active == request && !project.isDisposed) {
                        rows = rows.map { row -> if (row is ReviewRow.Loading) ReviewRow.Error(row.repository, "评审查询超时，请重试") else row }
                        deliver(rows, generation)
                    }
                    return@executeOnPooledThread
                }
                val completed = try { future.get() } catch (_: CancellationException) { return@executeOnPooledThread }
                if (generation != sequence.get() || active != request || project.isDisposed) return@executeOnPooledThread
                (completed.second as? ReviewRow.Link)?.let { lastSuccessful[choiceKey(request, completed.first)] = it.review }
                rows = rows.map { row -> if (row.repository == completed.first) completed.second else row }
                deliver(rows, generation)
            }
        }
    }

    private fun lookup(request: Request, binding: ReviewBinding): ReviewRow {
        val root = binding.root ?: return ReviewRow.Error(binding.repository, "本地仓库不可用")
        val branch = binding.workBranch ?: return ReviewRow.Error(binding.repository, "Feature 未记录工作分支")
        val remote = NativeGit.forProject(project).reviewRemote(root.toFile(), branch, remoteChoices[choiceKey(request, binding.repository)])
        val resolved = remote as? NativeGit.ReviewRemote.Resolved ?: return when (remote) {
            is NativeGit.ReviewRemote.Choice -> ReviewRow.RemoteChoice(binding.repository, remote.names)
            is NativeGit.ReviewRemote.Unavailable -> ReviewRow.Error(binding.repository, remote.reason)
            else -> ReviewRow.Error(binding.repository, "无法解析评审 remote")
        }
        val projectRemote = RemoteProjectParser.parse(resolved.url).getOrElse { return ReviewRow.Error(binding.repository, "remote 地址无法用于评审查询") }
        val matches = request.hosts.filter { projectRemote.host in it.hosts() }
        if (matches.isEmpty()) return ReviewRow.Setup(binding.repository, "未配置匹配此 remote 的代码托管服务")
        if (matches.size > 1) return ReviewRow.Error(binding.repository, "多个代码托管服务匹配此 remote")
        val host = matches.single()
        val token = ReviewCredentials.load(request.kitRoot, host) ?: return ReviewRow.Setup(binding.repository, "未保存该服务的 Token")
        return when (val lookup = ReviewClient().find(host, projectRemote, branch, token)) {
            is ReviewLookup.Failure -> previousLink(request, binding.repository)?.let { ReviewRow.Error(binding.repository, lookup.message, it) }
                ?: ReviewRow.Error(binding.repository, lookup.message)
            is ReviewLookup.Matches -> {
                if (lookup.incomplete != null && lookup.candidates.isEmpty()) {
                    return previousLink(request, binding.repository)?.let { ReviewRow.Error(binding.repository, "查询不完整：${lookup.incomplete}", it) }
                        ?: ReviewRow.Error(binding.repository, "查询不完整：${lookup.incomplete}")
                }
                val preferred = candidateChoices[choiceKey(request, binding.repository)]?.let { id -> lookup.candidates.firstOrNull { it.id == id } }
                when {
                    preferred != null -> ReviewRow.Link(binding.repository, preferred, incomplete = lookup.incomplete)
                    lookup.candidates.isEmpty() -> ReviewRow.Missing(binding.repository)
                    lookup.candidates.size == 1 -> ReviewRow.Link(binding.repository, lookup.candidates.single(), incomplete = lookup.incomplete)
                    else -> ReviewRow.Multiple(binding.repository, lookup.candidates)
                }
            }
        }
    }

    private fun previousLink(request: Request, repository: String): ReviewCandidate? = lastSuccessful[choiceKey(request, repository)]

    private fun choiceKey(request: Request, repository: String) = "${request.kitRoot}\u0000${request.slug}\u0000$repository"

    private fun cancel() {
        collector?.cancel(true)
        workers.forEach { it.cancel(true) }
        collector = null
        workers = emptyList()
    }

    private fun deliver(value: List<ReviewRow>, generation: Long? = null) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && (generation == null || generation == sequence.get())) callbacks.forEach { it(value) }
        }
    }

    override fun dispose() {
        invalidate()
        executor.shutdownNow()
        callbacks.clear()
    }

    companion object {
        private const val QUERY_TIMEOUT_SECONDS = 25L
        fun getInstance(project: Project): FeatureReviewService = project.getService(FeatureReviewService::class.java)
    }
}

/** Worker 必须总能产生终态；否则 UI 会永久保留 Loading。 */
internal fun runReviewLookup(repository: String, lookup: () -> ReviewRow): Pair<String, ReviewRow> = try {
    repository to lookup()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (cancelled: ProcessCanceledException) {
    throw cancelled
} catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
    throw interrupted
} catch (failure: Exception) {
    Logger.getInstance(FeatureReviewService::class.java).warn("代码评审查询失败：$repository", failure)
    repository to ReviewRow.Error(repository, "评审查询失败，请重试")
}

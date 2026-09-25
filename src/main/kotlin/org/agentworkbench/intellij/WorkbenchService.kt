package org.agentworkbench.intellij

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import org.agentworkbench.intellij.kit.InspectResponse
import org.agentworkbench.intellij.kit.HandoffData
import org.agentworkbench.intellij.kit.KitClient
import org.agentworkbench.intellij.kit.SearchData
import org.agentworkbench.intellij.ui.WorkbenchVirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
internal class WorkbenchService(private val project: Project) : Disposable {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val coroutineJobs = ConcurrentHashMap<String, Job>()
    private val file = WorkbenchVirtualFile()
    private val coordinator = RefreshCoordinator<Unit>()
    
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val lock = Any()
    @Volatile private var disposed = false
    @Volatile private var snapshot = Snapshot.empty()
    @Volatile private var detailGeneration = 0L
    @Volatile private var documentGeneration = 0L
    @Volatile private var verificationGeneration = 0L

    @Volatile private var currentBranchSlug: String? = null
    private val vfsDebounce = java.util.concurrent.atomic.AtomicReference<Job?>(null)

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val root = snapshot.kitRoot ?: return
                // .git/ 与 __pycache__/ 的事件对快照没有意义，过滤后再去抖；
                // 代际协调只丢弃过期结果，不去抖时每个文件事件都会各起一次 Python 进程。
                val relevant = events.any { event ->
                    event.path.startsWith(root) &&
                        !event.path.contains("/.git/") &&
                        !event.path.contains("/__pycache__/")
                }
                if (!relevant) return
                vfsDebounce.getAndSet(scope.launch {
                    kotlinx.coroutines.delay(VFS_DEBOUNCE_MILLIS)
                    if (!disposed) bind(root, snapshot.python ?: "") { }
                })?.cancel()
            }
        })
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { _ ->
            updateBranchSlug()
        })
    }

    fun currentBranchSlug(): String? = currentBranchSlug

    private fun updateBranchSlug() {
        val items = snapshot.items
        val gitMgr = runCatching { GitRepositoryManager.getInstance(project) }.getOrNull() ?: return
        val repos = gitMgr.repositories
        if (repos.isEmpty() || items.isEmpty()) {
            if (currentBranchSlug != null) {
                currentBranchSlug = null
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) listeners.forEach { it() }
                }
            }
            return
        }

        val repoBranches = repos.mapNotNull { repo ->
            val branch = repo.currentBranchName ?: return@mapNotNull null
            Triple(repo.root.path, repo.root.name, branch)
        }
        // Kit 仓库 id -> 绝对路径，用于把 featureSummary.repositoryBindings[].repository 对应到本地检出。
        val repoPathById = snapshot.workspace?.data?.takeIf { it.isJsonObject }?.asJsonObject
            ?.getAsJsonArray("repositories")?.mapNotNull { element ->
                val repo = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = repo.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val path = repo.get("absolutePath")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                id to path
            }?.toMap().orEmpty()

        val matched = matchBranchSlug(items, repoPathById, repoBranches)

        if (matched != currentBranchSlug) {
            currentBranchSlug = matched
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) {
                    listeners.forEach { it() }
                }
            }
        }
    }

    fun file() = file
    fun snapshot() = snapshot

    /** 快照变化时在 EDT 上通知；用事件替代界面侧的轮询。 */
    fun subscribe(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    fun bind(kitRoot: String, python: String, callback: (Snapshot) -> Unit) {
        if (kitRoot.isBlank() || python.isBlank()) return deliver(callback, snapshot.copy(error = "需要明确 Kit 根目录和 Python 解释器。"))
        // 路径解析与 PATH 探测都是文件系统 IO，放到后台线程执行。
        scope.launch {
            if (disposed) return@launch
            val root = runCatching { Path.of(kitRoot).toRealPath().toString() }.getOrElse { return@launch deliver(callback, snapshot.copy(error = "Kit 根目录不可读取。")) }
            val executable = resolvePython(python) ?: return@launch deliver(callback, snapshot.copy(error = "Python 解释器不可执行。"))
            KitClient.validateInterpreter(Path.of(executable)).exceptionOrNull()?.let { failure ->
                return@launch deliver(callback, snapshot.copy(error = failure.message ?: "Python 版本检查失败。"))
            }
            val previousRoot = snapshot.kitRoot
            if (previousRoot != root || snapshot.python != executable) {
                if (previousRoot != null) cancelRoot(previousRoot)
                synchronized(lock) { snapshot = Snapshot.empty(root, executable) }
            }
            request("workspace:$root", callback) { client ->
                val response = client.inspect("workspace").getOrThrow()
                ({ state: Snapshot, value: InspectResponse -> state.copy(workspace = value, error = null) }) to response
            }
            request("items:$root", callback) { client ->
                val response = loadPages(client, "items")
                val items = response.data!!.asJsonObject.getAsJsonArray("items").map { it.asJsonObject }
                ({ state: Snapshot, _: InspectResponse -> state.copy(items = items, error = null) }) to response
            }
        }
    }

    private fun deliver(callback: (Snapshot) -> Unit, state: Snapshot) {
        if (disposed) return
        updateBranchSlug()
        ApplicationManager.getApplication().invokeLater { if (!disposed) callback(state) }
    }

    fun loadWorkItem(slug: String, callback: (Snapshot) -> Unit) = loadProjection(slug, "task", callback)

    fun loadProjection(slug: String, view: String, callback: (Snapshot) -> Unit) {
        require(view in setOf("task", "change", "flow")) { "不支持的详情视图" }
        val selection = synchronized(lock) {
            if (snapshot.selectedDetail != slug) detailGeneration++
            snapshot = snapshot.copy(selectedDetail = slug, selectedHandoff = null)
            detailGeneration
        }
        val current = snapshot
        if (!supports("projection") && current.workspace != null) {
            deliver(callback, current.copy(error = "当前 Kit 版本不兼容：缺少 inspect projection，请升级 Kit。"))
            return
        }
        requestFor("projection", "$slug:$view", callback, listOf(slug, "--view", view), relevant = { it.selectedDetail == slug && detailGeneration == selection }) { state, response ->
            if (response.status == "partial") {
                state.copy(error = "${view} 详情不完整：${response.diagnostics.joinToString("; ") { it.message }.ifBlank { "Kit 返回 partial" }}；保留上次内容")
            } else if (response.data?.asJsonObject?.get("view")?.asString != view) {
                state.copy(error = "Kit 返回了不匹配的详情视图；保留上次内容")
            } else if (state.selectedDetail == slug) {
                when (view) {
                    "task" -> state.copy(detail = response, error = null)
                    "change" -> state.copy(change = response, error = null)
                    else -> state.copy(flow = response, error = null)
                }
            } else state
        }
    }
    fun loadDocument(slug: String, path: String, revision: String?, callback: (Snapshot) -> Unit) {
        val identity = synchronized(lock) { detailGeneration to ++documentGeneration }
        requestFor("document", "$slug:$path", callback, listOf(slug, "--path", path) + (revision?.let { listOf("--document-revision", it) } ?: emptyList()),
            relevant = { it.selectedDetail == slug && detailGeneration == identity.first && documentGeneration == identity.second }) { state, response ->
            state.copy(document = response, error = null)
        }
    }

    fun loadArtifacts(slug: String, offset: Int = 0, limit: Int = 50, callback: (Snapshot) -> Unit) {
        requestFor("artifacts", "$slug:$offset:$limit", callback,
            listOf(slug, "--offset", offset.toString(), "--limit", limit.toString()),
            relevant = { it.selectedDetail == slug }) { state, response ->
            state.copy(artifacts = response)
        }
    }
    fun loadVerification(slug: String, callback: (Snapshot) -> Unit) = loadVerification(slug, false, callback)
    fun loadVerification(slug: String, checkCode: Boolean, callback: (Snapshot) -> Unit) {
        val identity = synchronized(lock) { detailGeneration to ++verificationGeneration }
        requestFor("verification", "$slug:${if (checkCode) "code" else "records"}", callback,
            listOf(slug) + if (checkCode) listOf("--check-code") else emptyList(),
            relevant = { it.selectedDetail == slug && detailGeneration == identity.first && verificationGeneration == identity.second }) { state, response ->
            state.copy(verification = response, error = null)
        }
    }
    fun loadWorkflow(callback: (Snapshot) -> Unit) = requestFor("workflow", "", callback) { state, response -> state.copy(workflow = response, error = null) }
    fun loadHandoff(slug: String, callback: (Snapshot) -> Unit) {
        synchronized(lock) { snapshot = snapshot.copy(selectedHandoff = slug) }
        requestFor("handoff", slug, callback, relevant = { it.selectedHandoff == slug }) { state, response ->
            if (state.selectedHandoff == slug) {
                state.copy(handoff = HandoffData.parse(response.data), error = null)
            } else state
        }
    }
    fun searchHistory(query: String, repository: String?, status: String?, callback: (Snapshot) -> Unit) {
        val selection = listOf(query, repository.orEmpty(), status.orEmpty()).joinToString("\u0000")
        synchronized(lock) { snapshot = snapshot.copy(selectedSearch = selection) }
        val arguments = buildList {
            addAll(listOf("--query", query, "--limit", "50"))
            repository?.let { addAll(listOf("--repo", it)) }
            status?.let { addAll(listOf("--status", it)) }
        }
        requestFor("search", selection, callback, arguments, relevant = { it.selectedSearch == selection }) { state, response ->
            if (state.selectedSearch == selection) {
                state.copy(
                    search = SearchData.parse(response.data).copy(
                        incomplete = response.status == "partial"
                    ),
                    error = null,
                )
            } else state
        }
    }
    /** [itemSlug] 非空时由 Kit 服务端按需求过滤（inspect runs --item），避免拉全量后在客户端过滤。 */
    fun loadRuns(itemSlug: String? = null, callback: (Snapshot) -> Unit) {
        val root = snapshot.kitRoot ?: return
        synchronized(lock) { snapshot = snapshot.copy(selectedRunsFilter = itemSlug) }
        val filter = itemSlug?.let { listOf("--item", it) } ?: emptyList()
        request("runs:$root:${itemSlug.orEmpty()}", callback, relevant = { it.selectedRunsFilter == itemSlug }) { client ->
            ({ state: Snapshot, response: InspectResponse -> if (state.selectedRunsFilter == itemSlug) state.copy(runs = response, error = null) else state }) to loadPages(client, "runs", filter)
        }
    }
    fun loadRun(id: String, callback: (Snapshot) -> Unit) {
        synchronized(lock) { snapshot = snapshot.copy(selectedRun = id) }
        requestFor("run", id, callback, relevant = { it.selectedRun == id }) { state, response -> if (state.selectedRun == id) state.copy(run = response, error = null) else state }
    }

    private fun requestFor(operation: String, subject: String, callback: (Snapshot) -> Unit, arguments: List<String> = if (subject.isBlank()) emptyList() else listOf(subject), relevant: (Snapshot) -> Boolean = { true }, update: (Snapshot, InspectResponse) -> Snapshot) {
        val current = snapshot
        if (current.kitRoot == null || current.python == null || disposed) return
        request("$operation:${current.kitRoot}:$subject", callback, relevant) { client -> update to client.inspect(operation, arguments).getOrThrow() }
    }

    private fun request(key: String, callback: (Snapshot) -> Unit, relevant: (Snapshot) -> Boolean = { true }, task: (KitClient) -> Pair<(Snapshot, InspectResponse) -> Snapshot, InspectResponse>) {
        val current = snapshot
        val root = current.kitRoot ?: return
        val python = current.python ?: return
        coroutineJobs.remove(key)?.cancel()
        val generation = coordinator.begin(key)
        coroutineJobs[key] = scope.launch {
            val result = runCatching { coordinator.bounded { task(KitClient(Path.of(python), Path.of(root))) } }
            if (disposed || snapshot.kitRoot != root || snapshot.python != python || !relevant(snapshot)) {
                return@launch
            }
            if (!coordinator.isCurrent(key, generation)) return@launch
            synchronized(lock) {
                if (!relevant(snapshot) || !coordinator.isCurrent(key, generation)) return@launch
                if (result.isSuccess) {
                    val (update, response) = result.getOrThrow()
                    val next = update(snapshot, response)
                    if (coordinator.succeed(key, generation, Unit, response.observedAt)) snapshot = next
                } else if (coordinator.fail(key, generation, result.exceptionOrNull()?.message ?: "读取失败")) {
                    LOG.warn("Inspect 请求失败：$key", result.exceptionOrNull())
                    snapshot = snapshot.copy(error = result.exceptionOrNull()?.message ?: "读取失败")
                }
            }
            coordinator.trim(MAX_RESOURCES)
            while (coroutineJobs.size > MAX_RESOURCES) {
                // 只驱逐其他请求；ConcurrentHashMap 的任意序 firstOrNull 可能取消刚发起的当前请求。
                val evict = coroutineJobs.keys.firstOrNull { it != key } ?: break
                coroutineJobs.remove(evict)?.cancel()
            }
            val delivered = snapshot
            if (!disposed && coordinator.isCurrent(key, generation)) ApplicationManager.getApplication().invokeLater {
                if (!disposed && snapshot.kitRoot == root && snapshot.python == python && relevant(snapshot) && coordinator.isCurrent(key, generation)) {
                    callback(delivered)
                    listeners.forEach { it() }
                }
            }
        }
    }

    private fun loadPages(client: KitClient, operation: String, baseArguments: List<String> = emptyList()): InspectResponse {
        repeat(2) {
            val items = com.google.gson.JsonArray(); var offset = 0; var first: InspectResponse? = null
            while (true) {
                val page = client.inspect(operation, baseArguments + listOf("--offset", offset.toString(), "--limit", "200")).getOrThrow()
                val collection = page.data?.asJsonObject?.get("collectionRevision")?.asString
                    ?: error("列表响应缺少集合版本，请同步更新 Kit")
                if (first != null && first!!.data!!.asJsonObject.get("collectionRevision").asString != collection) break
                if (first == null) first = page
                val data = page.data?.asJsonObject ?: error("WorkItem 列表数据无效")
                val chunk = data.getAsJsonArray("items") ?: error("列表数据无效")
                chunk.forEach(items::add)
                val paging = data.getAsJsonObject("page") ?: error("WorkItem 分页数据无效")
                if (!paging.get("hasMore").asBoolean) {
                    val merged = first!!.data!!.asJsonObject.deepCopy()
                    merged.add("items", items)
                    merged.getAsJsonObject("page").addProperty("hasMore", false)
                    return first!!.copy(data = merged)
                }
                check(!chunk.isEmpty && offset < 10_000) { "分页结果未前进或超过工作区读取上限" }
                offset += paging.get("limit").asInt
            }
        }
        error("列表在分页期间持续变化")
    }

    private fun cancelRoot(root: String) = coroutineJobs.filterKeys { it.contains(":$root") }.forEach { (key, job) -> job.cancel(); coordinator.invalidate(key) }
    private fun resolvePython(value: String): String? {
        val direct = runCatching { Path.of(value) }.getOrNull()
        if (direct != null && direct.isAbsolute && Files.isExecutable(direct)) return direct.toRealPath().toString()
        if (value.contains('/') || value.contains('\\')) return null
        return System.getenv("PATH")?.split(java.io.File.pathSeparator)?.asSequence()
            ?.map { Path.of(it, value) }?.firstOrNull(Files::isExecutable)?.toRealPath()?.toString()
    }
    fun copyPrompt(slug: String, callback: (Result<String>) -> Unit) {
        val root = snapshot.kitRoot ?: return callback(Result.failure(IllegalStateException("未配置 Kit 根目录")))
        val python = snapshot.python ?: return callback(Result.failure(IllegalStateException("未配置 Python 解释器")))
        if (supports("handoff")) {
            loadHandoff(slug) { state ->
                val data = state.handoff
                callback(
                    if (state.error == null && data?.slug == slug) Result.success(data.content)
                    else Result.failure(IllegalStateException(state.error ?: "接手包不可用"))
                )
            }
            return
        }
        scope.launch {
            if (disposed) return@launch
            val result = KitClient(Path.of(python), Path.of(root)).tool("brief", listOf(slug, "--root", root, "--execution"))
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (!disposed) callback(result)
            }
        }
    }

    fun supports(operation: String): Boolean = snapshot.workspace?.data
        ?.takeIf { it.isJsonObject }?.asJsonObject
        ?.getAsJsonObject("protocol")?.getAsJsonArray("operations")
        ?.any { it.isJsonPrimitive && it.asString == operation } == true

    fun completeWorkItem(slug: String, callback: (Result<Unit>) -> Unit) {
        val current = snapshot
        val item = current.detail?.data?.asJsonObject?.getAsJsonObject("summary")?.takeIf { it.get("slug")?.asString == slug }
            ?: return callback(Result.failure(IllegalStateException("未找到需求：$slug")))
        val blocker = completionBlocker(item)
        if (blocker != null) {
            callback(Result.failure(IllegalStateException(blocker)))
            return
        }
        val root = current.kitRoot ?: return callback(Result.failure(IllegalStateException("未配置 Kit 根目录")))
        val python = current.python ?: return callback(Result.failure(IllegalStateException("未配置 Python 解释器")))
        scope.launch {
            if (disposed) return@launch
            val result = KitClient(Path.of(python), Path.of(root)).completeWorkItem(slug, item.get("stateRevision").asString)
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) callback(result)
            }
        }
    }

    fun runDoctor(callback: (Result<String>) -> Unit) {
        val root = snapshot.kitRoot ?: return callback(Result.failure(IllegalStateException("未配置 Kit 根目录")))
        val python = snapshot.python ?: return callback(Result.failure(IllegalStateException("未配置 Python 解释器")))
        scope.launch {
            if (disposed) return@launch
            val result = KitClient(Path.of(python), Path.of(root)).tool("doctor", listOf("--root", root, "--json"))
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (!disposed) callback(result)
            }
        }
    }

    override fun dispose() { disposed = true; vfsDebounce.getAndSet(null)?.cancel(); coroutineJobs.values.forEach { it.cancel() }; coroutineJobs.clear() }

    data class Snapshot(val kitRoot: String?, val python: String?, val workspace: InspectResponse?, val items: List<JsonObject>, val detail: InspectResponse?, val error: String?, val document: InspectResponse? = null, val artifacts: InspectResponse? = null, val verification: InspectResponse? = null, val workflow: InspectResponse? = null, val runs: InspectResponse? = null, val run: InspectResponse? = null, val selectedDetail: String? = null, val handoff: HandoffData? = null, val search: SearchData? = null, val selectedHandoff: String? = null, val selectedSearch: String? = null, val selectedRun: String? = null, val selectedRunsFilter: String? = null, val change: InspectResponse? = null, val flow: InspectResponse? = null) {
        companion object { fun empty(root: String? = null, python: String? = null) = Snapshot(root, python, null, emptyList(), null, null) }
    }
    companion object {
        private val LOG = Logger.getInstance(WorkbenchService::class.java)
        const val MAX_RESOURCES = 100
        private const val VFS_DEBOUNCE_MILLIS = 500L
        fun getInstance(project: Project): WorkbenchService = project.getService(WorkbenchService::class.java)

        internal fun completionBlocker(item: JsonObject): String? {
            val action = item.getAsJsonObject("completionAction") ?: return "请先读取当前需求的完成条件"
            return if (action.get("available")?.asBoolean == true) null
            else action.get("reason")?.takeUnless { it.isJsonNull }?.asString ?: "仍有未完成事项"
        }

        /**
         * 把本地检出的 (仓路径, 仓目录名, 当前分支) 与 featureSummary.repositoryBindings 匹配，
         * 返回首个命中的需求 slug。binding.repository 是 Kit 仓库 id，经 repoPathById 映射到绝对路径。
         */
        internal fun matchBranchSlug(
            items: List<JsonObject>,
            repoPathById: Map<String, String>,
            repoBranches: List<Triple<String, String, String>>,
        ): String? {
            for (item in items) {
                val slug = item.get("slug")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val bindings = item.getAsJsonArray("repositoryBindings") ?: continue
                for (bindingElem in bindings) {
                    if (!bindingElem.isJsonObject) continue
                    val binding = bindingElem.asJsonObject
                    val repoId = binding.get("repository")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    val branch = binding.get("workBranch")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    val absolutePath = repoPathById[repoId]
                    for ((currentRepoPath, currentRepoName, currentBranch) in repoBranches) {
                        if (currentBranch == branch && (currentRepoPath == absolutePath || currentRepoName == repoId)) {
                            return slug
                        }
                    }
                }
            }
            return null
        }
    }
}

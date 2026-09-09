package org.agentworkbench.intellij

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.agentworkbench.intellij.kit.InspectResponse
import org.agentworkbench.intellij.kit.KitClient
import org.agentworkbench.intellij.ui.WorkbenchVirtualFile
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

@Service(Service.Level.PROJECT)
internal class WorkbenchService : Disposable {
    private val file = WorkbenchVirtualFile()
    private val coordinator = RefreshCoordinator<Unit>()
    private val requests = ConcurrentHashMap<String, Future<*>>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val lock = Any()
    @Volatile private var disposed = false
    @Volatile private var snapshot = Snapshot.empty()

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
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed) return@executeOnPooledThread
            val root = runCatching { Path.of(kitRoot).toRealPath().toString() }.getOrElse { return@executeOnPooledThread deliver(callback, snapshot.copy(error = "Kit 根目录不可读取。")) }
            val executable = resolvePython(python) ?: return@executeOnPooledThread deliver(callback, snapshot.copy(error = "Python 解释器不可执行。"))
            val previousRoot = snapshot.kitRoot
            if (previousRoot != root) {
                if (previousRoot != null) cancelRoot(previousRoot)
                synchronized(lock) { snapshot = Snapshot.empty(root, executable) }
            }
            request("workspace:$root", callback) { client ->
                val response = client.inspect("workspace").getOrThrow()
                ({ state: Snapshot, value: InspectResponse -> state.copy(workspace = value, error = null) }) to response
            }
            request("features:$root", callback) { client ->
                val response = loadPages(client, "features")
                val features = response.data!!.asJsonObject.getAsJsonArray("items").map { it.asJsonObject }
                ({ state: Snapshot, _: InspectResponse -> state.copy(features = features, error = null) }) to response
            }
        }
    }

    private fun deliver(callback: (Snapshot) -> Unit, state: Snapshot) {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater { if (!disposed) callback(state) }
    }

    fun loadFeature(slug: String, callback: (Snapshot) -> Unit) {
        synchronized(lock) { snapshot = snapshot.copy(selectedDetail = slug) }
        requestFor("feature", slug, callback) { state, response -> if (state.selectedDetail == slug) state.copy(detail = response, error = null) else state }
    }
    fun loadDocument(slug: String, path: String, revision: String?, callback: (Snapshot) -> Unit) = requestFor("document", "$slug:$path", callback, listOf(slug, "--path", path) + (revision?.let { listOf("--revision", it) } ?: emptyList())) { state, response -> state.copy(document = response, error = null) }
    fun loadVerification(slug: String, callback: (Snapshot) -> Unit) = loadVerification(slug, false, callback)
    fun loadVerification(slug: String, checkCode: Boolean, callback: (Snapshot) -> Unit) =
        requestFor("verification", "$slug:${if (checkCode) "code" else "records"}", callback, listOf(slug) + if (checkCode) listOf("--check-code") else emptyList()) { state, response -> state.copy(verification = response, error = null) }
    fun loadWorkflow(callback: (Snapshot) -> Unit) = requestFor("workflow", "", callback) { state, response -> state.copy(workflow = response, error = null) }
    fun loadRuns(callback: (Snapshot) -> Unit) {
        val root = snapshot.kitRoot ?: return
        request("runs:$root:", callback) { client ->
            ({ state: Snapshot, response: InspectResponse -> state.copy(runs = response, error = null) }) to loadPages(client, "runs")
        }
    }
    fun loadRun(id: String, callback: (Snapshot) -> Unit) = requestFor("run", id, callback) { state, response -> state.copy(run = response, error = null) }

    private fun requestFor(operation: String, subject: String, callback: (Snapshot) -> Unit, arguments: List<String> = if (subject.isBlank()) emptyList() else listOf(subject), update: (Snapshot, InspectResponse) -> Snapshot) {
        val current = snapshot
        if (current.kitRoot == null || current.python == null || disposed) return
        request("$operation:${current.kitRoot}:$subject", callback) { client -> update to client.inspect(operation, arguments).getOrThrow() }
    }

    private fun request(key: String, callback: (Snapshot) -> Unit, task: (KitClient) -> Pair<(Snapshot, InspectResponse) -> Snapshot, InspectResponse>) {
        val current = snapshot
        val root = current.kitRoot ?: return
        val python = current.python ?: return
        requests.remove(key)?.cancel(true)
        val generation = coordinator.begin(key)
        requests[key] = ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { coordinator.bounded { task(KitClient(Path.of(python), Path.of(root))) } }
            if (disposed || snapshot.kitRoot != root) {
                return@executeOnPooledThread
            }
            if (!coordinator.isCurrent(key, generation)) return@executeOnPooledThread
            synchronized(lock) {
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
            while (requests.size > MAX_RESOURCES) requests.keys.firstOrNull()?.let { requests.remove(it)?.cancel(true) } ?: break
            val delivered = snapshot
            if (!disposed && coordinator.isCurrent(key, generation)) ApplicationManager.getApplication().invokeLater {
                if (!disposed && snapshot.kitRoot == root && coordinator.isCurrent(key, generation)) {
                    callback(delivered)
                    listeners.forEach { it() }
                }
            }
        }
    }

    private fun loadPages(client: KitClient, operation: String): InspectResponse {
        repeat(2) {
            val items = com.google.gson.JsonArray(); var offset = 0; var first: InspectResponse? = null
            while (true) {
                val page = client.inspect(operation, listOf("--offset", offset.toString(), "--limit", "200")).getOrThrow()
                if (first != null && first!!.revision != page.revision) break
                if (first == null) first = page
                val data = page.data?.asJsonObject ?: error("Feature 列表数据无效")
                val chunk = data.getAsJsonArray("items") ?: error("列表数据无效")
                chunk.forEach(items::add)
                val paging = data.getAsJsonObject("page") ?: error("Feature 分页数据无效")
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

    private fun cancelRoot(root: String) = requests.filterKeys { it.contains(":$root") }.forEach { (key, future) -> future.cancel(true); coordinator.invalidate(key) }
    private fun resolvePython(value: String): String? {
        val direct = runCatching { Path.of(value) }.getOrNull()
        if (direct != null && direct.isAbsolute && Files.isExecutable(direct)) return direct.toRealPath().toString()
        if (value.contains('/') || value.contains('\\')) return null
        return System.getenv("PATH")?.split(java.io.File.pathSeparator)?.asSequence()
            ?.map { Path.of(it, value) }?.firstOrNull(Files::isExecutable)?.toRealPath()?.toString()
    }
    override fun dispose() { disposed = true; requests.values.forEach { it.cancel(true) }; requests.clear() }

    data class Snapshot(val kitRoot: String?, val python: String?, val workspace: InspectResponse?, val features: List<JsonObject>, val detail: InspectResponse?, val error: String?, val document: InspectResponse? = null, val verification: InspectResponse? = null, val workflow: InspectResponse? = null, val runs: InspectResponse? = null, val run: InspectResponse? = null, val selectedDetail: String? = null) {
        companion object { fun empty(root: String? = null, python: String? = null) = Snapshot(root, python, null, emptyList(), null, null) }
    }
    companion object {
        private val LOG = Logger.getInstance(WorkbenchService::class.java)
        const val MAX_RESOURCES = 100
        fun getInstance(project: Project): WorkbenchService = project.getService(WorkbenchService::class.java)
    }
}

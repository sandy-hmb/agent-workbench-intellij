package org.agentworkbench.intellij.git

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future

/** 全项目唯一的 Git 现场扫描：一次扫描，所有仓库视图共享结果。 */
@Service(Service.Level.PROJECT)
internal class GitScanService(private val project: Project) : Disposable {
    data class Scan(
        val id: String, val role: String, val root: Path, val availability: String, val description: String = "",
        val scene: NativeGit.Snapshot? = null, val remotes: List<String> = emptyList(), val lastCommit: String? = null,
    )
    fun interface Listener { fun updated(scans: List<Scan>, scanning: Boolean) }

    private var generation = 0
    @Volatile private var scans = emptyList<Scan>()
    @Volatile private var scanning = false
    private var request: Future<*>? = null
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun scans(): List<Scan> = scans
    fun scenes(): Map<String, NativeGit.Snapshot> = scans.mapNotNull { s -> s.scene?.let { s.id to it } }.toMap()

    fun subscribe(parent: Disposable, listener: Listener) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }

    /** 由 EDT 调用；仓库清单来自工作区登记，扫描在后台执行。 */
    fun refresh(repositories: List<JsonObject>) {
        request?.cancel(true)
        val current = ++generation
        val base = repositories.mapNotNull { item ->
            val path = item.get("absolutePath")?.asString ?: return@mapNotNull null
            Scan(item.get("id").asString, item.get("role").asString, Path.of(path),
                item.get("availability")?.asString ?: "unknown", item.get("description")?.asString.orEmpty())
        }.sortedBy { it.role == "kit" }
        scans = base
        scanning = base.isNotEmpty()
        notifyListeners()
        if (base.isEmpty()) return
        request = ApplicationManager.getApplication().executeOnPooledThread {
            val git = NativeGit.forProject(project)
            val updated = base.map { entry ->
                if (Thread.currentThread().isInterrupted) return@executeOnPooledThread
                entry.copy(scene = git.snapshot(entry.root.toFile()), remotes = git.remotes(entry.root.toFile()), lastCommit = git.lastCommitTime(entry.root.toFile()))
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && current == generation) {
                    scans = updated
                    scanning = false
                    notifyListeners()
                }
            }
        }
    }

    private fun notifyListeners() { listeners.forEach { it.updated(scans, scanning) } }

    override fun dispose() { generation++; request?.cancel(true); listeners.clear() }

    companion object {
        fun getInstance(project: Project): GitScanService = project.getService(GitScanService::class.java)
    }
}

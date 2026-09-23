package org.agentworkbench.intellij

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import org.agentworkbench.intellij.review.ReviewHost
import java.nio.file.Path

@State(name = "AgentWorkbenchSettings", storages = [Storage(value = "agent-workbench.xml", roamingType = RoamingType.DISABLED)])
@Service(Service.Level.APP)
internal class WorkbenchSettings : PersistentStateComponent<WorkbenchSettings.Data> {
    data class Position(var key: String = "", var offset: Int = 0)
    data class Preference(
        var root: String = "", var python: String = "python3", var query: String = "", var status: String = "未完成", var incompleteDefaultApplied: Boolean = false, var tab: Int = 0, var run: String = "", var feature: String = "", var document: String = "", var positions: MutableList<Position> = mutableListOf(), var reviewHosts: MutableList<ReviewHost> = mutableListOf(),
    )
    data class FeatureView(var slug: String = "", var tab: Int = 0, var changeTab: Int = 1, var filter: String = "全部", var task: String = "", var repository: String = "", var file: String = "", var offset: Int = 0, var startCommit: String = "")
    data class Binding(var projectRoot: String = "", var kitRoot: String = "")
    data class Data(var preferences: MutableList<Preference> = mutableListOf(), var bindings: MutableList<Binding> = mutableListOf(), var featureViews: MutableList<FeatureViewEntry> = mutableListOf())
    data class FeatureViewEntry(var root: String = "", var view: FeatureView = FeatureView())

    private var data = Data()
    private val canonicalCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun getState(): Data = data
    override fun loadState(state: Data) { data = state }

    fun preference(root: String): Preference {
        val canonical = canonical(root)
        return data.preferences.firstOrNull { it.root == canonical } ?: Preference(root = canonical).also { data.preferences += it }
    }

    fun featureView(root: String, slug: String): FeatureView {
        require(slug.isNotBlank())
        val key = canonical(root)
        return data.featureViews.firstOrNull { it.root == key && it.view.slug == slug }?.view
            ?: FeatureView(slug = slug, tab = preference(root).tab.coerceIn(0, 2)).also {
                data.featureViews += FeatureViewEntry(key, it)
            }
    }

    fun featureStatus(root: String): String = preference(root).let {
        if (!it.incompleteDefaultApplied) {
            if (it.status == "全部") it.status = "未完成"
            it.incompleteDefaultApplied = true
        }
        it.status
    }

    fun rememberPosition(root: String, key: String, offset: Int) {
        val preference = preference(root)
        preference.positions.removeIf { it.key == key }
        preference.positions += Position(key, offset)
        while (preference.positions.size > MAX_POSITIONS) preference.positions.removeAt(0)
    }
    fun reviewHosts(root: String): List<ReviewHost> = preference(root).reviewHosts.map { it.copy() }
    fun replaceReviewHosts(root: String, hosts: List<ReviewHost>) {
        preference(root).reviewHosts = hosts.map { it.copy() }.toMutableList()
    }
    fun kitForProject(projectRoot: String): String? = data.bindings.firstOrNull { it.projectRoot == canonical(projectRoot) }?.kitRoot
    fun rememberBinding(projectRoot: String, kitRoot: String) {
        val project = canonical(projectRoot); val kit = canonical(kitRoot)
        data.bindings.removeIf { it.projectRoot == project }; data.bindings += Binding(project, kit)
    }
    private fun canonical(path: String) = canonicalCache.computeIfAbsent(path) {
        runCatching { Path.of(it).toRealPath().toString() }
            .getOrElse { _ -> Path.of(path).toAbsolutePath().normalize().toString() }
    }

    companion object {
        const val MAX_POSITIONS = 100
        fun getInstance(): WorkbenchSettings = service()
    }
}

package org.agentworkbench.intellij

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.nio.file.Path

@State(name = "AgentWorkbenchSettings", storages = [Storage(value = "agent-workbench.xml", roamingType = RoamingType.DISABLED)])
@Service(Service.Level.APP)
internal class WorkbenchSettings : PersistentStateComponent<WorkbenchSettings.Data> {
    data class Position(var key: String = "", var offset: Int = 0)
    data class Preference(
        var root: String = "", var python: String = "python3", var query: String = "", var status: String = "全部", var tab: Int = 0, var run: String = "", var feature: String = "", var document: String = "", var allRuns: Boolean = false, var positions: MutableList<Position> = mutableListOf(),
    )
    data class Binding(var projectRoot: String = "", var kitRoot: String = "")
    data class Data(var preferences: MutableList<Preference> = mutableListOf(), var bindings: MutableList<Binding> = mutableListOf())

    private var data = Data()
    override fun getState(): Data = data
    override fun loadState(state: Data) { data = state }

    fun preference(root: String): Preference {
        val canonical = canonical(root)
        return data.preferences.firstOrNull { it.root == canonical } ?: Preference(root = canonical).also { data.preferences += it }
    }

    fun rememberPosition(root: String, key: String, offset: Int) {
        val preference = preference(root)
        preference.positions.removeIf { it.key == key }
        preference.positions += Position(key, offset)
        while (preference.positions.size > MAX_POSITIONS) preference.positions.removeAt(0)
    }
    fun kitForProject(projectRoot: String): String? = data.bindings.firstOrNull { it.projectRoot == canonical(projectRoot) }?.kitRoot
    fun rememberBinding(projectRoot: String, kitRoot: String) {
        val project = canonical(projectRoot); val kit = canonical(kitRoot)
        data.bindings.removeIf { it.projectRoot == project }; data.bindings += Binding(project, kit)
    }
    private fun canonical(path: String) = runCatching { Path.of(path).toRealPath().toString() }
        .getOrElse { Path.of(path).toAbsolutePath().normalize().toString() }

    companion object {
        const val MAX_POSITIONS = 100
        fun getInstance(): WorkbenchSettings = service()
    }
}

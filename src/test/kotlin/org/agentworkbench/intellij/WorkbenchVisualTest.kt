package org.agentworkbench.intellij

import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.agentworkbench.intellij.ui.WorkbenchPanel
import org.agentworkbench.intellij.ui.WorkbenchTabs
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JLabel

/** Exercise the real producer's targeted views without rebuilding an old complete WorkItem. */
class WorkbenchVisualTest : BasePlatformTestCase() {
    private val testPython get() = System.getProperty("workbench.testPython", "python3")
    private fun descendants(root: Component): List<Component> =
        listOf(root) + ((root as? Container)?.components.orEmpty().flatMap { descendants(it) })

    fun testCurrentWorkItemNavigationUsesRoleDocumentsAndVerification() {
        val directory = Files.createTempDirectory("workbench-v2-ui-").toRealPath()
        val kit = Files.createDirectories(directory.resolve("kit"))
        Files.createDirectories(kit.resolve("scripts"))
        for (name in listOf("workspace", "items", "task", "change", "flow", "document", "verification", "workflow", "runs")) {
            val text = javaClass.getResourceAsStream("/inspect-v2/$name.json")!!.use { it.readBytes().decodeToString() }
            Files.writeString(kit.resolve("$name.json"), text.replace("/synthetic/kit", kit.toString()))
        }
        Files.writeString(kit.resolve("scripts/kit.py"), """
            import json,pathlib,sys
            root=pathlib.Path(sys.argv[sys.argv.index('--root')+1])
            op=sys.argv[sys.argv.index('--json')+1]
            source=sys.argv[sys.argv.index('--view')+1] if op=='projection' else op
            data=json.loads((root/(source+'.json')).read_text())
            data['root']=str(root)
            with (root/'requests.log').open('a') as log: log.write(' '.join(sys.argv[1:])+'\n')
            print(json.dumps(data))
        """.trimIndent())
        try {
            val service = WorkbenchService.getInstance(project)
            service.bind(kit.toString(), testPython) { }
            PlatformTestUtil.waitWithEventsDispatching("workspace", { service.snapshot().items.isNotEmpty() }, 10)
            val panel = WorkbenchPanel(project)
            try {
                panel.setActive(true)
                panel.selectWorkItem("demo")
                PlatformTestUtil.waitWithEventsDispatching("task projection", {
                    service.snapshot().detail?.data?.asJsonObject?.get("view")?.asString == "task"
                }, 10)
                PlatformTestUtil.waitWithEventsDispatching("role document request", {
                    Files.exists(kit.resolve("requests.log")) && Files.readString(kit.resolve("requests.log")).contains("--path change.md")
                }, 10)
                val tabs = descendants(panel).filterIsInstance<WorkbenchTabs>().first { it.titleAt(0) == "计划" }
                assertEquals(listOf("计划", "变更", "流程"), (0 until 3).map(tabs::titleAt))
                tabs.selectedIndex = 2
                PlatformTestUtil.waitWithEventsDispatching("verification", {
                    service.snapshot().verification?.data?.asJsonObject?.get("recordedResult")?.asString == "passed"
                }, 10)
                PlatformTestUtil.waitWithEventsDispatching("rendered verification", {
                    descendants(panel).filterIsInstance<JLabel>().any { it.text?.contains("上次检查结果") == true }
                }, 10)
                assertFalse(Files.readString(kit.resolve("requests.log")).contains("requirements/requirements.md"))
            } finally { Disposer.dispose(panel) }
        } finally {
            WorkbenchSettings.getInstance().state.bindings.removeIf { it.kitRoot == kit.toString() }
            directory.toFile().deleteRecursively()
        }
    }
}

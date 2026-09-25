package org.agentworkbench.intellij

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.Disposer
import org.agentworkbench.intellij.kit.KitClient
import org.agentworkbench.intellij.ui.WorkbenchPanel
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.swing.JTable
import javax.swing.JTabbedPane
import org.junit.Assume.assumeTrue

/** 显式提供运行时工作区后才运行，所有实际查询保持只读。 */
class CrmReadOnlyIntegrationTest : BasePlatformTestCase() {
    fun testActualWorkspaceThroughPluginClientAndNativePanel() {
        val configuredRoot = System.getProperty("workbench.integrationRoot", "")
        assumeTrue("显式 integrationRoot 才读取本机真实工作区", configuredRoot.isNotBlank())
        val root = Path.of(configuredRoot).toRealPath()
        val entry = Path.of(System.getProperty("workbench.integrationEntry")).toRealPath()
        val python = System.getProperty("workbench.testPython", "python3")
        val temporary = Files.createTempDirectory("workbench-integration-")
        val launcher = temporary.resolve("inspect-python")
        val quote: (String) -> String = { "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'" }
        Files.writeString(launcher, """
            #!/bin/sh
            if [ "${'$'}1" != "-B" ] || [ "${'$'}2" != ${quote(root.resolve("scripts/kit.py").toString())} ] || [ "${'$'}3" != "inspect" ]; then exit 2; fi
            shift 2
            exec ${quote(python)} -B ${quote(entry.toString())} "${'$'}@"
        """.trimIndent() + "\n")
        launcher.toFile().setExecutable(true, true)
        val before = snapshot(root)
        try {
            val client = KitClient(launcher, root)
            val responses = listOf("workspace", "items", "workflow", "runs").associateWith { client.inspect(it).getOrThrow() }
            assertTrue(responses.getValue("workspace").data!!.asJsonObject.getAsJsonArray("repositories").size() > 1)
            val items = responses.getValue("items").data!!.asJsonObject.getAsJsonArray("items")
            assertTrue(items.size() > 0)
            val slug = items[0].asJsonObject.get("slug").asString
            client.inspect("projection", listOf(slug, "--view", "task")).getOrThrow()
            client.inspect("document", listOf(slug, "--path", "README.md")).getOrThrow()
            client.inspect("verification", listOf(slug)).getOrThrow()
            val runs = responses.getValue("runs").data!!.asJsonObject.getAsJsonArray("items")
            if (!runs.isEmpty) client.inspect("run", listOf(runs[0].asJsonObject.get("id").asString)).getOrThrow()

            val service = WorkbenchService.getInstance(project)
            service.bind(root.toString(), launcher.toString()) { }
            PlatformTestUtil.waitWithEventsDispatching("真实工作区绑定", { service.snapshot().workspace != null && service.snapshot().items.isNotEmpty() }, 10)
            val wasDark=com.intellij.ui.JBColor.isBright().not()
            try {
                for(dark in listOf(true,false)) {
                    com.intellij.ui.JBColor.setDark(dark)
                    val theme=if(dark) "dark" else "light"
                    val panel=WorkbenchPanel(project)
                    try {
                        panel.setSize(1440,960);panel.refresh();panel.setActive(true);settle(panel)
                        PlatformTestUtil.waitWithEventsDispatching("Git 现场已显示", { panel.isGitSnapshotReady() },10)
                        panel.navigate("overview");settle(panel);capture(panel,"crm-overview-$theme")
                        panel.navigate("items");settle(panel);capture(panel,"crm-items-$theme")
                        panel.selectWorkItem(slug)
                        PlatformTestUtil.waitWithEventsDispatching("计划已显示", { service.snapshot().detail != null },10)
                        settle(panel);capture(panel,"crm-item-$theme")
                        val tabs=descendants(panel).filterIsInstance<org.agentworkbench.intellij.ui.WorkbenchTabs>().first { it.titleAt(0)=="计划" }
                        tabs.selectedIndex=org.agentworkbench.intellij.ui.WorkbenchPanel.CHANGES
                        PlatformTestUtil.waitWithEventsDispatching("变更投影", { service.snapshot().change != null },10)
                        settle(panel);capture(panel,"crm-changes-$theme")
                        tabs.selectedIndex=org.agentworkbench.intellij.ui.WorkbenchPanel.WORKFLOW
                        PlatformTestUtil.waitWithEventsDispatching("流程投影", { service.snapshot().flow != null },10)
                        settle(panel);capture(panel,"crm-workflow-$theme")
                        tabs.selectedIndex=org.agentworkbench.intellij.ui.WorkbenchPanel.PLAN
                        panel.setSize(1000,800);settle(panel);capture(panel,"crm-item-narrow-$theme")
                    } finally { Disposer.dispose(panel) }
                }
            } finally { com.intellij.ui.JBColor.setDark(wasDark) }
            assertEquals(before, snapshot(root))
        } finally { temporary.toFile().deleteRecursively() }
    }

    private fun settle(panel:Container) { repeat(3) { layout(panel);PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() } }
    private fun capture(panel:WorkbenchPanel,name:String) {
        val target=Path.of("build/ui-alignment/$name.png");Files.createDirectories(target.parent)
        val image=BufferedImage(panel.width,panel.height,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also { panel.printAll(it);it.dispose() };ImageIO.write(image,"png",target.toFile())
    }
    private fun snapshot(root: Path): Map<String, String> = Files.walk(root.resolve(".workspace")).use { paths ->
        paths.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }.toList().associate {
            it.toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).joinToString("") { byte -> "%02x".format(byte) }
        }
    }
    private fun descendants(component: java.awt.Component): List<java.awt.Component> = listOf(component) + if (component is Container) component.components.flatMap(::descendants) else emptyList()
    private fun layout(component: Container) { component.doLayout(); component.components.filterIsInstance<Container>().forEach(::layout) }
}

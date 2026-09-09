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
        val temporary = Files.createTempDirectory("workbench-integration-")
        val launcher = temporary.resolve("inspect-python")
        val quote: (String) -> String = { "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'" }
        Files.writeString(launcher, """
            #!/usr/bin/python3
            import os,sys
            args=sys.argv[1:]
            if args[:3] != ['-B',${quote(root.resolve("scripts/kit.py").toString())},'inspect']: sys.exit(2)
            args[1]=${quote(entry.toString())}
            os.execv('/usr/bin/python3',['/usr/bin/python3',*args])
        """.trimIndent() + "\n")
        launcher.toFile().setExecutable(true, true)
        val before = snapshot(root)
        try {
            val client = KitClient(launcher, root)
            val responses = listOf("workspace", "features", "workflow", "runs").associateWith { client.inspect(it).getOrThrow() }
            assertTrue(responses.getValue("workspace").data!!.asJsonObject.getAsJsonArray("repositories").size() > 1)
            val features = responses.getValue("features").data!!.asJsonObject.getAsJsonArray("items")
            assertTrue(features.size() > 0)
            val slug = features[0].asJsonObject.get("slug").asString
            client.inspect("feature", listOf(slug)).getOrThrow()
            client.inspect("document", listOf(slug, "--path", "README.md")).getOrThrow()
            client.inspect("verification", listOf(slug)).getOrThrow()
            val runs = responses.getValue("runs").data!!.asJsonObject.getAsJsonArray("items")
            if (!runs.isEmpty) client.inspect("run", listOf(runs[0].asJsonObject.get("id").asString)).getOrThrow()

            val service = WorkbenchService.getInstance(project)
            service.bind(root.toString(), launcher.toString()) { }
            PlatformTestUtil.waitWithEventsDispatching("真实工作区绑定", { service.snapshot().workspace != null && service.snapshot().features.isNotEmpty() }, 10)
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
                        panel.navigate("features");settle(panel);capture(panel,"crm-features-$theme")
                        panel.selectFeature(slug)
                        PlatformTestUtil.waitWithEventsDispatching("真实需求卡片已显示", {
                            descendants(panel).filterIsInstance<javax.swing.JLabel>().any { it.text=="需求范围" }
                        },10)
                        settle(panel);capture(panel,"crm-feature-$theme")
                        val tabs=descendants(panel).filterIsInstance<org.agentworkbench.intellij.ui.WorkbenchTabs>().first { it.tabCount==6 }
                        tabs.selectedIndex=org.agentworkbench.intellij.ui.WorkbenchPanel.DOCUMENTS
                        PlatformTestUtil.waitWithEventsDispatching("文档正文", { service.snapshot().document!=null },10)
                        settle(panel);capture(panel,"crm-document-$theme")
                        tabs.selectedIndex=org.agentworkbench.intellij.ui.WorkbenchPanel.VERIFY
                        settle(panel);capture(panel,"crm-verification-$theme")
                        tabs.selectedIndex=org.agentworkbench.intellij.ui.WorkbenchPanel.WORKFLOW
                        PlatformTestUtil.waitWithEventsDispatching("流程数据", { service.snapshot().workflow!=null },10)
                        settle(panel);capture(panel,"crm-workflow-$theme")
                        tabs.selectedIndex=org.agentworkbench.intellij.ui.WorkbenchPanel.SUMMARY
                        panel.setSize(1000,800);settle(panel);capture(panel,"crm-feature-narrow-$theme")
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

package org.agentworkbench.intellij

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.agentworkbench.intellij.ui.WorkbenchPanel
import org.agentworkbench.intellij.ui.WorkbenchTabs
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JTable

/** 用公开契约样例验证非空验证/流程页，实际渲染布局而非只检查存在组件。 */
class WorkbenchVisualTest:BasePlatformTestCase() {
    fun testFeatureNavigationEvidenceAndWorkflowLayout() {
        val directory=Files.createTempDirectory("workbench-visual-").toRealPath()
        val kit=Files.createDirectories(directory.resolve("kit"));Files.createDirectories(kit.resolve("scripts"))
        for(op in listOf("workspace","features","feature","document","verification","workflow","runs","run")) {
            val input=javaClass.getResourceAsStream("/inspect-v1/$op.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
            Files.writeString(kit.resolve("$op.json"),input.replace("/synthetic",directory.toString()))
        }
        Files.writeString(kit.resolve("scripts/kit.py"),"""
            import json,pathlib,sys
            root=pathlib.Path(sys.argv[sys.argv.index('--root')+1])
            op=sys.argv[sys.argv.index('--json')+1]
            data=json.loads((root/(op+'.json')).read_text())
            data['root']=str(root)
            print(json.dumps(data))
        """.trimIndent())
        val dark=!com.intellij.ui.JBColor.isBright()
        com.intellij.ui.JBColor.setDark(true)
        try {
            val service=WorkbenchService.getInstance(project)
            service.bind(kit.toString(),"/usr/bin/python3") { }
            PlatformTestUtil.waitWithEventsDispatching("fixture binding",{service.snapshot().workspace?.root==kit.toString()&&service.snapshot().features.isNotEmpty()},10)
            val panel=WorkbenchPanel(project)
            try {
                panel.setSize(1440,1000);panel.setActive(true);panel.navigate("overview");paint(panel,"sample-overview");panel.selectFeature("demo")
                waitText(panel,"需求范围")
                val tabs=descendants(panel).filterIsInstance<WorkbenchTabs>().first { it.tabCount==6 }
                assertEquals(listOf("概览", "文档", "计划 1/2", "变更", "验证", "流程"), (0 until 6).map(tabs::titleAt))
                val labels = descendants(panel).filterIsInstance<JLabel>().map { it.text }
                assertTrue(labels.containsAll(listOf("开发中", "开发实现")))
                val nav=descendants(panel).filterIsInstance<JButton>().mapNotNull { it.name }
                assertTrue(nav.toString(),nav.containsAll(listOf("nav-overview","nav-features","nav-runs","nav-extensions","nav-repo/service")))
                paint(panel,"sample-feature")
                tabs.selectedIndex=WorkbenchPanel.CHANGES
                val changes=descendants(panel).filterIsInstance<WorkbenchTabs>().first { it.tabCount==3 }
                assertEquals(listOf("代码评审","需求分支已提交","当前工作目录"),(0 until 3).map(changes::titleAt))
                paint(panel,"sample-changes-review")
                changes.selectedIndex=1
                paint(panel,"sample-changes-committed")
                tabs.selectedIndex=WorkbenchPanel.VERIFY
                waitText(panel,"检查 1",contains=true)
                paint(panel,"sample-verification")
                tabs.selectedIndex=WorkbenchPanel.WORKFLOW
                waitText(panel,"quality.integration",contains=true)
                PlatformTestUtil.waitWithEventsDispatching("Run 结果已显示",{descendants(panel).filterIsInstance<JLabel>().any { it.text=="配置一致" }},10)
                paint(panel,"sample-workflow")
                tabs.selectedIndex=WorkbenchPanel.SUMMARY
                val locate=descendants(panel).filterIsInstance<JButton>().first { it.text=="定位原文" }
                locate.doClick()
                assertEquals(WorkbenchPanel.DOCUMENTS,tabs.selectedIndex)
                PlatformTestUtil.waitWithEventsDispatching("定位任务原文",{service.snapshot().document?.data?.asJsonObject?.get("path")?.asString=="plans/implementation.md"},10)
                panel.navigate("features")
                assertTrue(descendants(panel).filterIsInstance<JButton>().any { it.text=="合成需求" })
            } finally { Disposer.dispose(panel) }
        } finally { com.intellij.ui.JBColor.setDark(dark);directory.toFile().deleteRecursively() }
    }
    private fun waitText(panel:Container,value:String,contains:Boolean=false) = PlatformTestUtil.waitWithEventsDispatching("显示 $value",{descendants(panel).filterIsInstance<JLabel>().any { if(contains) it.text?.contains(value)==true else it.text==value }},10)
    private fun paint(panel:WorkbenchPanel,name:String) {
        repeat(4) { layout(panel);PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
        val image=BufferedImage(panel.width,panel.height,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also { panel.printAll(it);it.dispose() }
        val target=Path.of("build/ui-alignment/$name.png");Files.createDirectories(target.parent);ImageIO.write(image,"png",target.toFile())
    }
    private fun layout(component:Container) { component.doLayout();component.components.filterIsInstance<Container>().forEach(::layout) }
    private fun descendants(component:Component):List<Component> = listOf(component)+if(component is Container) component.components.flatMap(::descendants) else emptyList()
}

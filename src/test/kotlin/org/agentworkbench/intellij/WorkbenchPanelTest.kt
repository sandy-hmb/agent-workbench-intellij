package org.agentworkbench.intellij

import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.agentworkbench.intellij.ui.DocumentReader
import org.agentworkbench.intellij.ui.WorkbenchPanel
import org.agentworkbench.intellij.ui.WorkbenchTabs
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JTable
import javax.swing.JTabbedPane
import javax.swing.JTextArea

class WorkbenchPanelTest : BasePlatformTestCase() {
    fun testDocumentReaderShowsHeadingsAndOriginalTextWithoutCreatingFiles() {
        val reader = DocumentReader(project) { error("Links require user action") }
        try {
            reader.showText("# 标题\n\n## 细节\n\n- [x] 已完成\n", 3, false)
            assertTrue(descendants(reader).filterIsInstance<javax.swing.JList<*>>().any { it.model.size == 2 })
            assertTrue(reader.goToAnchor("细节"))
            assertFalse(reader.goToAnchor("missing-anchor"))
            reader.goToLine(99)
        } finally { Disposer.dispose(reader) }
    }

    fun testWorkbenchHasDocumentSelectionAndSeparateEvidenceTables() {
        val panel = WorkbenchPanel(project)
        try {
            val controls = descendants(panel)
            val tabs = controls.filterIsInstance<WorkbenchTabs>().first { it.tabCount==6 }
            assertEquals(listOf("概览", "文档", "计划", "变更", "验证", "流程"), (0 until tabs.tabCount).map(tabs::titleAt))
            val nav = controls.filterIsInstance<javax.swing.JButton>().mapNotNull { it.name }
            assertTrue(nav.containsAll(listOf("nav-overview","nav-features","nav-runs","nav-extensions")))
            assertTrue(controls.filterIsInstance<JComboBox<*>>().size >= 3)
            assertTrue(controls.filterIsInstance<JTable>().all { !it.model.isCellEditable(0, 1) })
            assertTrue(controls.filterIsInstance<JTextArea>().all { !it.isEditable })
        } finally { Disposer.dispose(panel) }
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}

package org.agentworkbench.intellij

import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.agentworkbench.intellij.ui.DocumentReader
import org.agentworkbench.intellij.ui.WorkbenchPanel
import org.agentworkbench.intellij.ui.WorkbenchTabs
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
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
            assertTrue(nav.containsAll(listOf("nav-overview","nav-features","nav-search","nav-runs","nav-extensions")))
            val buttons = controls.filterIsInstance<JButton>().map { it.text }
            assertTrue(buttons.containsAll(listOf("Log", "Diff", "Commit", "Branches", "Fetch")))
            assertTrue(controls.filterIsInstance<JComboBox<*>>().size >= 3)
            assertTrue(controls.filterIsInstance<JTable>().all { !it.model.isCellEditable(0, 1) })
            assertTrue(controls.filterIsInstance<JTextArea>().all { !it.isEditable })
            assertEquals("暂无计划", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(0, 0))
        } finally { Disposer.dispose(panel) }
    }

    fun testPlanProgressFormatting() {
        assertEquals("暂无计划", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(0, 0))
        assertEquals("3 / 5 项", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5))
        assertEquals("3 / 5 (60%)", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5, percentage = true))
        assertEquals("3 / 5 项 (可信 2)", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5, percentage = false, trustedCompleted = 2))
        assertEquals("3 / 5 项 (可信 2) (60%)", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5, percentage = true, trustedCompleted = 2))
    }

    fun testDocumentTreePanelCategorizationAndSelection() {
        val reader = org.agentworkbench.intellij.ui.DocumentReader(project) {}
        try {
            var selectedPath: String? = null
            val treePanel = org.agentworkbench.intellij.ui.DocumentTreePanel(
                project = project,
                reader = reader,
                onDocumentSelected = { selectedPath = it },
                onOpenInEditor = {}
            )

            val files = listOf(
                com.google.gson.JsonObject().apply { addProperty("path", "requirements/requirements.md"); addProperty("exists", true) },
                com.google.gson.JsonObject().apply { addProperty("path", "requirements/jira-PD-1968.md"); addProperty("exists", true) },
                com.google.gson.JsonObject().apply { addProperty("path", "design/design.md"); addProperty("exists", true) },
                com.google.gson.JsonObject().apply { addProperty("path", "README.md"); addProperty("exists", true) }
            )
            val reviews = com.google.gson.JsonObject().apply {
                addProperty("requirements", "已批准")
                addProperty("design", "待审阅")
            }

            treePanel.setDocuments(files, reviews)

            // Verify initial selection selects first core document
            assertEquals("requirements/requirements.md", treePanel.activePath)

            // Select Jira requirements document
            treePanel.selectDocument("requirements/jira-PD-1968.md")
            assertEquals("requirements/jira-PD-1968.md", treePanel.activePath)
            assertEquals("requirements/jira-PD-1968.md", selectedPath)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(reader)
        }
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}

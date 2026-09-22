package org.agentworkbench.intellij

import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.agentworkbench.intellij.git.GitScanService
import org.agentworkbench.intellij.ui.DocumentReader
import org.agentworkbench.intellij.ui.FeatureChangesPanel
import org.agentworkbench.intellij.ui.FeatureReviewPanel
import org.agentworkbench.intellij.ui.GitPanel
import org.agentworkbench.intellij.ui.WorkbenchPanel
import org.agentworkbench.intellij.ui.WorkbenchTabs
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JTable
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

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
            val changes = controls.filterIsInstance<WorkbenchTabs>().first { it.tabCount == 3 }
            assertEquals(listOf("代码评审", "需求分支已提交", "当前工作目录"), (0 until changes.tabCount).map(changes::titleAt))
            assertEquals(0, changes.selectedIndex)
            val review = controls.filterIsInstance<FeatureReviewPanel>().single()
            assertSame(changes, generateSequence(review.parent) { it.parent }.filterIsInstance<WorkbenchTabs>().first())
            val nav = controls.filterIsInstance<javax.swing.JButton>().mapNotNull { it.name }
            assertTrue(nav.containsAll(listOf("nav-overview","nav-features","nav-search","nav-runs","nav-extensions")))
            val buttons = controls.filterIsInstance<JButton>().map { it.text }
            assertTrue(buttons.containsAll(listOf("Log", "Diff", "Commit", "Branches", "Fetch")))
            val combos = controls.filterIsInstance<JComboBox<*>>()
            assertTrue(combos.size >= 3)
            assertEquals("未完成", combos.single { combo -> (0 until combo.itemCount).map(combo::getItemAt).containsAll(listOf("未完成", "done")) }.selectedItem)
            assertTrue(controls.filterIsInstance<JTable>().all { !it.model.isCellEditable(0, 1) })
            assertTrue(controls.filterIsInstance<JTextArea>().all { !it.isEditable })
            assertEquals("暂无计划", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(0, 0))
        } finally { Disposer.dispose(panel) }
    }

    fun testCommittedChangesLoadsFilesAndCommitsWithoutComboBoxEvent() {
        val root = java.nio.file.Files.createTempDirectory("workbench-feature-changes-").toFile()
        try {
            git(root, "init", "-b", "main")
            root.resolve("base.txt").writeText("base")
            git(root, "add", ".")
            git(root, "commit", "-m", "base")
            git(root, "switch", "-c", "feature/demo")
            root.resolve("feature.txt").writeText("feature")
            git(root, "add", ".")
            git(root, "commit", "-m", "feature")
            git(root, "switch", "main")
            val feature = JsonParser.parseString("""{"summary":{"repositoryBindings":[{"repository":"service","baseBranch":"main","workBranch":"feature/demo"}]}}""").asJsonObject
            val repositories = listOf(JsonParser.parseString("""{"id":"service","absolutePath":${com.google.gson.Gson().toJson(root.path)}}""").asJsonObject)
            val panel = FeatureChangesPanel(project)
            try {
                panel.setSize(1200, 560)
                panel.showFeature(feature, repositories)
                com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("变更加载", {
                    descendants(panel).filterIsInstance<JList<*>>().let { it.size == 2 && it.all { list -> list.model.size == 1 } }
                }, 10)
                repeat(3) { layout(panel) }
                val lists = descendants(panel).filterIsInstance<JList<*>>()
                assertEquals(2, lists.size)
                assertTrue(lists.all { it.height > 100 })
            } finally { Disposer.dispose(panel) }
        } finally { root.deleteRecursively() }
    }

    fun testPlanProgressFormatting() {
        assertEquals("暂无计划", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(0, 0))
        assertEquals("3 / 5 项", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5))
        assertEquals("3 / 5 (60%)", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5, percentage = true))
        assertEquals("3 / 5 项 (可信 2)", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5, percentage = false, trustedCompleted = 2))
        assertEquals("3 / 5 项 (可信 2) (60%)", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5, percentage = true, trustedCompleted = 2))
    }

    fun testFeatureCompletionRequiresFinishedTrustedPlan() {
        val feature = JsonParser.parseString("""{"status":"testing","planSummary":{"completed":2,"total":2,"trustedProgress":{"applicable":true,"completed":1,"total":2}}}""").asJsonObject
        assertEquals("仍有 1 个计划项缺少可信凭据", WorkbenchService.completionBlocker(feature))
        feature.getAsJsonObject("planSummary").getAsJsonObject("trustedProgress").addProperty("completed", 2)
        assertNull(WorkbenchService.completionBlocker(feature))
    }

    fun testGitPanelShowsAtMostFiveRepositoriesBeforeActions() {
        val scanService = GitScanService.getInstance(project)
        val panel = GitPanel(project)
        try {
            scanService.refresh((1..6).map { index ->
                com.google.gson.JsonObject().apply {
                    addProperty("id", "repo-$index")
                    addProperty("role", "business")
                    addProperty("absolutePath", "/missing/repo-$index")
                    addProperty("availability", "missing")
                }
            })
            val controls = descendants(panel)
            val table = controls.filterIsInstance<JTable>().single()
            val scroll = controls.filterIsInstance<JScrollPane>().single { it.viewport.view === table }
            assertEquals(table.tableHeader.preferredSize.height + table.rowHeight * 5, scroll.preferredSize.height)

            panel.setSize(1200, 800)
            repeat(3) { layout(panel) }
            val log = controls.filterIsInstance<JButton>().single { it.text == "Log" }
            val tableBottom = SwingUtilities.convertPoint(scroll, 0, scroll.height, panel).y
            val actionTop = SwingUtilities.convertPoint(log, 0, 0, panel).y
            assertTrue(actionTop >= tableBottom)
            assertTrue(actionTop + log.height <= panel.height)
        } finally {
            Disposer.dispose(panel)
            scanService.refresh(emptyList())
        }
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

    private fun layout(component: Container) { component.doLayout(); component.components.filterIsInstance<Container>().forEach(::layout) }

    private fun git(root: java.io.File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.path, *arguments)).redirectErrorStream(true).apply {
            environment()["GIT_AUTHOR_NAME"] = "Test"; environment()["GIT_AUTHOR_EMAIL"] = "test@example.invalid"
            environment()["GIT_COMMITTER_NAME"] = "Test"; environment()["GIT_COMMITTER_EMAIL"] = "test@example.invalid"
        }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { output }
    }
}

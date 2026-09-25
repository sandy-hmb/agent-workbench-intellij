package org.agentworkbench.intellij

import com.google.gson.JsonParser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.agentworkbench.intellij.git.GitScanService
import org.agentworkbench.intellij.ui.DocumentReader
import org.agentworkbench.intellij.ui.WorkItemChangesPanel
import org.agentworkbench.intellij.ui.WorkItemReviewPanel
import org.agentworkbench.intellij.ui.GitPanel
import org.agentworkbench.intellij.ui.WorkbenchPanel
import org.agentworkbench.intellij.ui.WorkbenchTabs
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JTable
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
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
            val tabs = controls.filterIsInstance<WorkbenchTabs>().first { it.titleAt(0) == "计划" }
            assertEquals(listOf("计划", "变更", "流程", "交付物"), (0 until tabs.tabCount).map(tabs::titleAt))
            assertEquals(0, tabs.selectedIndex)
            val changes = controls.filterIsInstance<WorkbenchTabs>().first { it.titleAt(0) == "代码评审" }
            assertEquals(listOf("代码评审", "需求分支已提交", "当前工作目录"), (0 until changes.tabCount).map(changes::titleAt))
            assertEquals(0, changes.selectedIndex)
            val review = controls.filterIsInstance<WorkItemReviewPanel>().single()
            assertSame(changes, generateSequence(review.parent) { it.parent }.filterIsInstance<WorkbenchTabs>().first())
            val nav = controls.filterIsInstance<javax.swing.JButton>().mapNotNull { it.name }
            assertTrue(nav.containsAll(listOf("nav-overview","nav-items","nav-search","nav-runs","nav-extensions")))
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
        val root = java.nio.file.Files.createTempDirectory("workbench-item-changes-").toFile()
        try {
            git(root, "init", "-b", "main")
            root.resolve("base.txt").writeText("base")
            git(root, "add", ".")
            git(root, "commit", "-m", "base")
            git(root, "switch", "-c", "item/demo")
            root.resolve("item.txt").writeText("item")
            git(root, "add", ".")
            git(root, "commit", "-m", "item")
            git(root, "switch", "main")
            val item = JsonParser.parseString("""{"summary":{"repositoryBindings":[{"repository":"service","baseBranch":"main","workBranch":"item/demo"}]}}""").asJsonObject
            val repositories = listOf(JsonParser.parseString("""{"id":"service","absolutePath":${com.google.gson.Gson().toJson(root.path)}}""").asJsonObject)
            val panel = WorkItemChangesPanel(project)
            try {
                panel.setSize(1200, 560)
                panel.showWorkItem(item, repositories)
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

    fun testEditingTakeoverCommitDisablesOldCommittedDiffUntilComparisonSucceeds() {
        val root = Files.createTempDirectory("panel-takeover-").toFile()
        try {
            git(root, "init", "-b", "main")
            root.resolve("base.txt").writeText("base")
            git(root, "add", ".")
            git(root, "commit", "-m", "base")
            git(root, "switch", "-c", "item")
            root.resolve("first.txt").writeText("first")
            git(root, "add", ".")
            git(root, "commit", "-m", "first")
            val takeover = git(root, "rev-parse", "HEAD").trim()
            root.resolve("second.txt").writeText("second")
            git(root, "add", ".")
            git(root, "commit", "-m", "second")
            val item = JsonParser.parseString("""{"summary":{"slug":"takeover","repositoryBindings":[{"repository":"service","baseBranch":"main","workBranch":"item"}]}}""").asJsonObject
            val repositories = listOf(JsonParser.parseString("""{"id":"service","absolutePath":${com.google.gson.Gson().toJson(root.path)}}""").asJsonObject)
            val panel = WorkItemChangesPanel(project)
            try {
                panel.showWorkItem(item, repositories)
                val button = descendants(panel).filterIsInstance<JButton>().single { it.text == "打开已提交 Diff" }
                val field = descendants(panel).filterIsInstance<JTextField>().single()
                val lists = descendants(panel).filterIsInstance<JList<*>>()
                PlatformTestUtil.waitWithEventsDispatching("初始比较", { button.isEnabled && lists.first().model.size == 2 }, 10)
                field.text = takeover
                assertFalse("修改起点后旧 Diff 不得可点", button.isEnabled)
                button.doClick()
                assertFalse(button.isEnabled)
                field.postActionEvent()
                PlatformTestUtil.waitWithEventsDispatching("应用接手起点", {
                    button.isEnabled && lists.first().model.size == 1 && lists.first().model.getElementAt(0) == "second.txt"
                }, 10)
                field.text = "invalid"
                assertFalse(button.isEnabled)
                field.postActionEvent()
                PlatformTestUtil.waitWithEventsDispatching("无效接手起点", {
                    descendants(panel).filterIsInstance<JLabel>().any { it.text?.contains("读取失败：接手起点不是有效 commit") == true }
                }, 10)
                assertFalse(button.isEnabled)
            } finally { Disposer.dispose(panel) }
        } finally { root.deleteRecursively() }
    }

    fun testWorkItemSwitchAndFailureNeverShowPreviousWorkItemComparison() {
        val root = Files.createTempDirectory("panel-item-a-").toFile()
        try {
            git(root, "init", "-b", "main")
            root.resolve("base.txt").writeText("base")
            git(root, "add", ".")
            git(root, "commit", "-m", "base")
            git(root, "switch", "-c", "item")
            root.resolve("a.txt").writeText("a")
            git(root, "add", ".")
            git(root, "commit", "-m", "a")
            val featureA = JsonParser.parseString("""{"summary":{"slug":"alpha","repositoryBindings":[{"repository":"first","baseBranch":"main","workBranch":"item"},{"repository":"second","baseBranch":"main","workBranch":"item"}]}}""").asJsonObject
            val featureB = JsonParser.parseString("""{"summary":{"slug":"beta","repositoryBindings":[{"repository":"first","baseBranch":"main","workBranch":"missing"}]}}""").asJsonObject
            val repositories = listOf("first", "second").map { id ->
                JsonParser.parseString("""{"id":"$id","absolutePath":${com.google.gson.Gson().toJson(root.path)}}""").asJsonObject
            }
            val panel = WorkItemChangesPanel(project)
            try {
                panel.showWorkItem(featureA, repositories)
                val button = descendants(panel).filterIsInstance<JButton>().single { it.text == "打开已提交 Diff" }
                val lists = descendants(panel).filterIsInstance<JList<*>>()
                PlatformTestUtil.waitWithEventsDispatching("alpha 比较", { button.isEnabled && lists.all { it.model.size == 1 } && labels(panel).any { it.contains("first ·") } }, 10)
                panel.restoreLocation("second", "", "")
                assertFalse(button.isEnabled)
                assertTrue(lists.all { it.model.size == 0 })
                assertFalse(labels(panel).any { it.contains("first ·") || it.contains("实际比较：") || it.contains("1 个文件") })
                panel.showWorkItem(featureB, repositories)
                PlatformTestUtil.waitWithEventsDispatching("beta 读取失败", { labels(panel).any { it.contains("读取失败：未找到需求分支 missing") } }, 10)
                assertFalse(button.isEnabled)
                assertTrue(lists.all { it.model.size == 0 })
                assertTrue(labels(panel).any { it.contains("first · 读取失败") })
                assertFalse(labels(panel).any { it.contains("second ·") || it.contains("实际比较：") || it.contains("1 个文件") })
            } finally { Disposer.dispose(panel) }
        } finally { root.deleteRecursively() }
    }

    fun testSameWorkItemFailureKeepsSuccessfulComparisonMarkedStale() {
        val root = Files.createTempDirectory("panel-item-stale-").toFile()
        try {
            git(root, "init", "-b", "main")
            root.resolve("base.txt").writeText("base")
            git(root, "add", ".")
            git(root, "commit", "-m", "base")
            git(root, "switch", "-c", "item")
            root.resolve("a.txt").writeText("a")
            git(root, "add", ".")
            git(root, "commit", "-m", "a")
            val item = JsonParser.parseString("""{"summary":{"slug":"alpha","repositoryBindings":[{"repository":"service","baseBranch":"main","workBranch":"item"}]}}""").asJsonObject
            val repositories = listOf(JsonParser.parseString("""{"id":"service","absolutePath":${com.google.gson.Gson().toJson(root.path)}}""").asJsonObject)
            val panel = WorkItemChangesPanel(project)
            try {
                panel.showWorkItem(item, repositories)
                val button = descendants(panel).filterIsInstance<JButton>().single { it.text == "打开已提交 Diff" }
                val lists = descendants(panel).filterIsInstance<JList<*>>()
                PlatformTestUtil.waitWithEventsDispatching("alpha 成功", { button.isEnabled && lists.first().model.size == 1 }, 10)
                panel.restoreLocation("service", "", "invalid")
                panel.showWorkItem(item, repositories)
                PlatformTestUtil.waitWithEventsDispatching("alpha 重读失败", { labels(panel).any { it.contains("读取失败：接手起点不是有效 commit") } }, 10)
                assertFalse(button.isEnabled)
                assertEquals("a.txt", lists.first().model.getElementAt(0))
                assertTrue(labels(panel).any { it.contains("实际比较：") && it.contains("过期") })
                assertTrue(labels(panel).any { it.contains("service ·") && it.contains("过期") })
            } finally { Disposer.dispose(panel) }
        } finally { root.deleteRecursively() }
    }



    fun testMultiRepositoryComparisonFailureDoesNotShowZeroChanges() {
        val panel = WorkItemChangesPanel(project)
        try {
            val item = JsonParser.parseString("""{"summary":{"repositoryBindings":[{"repository":"first","baseBranch":"main","workBranch":"item/a"},{"repository":"second","baseBranch":"main","workBranch":"item/b"}]}}""").asJsonObject
            val repositories = listOf("first", "second").map { id ->
                JsonParser.parseString("""{"id":"$id","absolutePath":"/missing/$id"}""").asJsonObject
            }
            panel.showWorkItem(item, repositories)
            PlatformTestUtil.waitWithEventsDispatching("多仓失败摘要", {
                descendants(panel).filterIsInstance<javax.swing.JLabel>().count { it.text?.contains("工作目录读取失败") == true } == 2
            }, 10)
            val labels = descendants(panel).filterIsInstance<javax.swing.JLabel>().map { it.text.orEmpty() }
            assertTrue(labels.any { it.contains("first · 读取失败") })
            assertTrue(labels.any { it.contains("second · 读取失败") })
            assertFalse(labels.any { it.contains("工作目录 0 项") || it.contains("· 0 文件") })
        } finally { Disposer.dispose(panel) }
    }

    fun testPlanProgressFormatting() {
        assertEquals("暂无计划", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(0, 0))
        assertEquals("3 / 5 项", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5))
        assertEquals("3 / 5 (60%)", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5, percentage = true))
        assertEquals("3 / 5 项 (可信 2)", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5, percentage = false, trustedCompleted = 2))
        assertEquals("3 / 5 项 (可信 2) (60%)", org.agentworkbench.intellij.ui.WorkbenchUi.planProgress(3, 5, percentage = true, trustedCompleted = 2))
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

    private fun labels(component: Component): List<String> = descendants(component).filterIsInstance<JLabel>().map { it.text.orEmpty() }

    private fun layout(component: Container) { component.doLayout(); component.components.filterIsInstance<Container>().forEach(::layout) }

    private fun git(root: java.io.File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path, *arguments)).redirectErrorStream(true).apply {
            environment()["GIT_AUTHOR_NAME"] = "Test"; environment()["GIT_AUTHOR_EMAIL"] = "test@example.invalid"
            environment()["GIT_COMMITTER_NAME"] = "Test"; environment()["GIT_COMMITTER_EMAIL"] = "test@example.invalid"
        }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { output }
        return output
    }
}

package org.agentworkbench.intellij

import com.google.gson.JsonParser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
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
            assertEquals(listOf("计划", "变更", "流程"), (0 until tabs.tabCount).map(tabs::titleAt))
            assertEquals(0, tabs.selectedIndex)
            val changes = controls.filterIsInstance<WorkbenchTabs>().first { it.titleAt(0) == "代码评审" }
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

    fun testEditingTakeoverCommitDisablesOldCommittedDiffUntilComparisonSucceeds() {
        val root = Files.createTempDirectory("panel-takeover-").toFile()
        try {
            git(root, "init", "-b", "main")
            root.resolve("base.txt").writeText("base")
            git(root, "add", ".")
            git(root, "commit", "-m", "base")
            git(root, "switch", "-c", "feature")
            root.resolve("first.txt").writeText("first")
            git(root, "add", ".")
            git(root, "commit", "-m", "first")
            val takeover = git(root, "rev-parse", "HEAD").trim()
            root.resolve("second.txt").writeText("second")
            git(root, "add", ".")
            git(root, "commit", "-m", "second")
            val feature = JsonParser.parseString("""{"summary":{"slug":"takeover","repositoryBindings":[{"repository":"service","baseBranch":"main","workBranch":"feature"}]}}""").asJsonObject
            val repositories = listOf(JsonParser.parseString("""{"id":"service","absolutePath":${com.google.gson.Gson().toJson(root.path)}}""").asJsonObject)
            val panel = FeatureChangesPanel(project)
            try {
                panel.showFeature(feature, repositories)
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

    fun testFeatureSwitchAndFailureNeverShowPreviousFeatureComparison() {
        val root = Files.createTempDirectory("panel-feature-a-").toFile()
        try {
            git(root, "init", "-b", "main")
            root.resolve("base.txt").writeText("base")
            git(root, "add", ".")
            git(root, "commit", "-m", "base")
            git(root, "switch", "-c", "feature")
            root.resolve("a.txt").writeText("a")
            git(root, "add", ".")
            git(root, "commit", "-m", "a")
            val featureA = JsonParser.parseString("""{"summary":{"slug":"alpha","repositoryBindings":[{"repository":"first","baseBranch":"main","workBranch":"feature"},{"repository":"second","baseBranch":"main","workBranch":"feature"}]}}""").asJsonObject
            val featureB = JsonParser.parseString("""{"summary":{"slug":"beta","repositoryBindings":[{"repository":"first","baseBranch":"main","workBranch":"missing"}]}}""").asJsonObject
            val repositories = listOf("first", "second").map { id ->
                JsonParser.parseString("""{"id":"$id","absolutePath":${com.google.gson.Gson().toJson(root.path)}}""").asJsonObject
            }
            val panel = FeatureChangesPanel(project)
            try {
                panel.showFeature(featureA, repositories)
                val button = descendants(panel).filterIsInstance<JButton>().single { it.text == "打开已提交 Diff" }
                val lists = descendants(panel).filterIsInstance<JList<*>>()
                PlatformTestUtil.waitWithEventsDispatching("alpha 比较", { button.isEnabled && lists.all { it.model.size == 1 } && labels(panel).any { it.contains("first ·") } }, 10)
                panel.restoreLocation("second", "", "")
                assertFalse(button.isEnabled)
                assertTrue(lists.all { it.model.size == 0 })
                assertFalse(labels(panel).any { it.contains("first ·") || it.contains("实际比较：") || it.contains("1 个文件") })
                panel.showFeature(featureB, repositories)
                PlatformTestUtil.waitWithEventsDispatching("beta 读取失败", { labels(panel).any { it.contains("读取失败：未找到需求分支 missing") } }, 10)
                assertFalse(button.isEnabled)
                assertTrue(lists.all { it.model.size == 0 })
                assertTrue(labels(panel).any { it.contains("first · 读取失败") })
                assertFalse(labels(panel).any { it.contains("second ·") || it.contains("实际比较：") || it.contains("1 个文件") })
            } finally { Disposer.dispose(panel) }
        } finally { root.deleteRecursively() }
    }

    fun testSameFeatureFailureKeepsSuccessfulComparisonMarkedStale() {
        val root = Files.createTempDirectory("panel-feature-stale-").toFile()
        try {
            git(root, "init", "-b", "main")
            root.resolve("base.txt").writeText("base")
            git(root, "add", ".")
            git(root, "commit", "-m", "base")
            git(root, "switch", "-c", "feature")
            root.resolve("a.txt").writeText("a")
            git(root, "add", ".")
            git(root, "commit", "-m", "a")
            val feature = JsonParser.parseString("""{"summary":{"slug":"alpha","repositoryBindings":[{"repository":"service","baseBranch":"main","workBranch":"feature"}]}}""").asJsonObject
            val repositories = listOf(JsonParser.parseString("""{"id":"service","absolutePath":${com.google.gson.Gson().toJson(root.path)}}""").asJsonObject)
            val panel = FeatureChangesPanel(project)
            try {
                panel.showFeature(feature, repositories)
                val button = descendants(panel).filterIsInstance<JButton>().single { it.text == "打开已提交 Diff" }
                val lists = descendants(panel).filterIsInstance<JList<*>>()
                PlatformTestUtil.waitWithEventsDispatching("alpha 成功", { button.isEnabled && lists.first().model.size == 1 }, 10)
                panel.restoreLocation("service", "", "invalid")
                panel.showFeature(feature, repositories)
                PlatformTestUtil.waitWithEventsDispatching("alpha 重读失败", { labels(panel).any { it.contains("读取失败：接手起点不是有效 commit") } }, 10)
                assertFalse(button.isEnabled)
                assertEquals("a.txt", lists.first().model.getElementAt(0))
                assertTrue(labels(panel).any { it.contains("实际比较：") && it.contains("过期") })
                assertTrue(labels(panel).any { it.contains("service ·") && it.contains("过期") })
            } finally { Disposer.dispose(panel) }
        } finally { root.deleteRecursively() }
    }

    fun testSwitchingFeaturesKeepsPlanFilterTaskAndScrollSeparate() {
        val root = Files.createTempDirectory("panel-switch-")
        val settings = WorkbenchSettings.getInstance()
        val service = WorkbenchService.getInstance(project)
        val panel: WorkbenchPanel
        try {
            Files.createDirectories(root.resolve("scripts"))
            listOf("workspace", "features", "feature").forEach { op ->
                Files.write(root.resolve("$op.json"), javaClass.getResourceAsStream("/inspect-v1/$op.json")!!.use { it.readBytes() })
            }
            val workspace = JsonParser.parseString(Files.readString(root.resolve("workspace.json"))).asJsonObject
            workspace.getAsJsonObject("data").getAsJsonObject("protocol").getAsJsonArray("operations").add("projection")
            Files.writeString(root.resolve("workspace.json"), workspace.toString())
            val feature = JsonParser.parseString(Files.readString(root.resolve("feature.json"))).asJsonObject
            val tasks = feature.getAsJsonObject("data").getAsJsonArray("tasks")
            repeat(60) { index -> tasks.add(JsonParser.parseString("""{"id":"T${index + 10}","title":"Task $index","completed":false,"path":"plans/implementation.md","startLine":1}""").asJsonObject) }
            feature.addProperty("operation", "projection")
            feature.getAsJsonObject("data").addProperty("view", "task")
            Files.writeString(root.resolve("projection.json"), feature.toString())
            Files.writeString(root.resolve("scripts/kit.py"), """
                import json,pathlib,sys
                root=pathlib.Path(sys.argv[sys.argv.index('--root')+1])
                op=sys.argv[sys.argv.index('--json')+1]
                data=json.loads((root/(op+'.json')).read_text())
                data['root']=str(root)
                if op=='projection': data['data']['summary']['slug']=sys.argv[sys.argv.index('--json')+2]
                print(json.dumps(data))
            """.trimIndent())
            service.bind(root.toString(), "/usr/bin/python3") { }
            PlatformTestUtil.waitWithEventsDispatching("绑定工作区", { service.snapshot().features.isNotEmpty() }, 10)
            panel = WorkbenchPanel(project)
            try {
                panel.setSize(1200, 700)
                panel.selectFeature("alpha")
                PlatformTestUtil.waitWithEventsDispatching("alpha 加载", { service.snapshot().detail != null || service.snapshot().error != null }, 10)
                assertNull(service.snapshot().error)
                PlatformTestUtil.waitWithEventsDispatching("计划行加载", { descendants(panel).filterIsInstance<JTable>().any { it.rowCount > 50 } }, 10)
                repeat(3) { layout(panel) }
                val controls = descendants(panel)
                val tabs = controls.filterIsInstance<WorkbenchTabs>().first { it.titleAt(0) == "计划" }
                val filter = controls.filterIsInstance<JComboBox<*>>().single { it.itemCount == 5 && it.getItemAt(0) == "全部任务" }
                val table = controls.filterIsInstance<JTable>().first { it.rowCount > 50 }
                val scroll = controls.filterIsInstance<JScrollPane>().single { it.viewport.view === table }
                scroll.setSize(900, 300)
                layout(scroll)
                scroll.viewport.dispatchEvent(java.awt.event.ComponentEvent(scroll.viewport, java.awt.event.ComponentEvent.COMPONENT_RESIZED))
                filter.selectedItem = "可继续"
                PlatformTestUtil.waitWithEventsDispatching("viewport layout", { scroll.viewport.extentSize.height > 0 }, 5)
                table.setRowSelectionInterval(25, 25)
                scroll.verticalScrollBar.value = 240
                val offset = scroll.verticalScrollBar.value
                assertTrue("plan should scroll", offset > 0)
                PlatformTestUtil.waitWithEventsDispatching("滚动保存", { settings.featureView(root.toString(), "alpha").offset == offset }, 5)
                tabs.selectedIndex = 1
                panel.selectFeature("beta")
                PlatformTestUtil.waitWithEventsDispatching("beta 加载", { table.rowCount > 50 }, 10)
                assertEquals(0, tabs.selectedIndex)
                assertEquals("全部任务", filter.selectedItem)
                assertEquals(0, settings.featureView(root.toString(), "beta").offset)
                tabs.selectedIndex = 2
                filter.selectedItem = "已完成"
                panel.selectFeature("alpha")
                PlatformTestUtil.waitWithEventsDispatching("alpha 恢复", { table.rowCount > 50 && filter.selectedItem == "可继续" }, 10)
                assertEquals(1, tabs.selectedIndex)
                assertEquals(offset, settings.featureView(root.toString(), "alpha").offset)
                tabs.selectedIndex = 0
                repeat(3) { layout(panel) }
                PlatformTestUtil.waitWithEventsDispatching("滚动恢复", { scroll.verticalScrollBar.value == offset }, 10)
                assertEquals("T34", table.getValueAt(table.selectedRow, 0))
                assertEquals("已完成", settings.featureView(root.toString(), "beta").filter)
                assertEquals(2, settings.featureView(root.toString(), "beta").tab)
            } finally { Disposer.dispose(panel) }
        } finally {
            settings.state.featureViews.removeIf { it.root == root.toString() }
            settings.state.preferences.removeIf { it.root == root.toString() }
            root.toFile().deleteRecursively()
        }
    }

    fun testWorkflowUsesSelectedFeatureRunAndOnDemandVerificationAndOffersExistingDocuments() {
        val root = Files.createTempDirectory("panel-flow-")
        val settings = WorkbenchSettings.getInstance()
        val service = WorkbenchService.getInstance(project)
        try {
            Files.createDirectories(root.resolve("scripts"))
            listOf("workspace", "features", "feature", "verification").forEach { op ->
                Files.write(root.resolve("$op.json"), javaClass.getResourceAsStream("/inspect-v1/$op.json")!!.use { it.readBytes() })
            }
            val workspace = JsonParser.parseString(Files.readString(root.resolve("workspace.json"))).asJsonObject
            workspace.getAsJsonObject("data").getAsJsonObject("protocol").getAsJsonArray("operations").add("projection")
            Files.writeString(root.resolve("workspace.json"), workspace.toString())
            val documents = listOf("requirements/requirements.md", "design/design.md", "plans/implementation.md", "changes/change.md", "README.md")
            val feature = JsonParser.parseString(Files.readString(root.resolve("feature.json"))).asJsonObject
            feature.addProperty("operation", "projection")
            feature.getAsJsonObject("data").addProperty("view", "task")
            Files.writeString(root.resolve("projection.json"), feature.toString())
            for (slug in listOf("alpha", "beta")) {
                for (path in documents) {
                    val file = root.resolve(".workspace/docs/features/$slug/$path")
                    Files.createDirectories(file.parent)
                    Files.writeString(file, path)
                }
            }
            Files.writeString(root.resolve("scripts/kit.py"), """
                import json,pathlib,sys
                root=pathlib.Path(sys.argv[sys.argv.index('--root')+1])
                op=sys.argv[sys.argv.index('--json')+1]
                slug=sys.argv[sys.argv.index('--json')+2] if op in ('projection','verification') else None
                with (root/'requests.log').open('a') as log: log.write(op+' '+(slug or '')+' '+(' '.join(sys.argv[1:]))+'\n')
                source='projection' if op=='projection' else op
                data=json.loads((root/(source+'.json')).read_text()) if source in ('workspace','features','projection','verification') else None
                if op=='projection':
                    view=sys.argv[sys.argv.index('--view')+1]
                    if view=='flow':
                        data['data']={'view':'flow','featureRevision':'rev','status':'development','workflow':{'orderedStages':[{'id':'feature.implement','core':True}], 'extensions':[]},'delivery':{'verification':{'exists':True,'codeState':'not_checked'}}}
                    else:
                        data['data']['summary']['slug']=slug
                        data['data']['summary']['path']='.workspace/docs/features/'+slug
                        data['data']['files']=[{'path':path,'exists':True} for path in ${com.google.gson.Gson().toJson(documents)}]
                elif op=='verification':
                    data['data']['slug']=slug
                elif op=='runs':
                    records=[{'id':'alpha-run','featureSlug':'alpha','updatedAt':'today','recordCounts':{'total':1}}] if '--feature' not in sys.argv or sys.argv[sys.argv.index('--feature')+1]=='alpha' else []
                    data={'apiVersion':{'major':1,'minor':0},'operation':op,'status':'ok','observedAt':'2026-09-08T10:00:00Z','root':str(root),'revision':'rev','diagnostics':[], 'data':{'items':records,'counts':{'parsed':len(records)},'page':{'offset':0,'limit':100,'total':len(records),'hasMore':False}}}
                elif op=='run':
                    data={'apiVersion':{'major':1,'minor':0},'operation':op,'status':'ok','observedAt':'2026-09-08T10:00:00Z','root':str(root),'revision':'rev','diagnostics':[], 'data':{'id':'alpha-run','featureSlug':'alpha','records':[{'stage':'feature.implement','status':'done','summary':'ALPHA RUN ONLY','updatedAt':'today'}]}}
                data['root']=str(root)
                print(json.dumps(data))
            """.trimIndent())
            service.bind(root.toString(), "/usr/bin/python3") { }
            PlatformTestUtil.waitWithEventsDispatching("工作区绑定", { service.snapshot().features.isNotEmpty() }, 10)
            val panel = WorkbenchPanel(project)
            try {
                panel.setActive(true)
                panel.selectFeature("alpha")
                PlatformTestUtil.waitWithEventsDispatching("alpha 详情", { descendants(panel).filterIsInstance<JButton>().any { it.text == "查看文档 (F4)" } }, 10)
                val docButton = descendants(panel).filterIsInstance<JButton>().single { it.text == "查看文档 (F4)" }
                val menu = docButton.componentPopupMenu
                assertNotNull("查看文档应有可选择的次级菜单", menu)
                assertEquals(documents, menu!!.components.filterIsInstance<JMenuItem>().mapNotNull { it.actionCommand })
                assertFalse(Files.readString(root.resolve("requests.log")).contains("verification alpha"))

                val tabs = descendants(panel).filterIsInstance<WorkbenchTabs>().first { it.titleAt(0) == "计划" }
                tabs.selectedIndex = 2
                PlatformTestUtil.waitWithEventsDispatching("alpha 流程与验证", {
                    val controls = descendants(panel)
                    val text = controls.filterIsInstance<JLabel>().map { it.text.orEmpty() }
                    controls.filterIsInstance<JTextArea>().any { it.text.contains("ALPHA RUN ONLY") } && text.any { it.contains("检查通过") } && text.any { it.contains("完整") } && text.any { it.contains("尚未核对") }
                }, 10)
                assertTrue(Files.readString(root.resolve("requests.log")).contains("verification alpha"))
                assertTrue(descendants(panel).filterIsInstance<JButton>().any { it.text == "查看验证摘要" })
                descendants(panel).filterIsInstance<JButton>().single { it.text == "核对当前代码" }.doClick()
                PlatformTestUtil.waitWithEventsDispatching("核对代码请求", { Files.readString(root.resolve("requests.log")).contains("--check-code") }, 10)
                panel.selectFeature("beta")
                assertFalse(descendants(panel).filterIsInstance<JTextArea>().any { it.text.contains("ALPHA RUN ONLY") })
                tabs.selectedIndex = 2
                PlatformTestUtil.waitWithEventsDispatching("beta 无 Run", {
                    val text = descendants(panel).filterIsInstance<JLabel>().map { it.text.orEmpty() }
                    text.any { it.contains("无当前 Run") } && text.any { it.contains("检查通过") }
                }, 10)
                assertFalse(descendants(panel).filterIsInstance<JTextArea>().any { it.text.contains("ALPHA RUN ONLY") })
                assertTrue(Files.readString(root.resolve("requests.log")).contains("--feature beta"))
            } finally { Disposer.dispose(panel) }
        } finally {
            settings.state.featureViews.removeIf { it.root == root.toString() }
            settings.state.preferences.removeIf { it.root == root.toString() }
            root.toFile().deleteRecursively()
        }
    }

    fun testMultiRepositoryComparisonFailureDoesNotShowZeroChanges() {
        val panel = FeatureChangesPanel(project)
        try {
            val feature = JsonParser.parseString("""{"summary":{"repositoryBindings":[{"repository":"first","baseBranch":"main","workBranch":"feature/a"},{"repository":"second","baseBranch":"main","workBranch":"feature/b"}]}}""").asJsonObject
            val repositories = listOf("first", "second").map { id ->
                JsonParser.parseString("""{"id":"$id","absolutePath":"/missing/$id"}""").asJsonObject
            }
            panel.showFeature(feature, repositories)
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

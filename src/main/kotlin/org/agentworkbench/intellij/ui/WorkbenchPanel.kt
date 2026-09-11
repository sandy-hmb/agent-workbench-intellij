package org.agentworkbench.intellij.ui

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import org.agentworkbench.intellij.WorkbenchNotifier
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import org.agentworkbench.intellij.WorkbenchService
import org.agentworkbench.intellij.WorkbenchSettings
import org.agentworkbench.intellij.git.GitScanService
import org.agentworkbench.intellij.git.NativeGit
import org.agentworkbench.intellij.review.FeatureReviewService
import org.agentworkbench.intellij.review.ReviewBinding
import org.agentworkbench.intellij.kit.KitClient
import org.agentworkbench.intellij.kit.HandoffData
import org.agentworkbench.intellij.kit.SearchHit
import java.awt.*
import java.net.URI
import java.nio.file.Path
import javax.swing.*
import javax.swing.table.DefaultTableModel
import org.agentworkbench.intellij.ui.WorkbenchUi as U

internal class WorkbenchPanel(private val project: Project) : JPanel(CardLayout()), Disposable {
    private val service = WorkbenchService.getInstance(project)
    private val settings = WorkbenchSettings.getInstance()
    private val scanService = GitScanService.getInstance(project)
    private val reviewService = FeatureReviewService.getInstance(project)
    private val contentPanel = JPanel(BorderLayout())
    private val emptyPanel = JPanel(GridBagLayout())
    private val sidebar = U.panel(BorderLayout())
    private val nav = U.column()
    private val workspaceName = U.label("研发工作区", 13, bold = true)
    private val workspacePath = U.label("尚未绑定 Kit", 10, U.muted)
    private val activeLabel = U.label("未设置", 11, U.muted)
    private val bottomStatus = U.label("尚未连接工作区", 10, U.muted)
    private val bottomRead = U.label("", 10, U.faint)
    private val editorTitle = U.label("工作区总览", 11)
    private val notice = U.label("", 11, U.amber)
    private val modeNotice = U.label("", 11, U.amber)
    private val pages = U.panel(CardLayout())
    private val overviewBody = U.column()
    private val overviewScrollPane = U.page(U.padded(overviewBody) as JPanel)
    private var lastWorkspaceSummaryFingerprint: String? = null
    private var lastFeatureFilterFingerprint: String? = null
    private val overviewPath = U.label("",11,U.muted)
    private val overviewMetrics = U.panel()
    private val ongoing = U.column()
    private val overviewAttention = U.column()
    private val overviewDoctorBanner = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        background = com.intellij.ui.JBColor(0xF9FAFB, 0x2B2D30)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(com.intellij.ui.JBColor(0xE5E7EB, 0x3C3F41), 1),
            JBUI.Borders.empty(8, 14)
        )
        isVisible = false
    }
    private val featuresPage = U.column()
    private val featureCount = U.label("", 11, U.muted)
    private val featureModel = DefaultListModel<JsonObject>()
    private val featureList = JBList(featureModel)
    private val search = SearchTextField()
    private val lifecycle = JComboBox(arrayOf("全部", "待评审", "planning", "development", "testing", "paused", "done"))
    private val repoFilter = JComboBox(arrayOf("全部仓库"))
    private val sort = JComboBox(arrayOf("最近更新", "名称"))
    private val featureHeader = U.column()
    private val featureOverview = U.column()
    private val featureDashboard = FeatureDashboardPanel(
        project = project,
        onOpenTask = { locateTask(it) },
        onOpenDoc = { openDocument(it) }
    )
    private val featureReviewPanel = FeatureReviewPanel(
        project = project,
        onRefresh = { refreshReviews(force = true) },
        onConfigure = { com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, WorkbenchConfigurable::class.java) },
        onChooseRemote = { repository, remote -> reviewService.chooseRemote(repository, remote) },
        onChooseCandidate = { repository, candidate -> reviewService.chooseCandidate(repository, candidate) },
    )
    private val featureChangesTabs = WorkbenchTabs()
    private val featureDoctorLabel = JLabel("环境检查中…", AllIcons.General.Information, JLabel.LEFT).apply {
        font = font.deriveFont(11f)
        foreground = U.muted
    }
    private val featureDoctorStrip = U.row(
        featureDoctorLabel,
        U.button("查看详情", true) { navigate("diagnostics") }
    ).apply { border = JBUI.Borders.empty(2, 0, 4, 0) }
    private val tabs = WorkbenchTabs()
    private val planTitle = U.label("计划", 14, bold = true)
    private val taskTable = table("编号", "完成", "任务", "依赖", "原文位置")
    private val taskFilter = JComboBox(arrayOf("全部任务", "待完成", "已完成", "可信已完成", "缺凭据/待核验"))
    private val reader = DocumentReader(project, ::openLink)
    private val docPanel = DocumentTreePanel(
        project = project,
        reader = reader,
        onDocumentSelected = { path ->
            if (!changing) {
                selectedDocument = path
                openDocument(path)
            }
        },
        onOpenInEditor = { path ->
            selectedSlug?.let { slug ->
                val line = reader.currentLine()
                WorkbenchNavigation.openFeatureInEditor(project, slug, path, line)
            }
        }
    )
    private val documentStatus: JLabel
        get() = docPanel.documentStatus
    private val verificationBody = U.column()
    private val workflowBody = U.column()
    private val globalRuns = U.column()
    private val globalExtensions = U.column()
    private val historySearch = SearchTextField()
    private val historyRepository = JComboBox(arrayOf("全部仓库"))
    private val historyStatus = JComboBox(arrayOf("全部状态", "planning", "development", "testing", "paused", "done"))
    private val historyModel = DefaultListModel<SearchHit>()
    private val historyResults = JBList(historyModel)
    private val historyCount = U.label("输入关键词后搜索当前工作区的历史文档", 11, U.muted)
    private val runPicker = JComboBox<String>()
    private val runStatus = U.label("", 11, U.muted)
    private val configuration = U.column()
    private val diagnosticsBody = U.column()
    private val doctorBody = U.column()
    private val git = GitPanel(project)
    private val repositoryGit = GitPanel(project)
    private val workingGit = GitPanel(project)
    private val committedChanges = FeatureChangesPanel(project)
    private var state = service.snapshot()
    private var selectedSlug: String? = null
    private var selectedDocument: String? = null
    private var files = emptyList<JsonObject>()
    private var tasks = emptyList<JsonObject>()
    private var displayedTasks = emptyList<JsonObject>()
    private var detailData: JsonObject? = null
    private var verificationData: JsonObject? = null
    private var workflowData: JsonObject? = null
    private var runData: JsonObject? = null
    private var scenes = emptyMap<String, NativeGit.Snapshot>()
    private var changing = false
    private var disposed = false
    private var active = false
    private var route = "overview"
    private var lastWorkspaceRevision: String? = null
    private var documentRevision: String? = null
    private var checkedFeatureRevision: String? = null
    private var checkingCode = false
    private var snapshotDirty = false
    private var doctorRan = false
    private var runningDoctor = false
    private var businessReposExpanded = false
    private var describeLoaded = false
    private var describeSummaries = emptyMap<String, String>()
    private var lastLocalReqKey: String? = null
    private var lastReviewKey: String? = null
    private val pendingKit = mutableSetOf<String>()
    // VFS 监听是主要刷新信号；定时器只作兜底（VFS 漏报、外部工具直写等），30 秒全量轮询会反复起 Python 进程。
    private val refreshTimer = Timer(300_000) { reload() }
    private val debounce = Timer(500) { if (active) scanService.refresh(repositories()) }.apply { isRepeats = false }
    private val kitDebounce = Timer(500) { applyKitChanges() }.apply { isRepeats = false }

    init {
        getAccessibleContext().accessibleName = "Agent Workbench 工作台"
        background = U.bg
        sidebar.background = U.surface
        sidebar.minimumSize = Dimension(JBUI.scale(160), 1)
        val workspace = U.row(U.column(5, workspaceName, workspacePath), JBLabel(AllIcons.General.ChevronDown)).apply { background = U.surface; border = JBUI.Borders.empty(22, 18, 22, 14) }
        sidebar.add(workspace, BorderLayout.NORTH)
        sidebar.add(U.scroll(nav).apply { viewport.background = U.surface })
        sidebar.add(U.column(12, navButton("配置", "工作区配置", AllIcons.General.Settings) { navigate("configuration") }, U.line(), U.label("工作流当前需求", 10, U.faint), activeLabel).apply { border = JBUI.Borders.empty(12, 18); background = U.surface }, BorderLayout.SOUTH)

        val workarea = U.panel().apply {
            minimumSize = Dimension(JBUI.scale(320), 1)
        }
        lateinit var mainSplitter: OnePixelSplitter
        val toggleSidebarBtn = U.button("折叠侧栏", true) {
            sidebar.isVisible = !sidebar.isVisible
            mainSplitter.revalidate()
            mainSplitter.repaint()
        }.apply {
            icon = AllIcons.General.InlineVariables
            toolTipText = "显示/隐藏工作台左侧导航栏，获取全屏沉浸阅读空间"
        }
        val editorBar = U.row(
            U.flow(
                U.button("工作区总览", true) { navigate("overview") }.apply { icon = AllIcons.Nodes.HomeFolder; iconTextGap = JBUI.scale(6) },
                editorTitle
            ),
            toggleSidebarBtn
        ).apply {
            background = U.surface; border = BorderFactory.createCompoundBorder(BottomLine(U.border), JBUI.Borders.empty(7, 16)); preferredSize = Dimension(1, JBUI.scale(43))
        }
        workarea.add(U.column(0, editorBar, notice.apply { border = JBUI.Borders.empty(7,28); isVisible = false }, modeNotice.apply { border = JBUI.Borders.empty(7,28); isVisible = false }), BorderLayout.NORTH)
        workarea.add(pages)

        mainSplitter = OnePixelSplitter(false, "org.agentworkbench.intellij.workbenchProportion", 0.22f).apply {
            firstComponent = sidebar
            secondComponent = workarea
            setHonorComponentsMinimumSize(true)
            dividerWidth = JBUI.scale(1)
        }
        contentPanel.add(mainSplitter, BorderLayout.CENTER)
        contentPanel.add(U.row(bottomStatus, bottomRead).apply { background = U.surface; border = JBUI.Borders.empty(6,12); preferredSize = Dimension(1,JBUI.scale(28)) }, BorderLayout.SOUTH)

        // ===== Empty Setup State UI =====
        val emptyMessage = U.label("当前项目未检测到 Agent Workbench 配置", 14, U.muted)
        val setupButton = U.button("配置 Kit 工作流根目录", true) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, WorkbenchConfigurable::class.java)
        }.apply { icon = AllIcons.General.Settings }
        emptyPanel.background = U.bg
        emptyPanel.add(U.column(16, emptyMessage, setupButton))
        
        add(emptyPanel, "EMPTY")
        add(contentPanel, "CONTENT")

        U.append(overviewBody, U.column(10,U.row(U.label("工作区总览",23,bold=true),U.flow(U.button("刷新", action=::reload).apply { icon = AllIcons.Actions.Refresh },U.button("Fetch 全部") { git.fetchVisible() }.apply { icon = AllIcons.Actions.Download })),overviewPath))
        U.append(overviewBody, overviewDoctorBanner, 16)
        U.append(overviewBody, overviewMetrics, 26)
        git.preferredSize = Dimension(1, JBUI.scale(405))
        U.append(overviewBody, git, 20)
        U.append(overviewBody, twoColumns(ongoing, overviewAttention, 300), 28)
        pages.add(overviewScrollPane, "overview")

        U.append(featuresPage, pageHeader("Feature 工作台", "浏览全部需求与历史记录"))
        lifecycle.renderer = object : DefaultListCellRenderer() { override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component = super.getListCellRendererComponent(list, if(value == "全部") value else U.status(value?.toString()), index, selected, focus) }
        val tools = U.row(search, U.flow(lifecycle, repoFilter, sort)).apply { border = JBUI.Borders.empty(24,0,16,0) }
        search.textEditor.emptyText.text = "搜索需求名称或标识..."
        search.textEditor.accessibleContext.accessibleName = "搜索需求"
        search.preferredSize = Dimension(JBUI.scale(220), JBUI.scale(32))
        listOf(lifecycle,repoFilter,sort,taskFilter,runPicker).forEach(U::combo)
        U.append(featuresPage, tools); U.append(featuresPage, featureCount)
        featureList.background = U.bg
        featureList.selectionBackground = U.selection
        featureList.setEmptyText("没有符合条件的需求")
        featureList.cellRenderer = ListCellRenderer<JsonObject> { _, value, _, selected, _ ->
            val plan = value.get("planSummary").obj(); val done = plan?.str("completed")?.toIntOrNull() ?: 0; val total = plan?.str("total")?.toIntOrNull() ?: 0
            val trustedObj = plan?.get("trustedProgress").obj()
            val isTrustedApplicable = trustedObj?.get("applicable")?.asBoolean == true
            val trustedDone = if (isTrustedApplicable) trustedObj?.get("completed")?.takeIf { it.isJsonPrimitive }?.asInt ?: done else null
            val untrustedCount = if (isTrustedApplicable && trustedDone != null && done > trustedDone) done - trustedDone else 0

            val left = U.column(6, U.flow(*listOfNotNull(U.label(value.str("title") ?: value.str("slug").orEmpty(), 12, U.text, bold = true), U.badge(U.status(value.str("status"))), verificationBadge(value)).toTypedArray()), U.mono("${value.str("slug")}  ·  ${value.objects("repositoryBindings").size} 个仓库  ·  ${value.str("lastUpdated")}"))
            val progressLabel = U.label(
                U.planProgress(done, total, trustedCompleted = if (isTrustedApplicable) trustedDone else null),
                11,
                if (untrustedCount > 0) U.amber else U.muted
            ).apply {
                if (untrustedCount > 0) toolTipText = "已打勾 $done 项，但其中 $untrustedCount 项缺乏有效测试/执行凭据"
            }
            val progress = if (total > 0) U.column(10, progressLabel, U.progress(done, total)) else U.label("暂无计划", 11, U.faint)
            U.row(left, progress).apply {
                border = BorderFactory.createCompoundBorder(BottomLine(U.border), JBUI.Borders.empty(14, 8))
                if (selected) paintBackground(this, U.selection)
            }
        }
        com.intellij.ui.ListSpeedSearch.installOn(featureList) { it.str("title") ?: it.str("slug").orEmpty() }
        com.intellij.ui.TableSpeedSearch.installOn(taskTable)
        featureList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { if (e.clickCount == 2) openSelectedFeature() }
        })
        featureList.registerKeyboardAction({ openSelectedFeature() }, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        featureList.registerKeyboardAction({
            featureList.selectedValue?.let { f ->
                val slug = f.str("slug") ?: return@let
                WorkbenchNavigation.openFeatureInEditor(project, slug)
            }
        }, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F4, 0), JComponent.WHEN_FOCUSED)
        featureList.addMouseListener(object : com.intellij.ui.PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val index = featureList.locationToIndex(Point(x, y))
                if (index >= 0) {
                    featureList.selectedIndex = index
                    val item = featureList.selectedValue ?: return
                    val slug = item.str("slug") ?: return
                    val menu = JPopupMenu()
                    menu.add(JMenuItem("打开需求面板").apply { addActionListener { openSelectedFeature() } })
                    menu.add(JMenuItem("在编辑器中打开 (F4)").apply {
                        icon = AllIcons.General.OpenInToolWindow
                        addActionListener { WorkbenchNavigation.openFeatureInEditor(project, slug) }
                    })
                    menu.add(JMenuItem("在项目视图中定位").apply {
                        icon = AllIcons.General.Locate
                        addActionListener { WorkbenchNavigation.locateFeatureInProjectView(project, slug) }
                    })
                    menu.addSeparator()
                    menu.add(JMenuItem("复制需求 Slug ($slug)").apply {
                        addActionListener { com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(slug)) }
                    })
                    menu.show(comp, x, y)
                }
            }
        })
        U.append(featuresPage, featureList, 16)
        pages.add(U.page(U.padded(featuresPage) as JPanel), "features")

        tabs.addTab("概览", featureDashboard)
        val locateTaskBtn = U.button("定位所选任务原文", action = ::locateTask).apply { toolTipText = "在内置阅读器中查看任务定义" }
        val openTaskInEditorBtn = U.button("在编辑器中打开任务", true) {
            val task = taskSelected() ?: return@button
            locateTaskInEditor(task)
        }.apply { icon = AllIcons.General.OpenInToolWindow; toolTipText = "在主编辑器中打开 implementation.md 并跳转到该任务定义行" }
        val planPanel = U.panel().apply {
            add(U.row(planTitle, U.flow(taskFilter, locateTaskBtn, openTaskInEditorBtn)).apply { border = JBUI.Borders.empty(24,0,16,0) }, BorderLayout.NORTH)
            add(U.scroll(taskTable))
        }
        featureChangesTabs.addTab("代码评审", U.page(U.padded(featureReviewPanel) as JPanel))
        featureChangesTabs.addTab("需求分支已提交", committedChanges)
        featureChangesTabs.addTab("当前工作目录", workingGit)

        tabs.addTab("文档", docPanel)
        tabs.addTab("计划", planPanel)
        tabs.addTab("变更", featureChangesTabs)
        tabs.addTab("验证", U.page(verificationBody))
        tabs.addTab("流程", U.panel().apply { add(U.row(U.flow(U.label("运行记录",11,U.muted), runPicker), runStatus).apply { border = JBUI.Borders.empty(22,0,20,0) }, BorderLayout.NORTH); add(U.page(workflowBody)) })

        val featureNorth = U.column(4, featureHeader, featureDoctorStrip)
        pages.add(U.padded(U.panel().apply { add(featureNorth, BorderLayout.NORTH); add(tabs) }), "feature")
        pages.add(U.page(U.padded(globalRuns) as JPanel), "runs")
        pages.add(U.page(U.padded(globalExtensions) as JPanel), "extensions")
        historySearch.textEditor.emptyText.text = "搜索需求、设计、计划或验证记录..."
        historySearch.preferredSize = Dimension(JBUI.scale(300), JBUI.scale(32))
        listOf(historyRepository, historyStatus).forEach(U::combo)
        historyResults.background = U.bg
        historyResults.selectionBackground = U.selection
        historyResults.setEmptyText("没有匹配的历史文档")
        historyResults.cellRenderer = ListCellRenderer<SearchHit> { _, value, _, selected, _ ->
            U.column(
                6,
                U.flow(U.label(value.title, 12, bold = true), U.badge(U.status(value.status))),
                U.mono("${value.slug} · ${value.path}:${value.line}"),
                U.copy(value.snippet),
            ).apply {
                border = BorderFactory.createCompoundBorder(BottomLine(U.border), JBUI.Borders.empty(12, 8))
                if (selected) paintBackground(this, U.selection)
            }
        }
        val historyPage = U.column()
        U.append(historyPage, pageHeader("历史检索", "搜索当前工作区的 Feature 文档"))
        U.append(historyPage, U.row(historySearch, U.flow(historyRepository, historyStatus, U.button("搜索") { searchHistory() }.apply { icon = AllIcons.Actions.Find })), 20)
        U.append(historyPage, historyCount, 6)
        U.append(historyPage, U.scroll(historyResults).apply { preferredSize = Dimension(1, JBUI.scale(520)) }, 12)
        pages.add(U.page(U.padded(historyPage) as JPanel), "search")
        pages.add(U.padded(U.panel().apply { add(pageHeader("业务仓库", "查看现场 · 使用宿主 Git 操作", U.button("刷新") { reload() }.apply { icon = AllIcons.Actions.Refresh }), BorderLayout.NORTH); add(repositoryGit) }), "repositories")
        pages.add(U.page(U.padded(configuration) as JPanel), "configuration")
        val diagnosticsPage = U.column()
        U.append(diagnosticsPage, pageHeader("诊断与健康", "Inspect 读取诊断与 kit doctor 检查", U.button("重新检查") { runDoctor(force = true) }.apply { icon = AllIcons.Actions.Refresh }))
        U.append(diagnosticsPage, U.section("读取诊断"), 24)
        U.append(diagnosticsPage, diagnosticsBody, 4)
        U.append(diagnosticsPage, U.section("工作区健康（doctor）"), 28)
        U.append(diagnosticsPage, doctorBody, 4)
        pages.add(U.page(U.padded(diagnosticsPage) as JPanel), "diagnostics")
        tabs.onChange = { if (!changing) {
            if (tabs.selectedIndex != CHANGES) { lastReviewKey=null; reviewService.invalidate() }
            remember(); loadVisible()
        } }
        featureChangesTabs.onChange = { if (!changing) {
            if (featureChangesTabs.selectedIndex != REVIEW_CHANGES) { lastReviewKey=null; reviewService.invalidate() }
            loadVisible()
        } }
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) { filterFeatures(); remember() }
        })
        lifecycle.addActionListener { filterFeatures(); remember() }; repoFilter.addActionListener { filterFeatures() }; sort.addActionListener { filterFeatures() }
        taskFilter.addActionListener { renderTasks() }
        taskTable.addMouseListener(object : java.awt.event.MouseAdapter() { override fun mouseClicked(e: java.awt.event.MouseEvent) { if(e.clickCount == 2) locateTask() } })
        taskTable.registerKeyboardAction({ locateTask() }, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        taskTable.registerKeyboardAction({
            taskSelected()?.let(::locateTaskInEditor)
        }, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F4, 0), JComponent.WHEN_FOCUSED)
        taskTable.addMouseListener(object : com.intellij.ui.PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val row = taskTable.rowAtPoint(Point(x, y))
                if (row >= 0) {
                    taskTable.setRowSelectionInterval(row, row)
                    val task = taskSelected() ?: return
                    val menu = JPopupMenu()
                    menu.add(JMenuItem("在内置阅读器中定位").apply { addActionListener { locateTask(task) } })
                    menu.add(JMenuItem("在编辑器中打开 (F4)").apply {
                        icon = AllIcons.General.OpenInToolWindow
                        addActionListener { locateTaskInEditor(task) }
                    })
                    menu.addSeparator()
                    val taskId = task.str("id") ?: task.str("title").orEmpty()
                    menu.add(JMenuItem("复制任务标识 ($taskId)").apply {
                        addActionListener { com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(taskId)) }
                    })
                    menu.show(comp, x, y)
                }
            }
        })
        runPicker.addActionListener { if (!changing) { loadRun(); remember() } }
        historySearch.textEditor.addActionListener { searchHistory() }
        historyResults.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount == 2) historyResults.selectedValue?.let(::openSearchHit)
            }
        })
        historyResults.addMouseListener(object : com.intellij.ui.PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val index = historyResults.locationToIndex(Point(x, y))
                if (index < 0) return
                historyResults.selectedIndex = index
                val hit = historyResults.selectedValue ?: return
                JPopupMenu().apply {
                    add(JMenuItem("打开原文").apply { addActionListener { openSearchHit(hit) } })
                    add(JMenuItem("复制出处").apply { addActionListener { copySearchHit(hit) } })
                }.show(comp, x, y)
            }
        })
        reader.onPosition = { line -> state.kitRoot?.let { root -> selectedDocument?.let { path -> documentRevision?.let { revision -> settings.rememberPosition(root, "$selectedSlug:$path:$revision", line) } } } }
        listOf(reader, committedChanges, git, repositoryGit, workingGit).forEach { Disposer.register(this, it) }
        scanService.subscribe(this) { scans, scanning ->
            if (disposed) return@subscribe
            scenes = scans.mapNotNull { s -> s.scene?.let { s.id to it } }.toMap()
            renderWorkspaceSummary(); renderSidebar()
            if (!scanning) detailData?.let(::renderFeatureOverview)
        }
        service.subscribe(this) {
            val next = service.snapshot()
            if (active) {
                refresh()
            } else {
                snapshotDirty = true
                // 如果当前未激活但检测到了有效的 kitRoot，且界面还在 EMPTY 状态，立即拉起 refresh 切换界面
                if (!next.kitRoot.isNullOrBlank() && state.kitRoot.isNullOrBlank()) {
                    refresh()
                }
            }
        }
        reviewService.subscribe(this) { rows -> if (!disposed) {
            if (rows.isEmpty()) lastReviewKey=null
            featureReviewPanel.render(rows)
        } }
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val roots = repositories().mapNotNull { it.str("absolutePath") }
                if (events.any { e -> roots.any { e.path.startsWith("$it/") } }) invalidateCode()
                classifyKitChanges(events)
            }
        })
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repo -> if(repositories().any { it.str("absolutePath") == repo.root.path }) invalidateCode() })
        restore(); refresh(); navigate("overview")
    }

    private fun invalidateCode() { ApplicationManager.getApplication().invokeLater { if (!disposed) { checkedFeatureRevision = null; verificationData = null; if(active) { renderVerification(); debounce.restart() } } } }
    fun setActive(value: Boolean) {
        active = value
        if(value && !disposed) {
            if (snapshotDirty) snapshotDirty = false
            refresh(); refreshTimer.start()
            if (pendingKit.isNotEmpty()) applyKitChanges()
            if(route=="feature") restoreFeature(); loadVisible()
        }
        else { refreshTimer.stop(); debounce.stop(); kitDebounce.stop() }
    }
    fun navigate(page: String) {
        route = page; (pages.layout as CardLayout).show(pages, page)
        editorTitle.text = when(page) { "feature" -> detailData?.get("summary").obj()?.str("title") ?: "Feature 工作台"; "features" -> "Feature 工作台"; "search" -> "历史检索"; "runs" -> "流程记录"; "extensions" -> "扩展"; "configuration" -> "工作区配置"; "repositories" -> "业务仓库"; "diagnostics" -> "读取诊断"; else -> "" }
        renderSidebar(); loadVisible()
    }
    fun refresh() {
        if(disposed) return
        val next = service.snapshot(); val changedRoot = state.kitRoot != next.kitRoot; state = next
        (layout as? CardLayout)?.show(this, if (state.kitRoot.isNullOrBlank()) "EMPTY" else "CONTENT")
        if(changedRoot) { selectedSlug=null; detailData=null; verificationData=null; lastWorkspaceRevision=null; scenes=emptyMap(); doctorRan=false; businessReposExpanded=false; describeLoaded=false; describeSummaries=emptyMap(); lastLocalReqKey=null;lastReviewKey=null;reviewService.invalidate(); pendingKit.clear(); historyModel.clear(); historyCount.text="输入关键词后搜索当前工作区的历史文档"; restore() }
        overviewPath.text=state.kitRoot ?: "在侧栏入口绑定工作流 Kit"
        workspaceName.text = state.workspace?.data.obj()?.get("identity").obj()?.str("name") ?: "研发工作区"
        workspacePath.text = state.kitRoot?.let { Path.of(it).fileName.toString() } ?: "尚未绑定 Kit"
        activeLabel.text = state.workspace?.data.obj()?.get("localContext").obj()?.str("activeFeature") ?: "未设置"
        val mode = state.workspace?.data.obj()?.str("mode")
        modeNotice.isVisible = mode == "maintenance"
        if (modeNotice.isVisible) modeNotice.text = "维护模式：该 Kit 尚未初始化本地工作区（缺少 .workspace 登记）。工作台仅显示 Kit 自身信息；请先在终端用 workspace-init 初始化，再重新绑定。"
        bottomStatus.text = if(state.workspace == null) "○ 尚未连接工作区" else if(mode == "maintenance") "◉ 已连接 Kit（维护模式）" else "◉ 工作区已连接   ${activeLabel.text}"
        bottomStatus.foreground = if(state.workspace == null) U.muted else U.green
        bottomRead.text = "${repositories().size} 个独立仓库  ·  读取 ${relativeTime(state.workspace?.observedAt)}"
        notice.isVisible = state.error != null; notice.text = "读取失败：${state.error}" + if(state.workspace!=null) "，保留上次成功内容" else "，尚未读取到工作区"
        renderDiagnostics()
        if (lastWorkspaceRevision != state.workspace?.revision) {
            lastWorkspaceRevision = state.workspace?.revision
            scanService.refresh(repositories())
            changing=true; val selected=repoFilter.selectedItem; repoFilter.removeAllItems(); repoFilter.addItem("全部仓库"); repositories().forEach { repoFilter.addItem(it.str("id")) }; repoFilter.selectedItem=selected ?: "全部仓库"; changing=false
            changing=true; val historySelected=historyRepository.selectedItem; historyRepository.removeAllItems(); historyRepository.addItem("全部仓库"); repositories().filter { it.str("role") == if(mode == "maintenance") "kit" else "business" }.forEach { historyRepository.addItem(it.str("id")) }; historyRepository.selectedItem=historySelected?.takeIf { value -> (0 until historyRepository.itemCount).any { historyRepository.getItemAt(it) == value } } ?: "全部仓库"; changing=false
        }
        renderSidebar(); renderWorkspaceSummary(); filterFeatures(); renderConfiguration()
        if (state.kitRoot != null && !doctorRan && !runningDoctor) {
            runDoctor()
        }
    }
    fun isGitSnapshotReady() = scenes.size == repositories().size && scenes.isNotEmpty()
    private fun repositories() = state.workspace?.data.obj()?.objects("repositories").orEmpty()
    private fun renderSidebar() {
        nav.removeAll(); nav.background=U.surface; nav.border=JBUI.Borders.empty(0,9)
        U.append(nav, navButton("overview", "工作区总览", AllIcons.Nodes.HomeFolder) { navigate("overview") })
        U.append(nav, navButton("features", "Feature 工作台", AllIcons.Actions.ListFiles, state.features.size.toString()) { navigate("features") },2)
        U.append(nav, navButton("search", "历史检索", AllIcons.Actions.Find) { navigate("search") },2)
        U.append(nav, navButton("runs", "流程记录", AllIcons.Vcs.History) { navigate("runs") },2)
        U.append(nav, navButton("extensions", "扩展", AllIcons.Nodes.Plugin, workflowData?.objects("extensions")?.size?.toString().orEmpty()) { navigate("extensions") },2)
        for((role,title) in listOf("business" to "业务仓库", "kit" to "工作流仓库")) {
            val roleRepos = repositories().filter { it.str("role")==role }
            val collapsible = role == "business" && roleRepos.size > 5
            val groupAction = if (collapsible) U.button(if (businessReposExpanded) "收起" else "全部 ${roleRepos.size}", true) {
                businessReposExpanded = !businessReposExpanded
                renderSidebar()
            }.apply {
                icon = if (businessReposExpanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
                horizontalTextPosition = SwingConstants.LEFT
                name = "nav-repository-group-business"
            } else U.label(roleRepos.size.toString(),10,U.faint)
            U.append(nav, U.row(U.label(title,10,U.faint),groupAction).apply { background=U.surface; border=JBUI.Borders.empty(24,11,10,11) })
            val visibleRepos = if (collapsible && !businessReposExpanded) roleRepos.take(5) else roleRepos
            visibleRepos.forEach { repo ->
                val name=repo.str("id").orEmpty(); val scene=scenes[name] as? NativeGit.Snapshot.Available
                U.append(nav, navButton("repo/$name",name,if(role=="kit") AllIcons.Vcs.Branch else AllIcons.Nodes.Module,scene?.changes?.takeIf { it>0 }?.toString().orEmpty()) { navigate("repositories"); repositoryGit.focusRepository(name) },2)
            }
        }
        nav.revalidate(); nav.repaint()
    }
    private fun navButton(key:String,value:String,icon:javax.swing.Icon,count:String="",action:()->Unit): JButton = U.button("$value${if(count.isBlank()) "" else "   $count"}",true,action).apply {
        this.icon=icon; iconTextGap=JBUI.scale(8)
        horizontalAlignment=SwingConstants.LEFT; foreground=if(route==key || (key=="features"&&route=="feature")) U.accent else U.muted
        background=if(foreground==U.accent) U.selection else U.surface; isOpaque=true; isContentAreaFilled=true; border=JBUI.Borders.empty(9,11)
        preferredSize=Dimension(JBUI.scale(204),JBUI.scale(36)); maximumSize=Dimension(Int.MAX_VALUE,JBUI.scale(36)); toolTipText=value; name="nav-$key"
    }
    private fun renderWorkspaceSummary() {
        val values = scenes.values.filterIsInstance<NativeGit.Snapshot.Available>()
        val pending = state.features.count { it.str("status") !in listOf("done","paused") }
        val repoIssues = repoAttention()
        val featIssues = featureAttention()
        val allAttention = attention()

        val summaryFingerprint = buildString {
            append(repositories().size).append(';')
            append(pending).append(';')
            append(values.sumOf { it.changes }).append(';')
            append(allAttention.joinToString(",")).append(';')
            state.features.filter { it.str("status") != "done" }.take(5).forEach {
                append(it.str("slug")).append(':').append(it.str("status")).append(':').append(it.get("planSummary").obj()?.str("completed")).append(';')
            }
        }
        if (summaryFingerprint == lastWorkspaceSummaryFingerprint && overviewMetrics.componentCount > 0) {
            return
        }
        lastWorkspaceSummaryFingerprint = summaryFingerprint

        val savedPos = overviewScrollPane.viewport.viewPosition

        val attentionSub = when {
            repoIssues.isNotEmpty() && featIssues.isNotEmpty() -> "${repoIssues.size} 仓 / ${featIssues.size} 需求 ↗"
            repoIssues.isNotEmpty() -> "${repoIssues.size} 个仓库异常 ↗"
            featIssues.isNotEmpty() -> "${featIssues.size} 个需求待评审 ↗"
            allAttention.isNotEmpty() -> "${allAttention.size} 项需关注 ↗"
            else -> "暂无已知阻塞 ↗"
        }
        val attentionTip = if (allAttention.isNotEmpty()) {
            "<html><b>当前需要关注 (${allAttention.size} 项):</b><br/>" +
                allAttention.joinToString("<br/>") { "• $it" } + "</html>"
        } else null

        overviewMetrics.removeAll(); overviewMetrics.add(U.metrics(
            clickableMetric("Git 仓库", repositories().size.toString(), "${repositories().count { it.str("role")=="business" }} 个业务仓 ↗") { navigate("repositories") },
            clickableMetric("进行中的 Feature", pending.toString(), "查看需求列表 ↗") { navigate("features") },
            clickableMetric("未提交文件", if (scenes.isEmpty()) "—" else values.sumOf { it.changes }.toString(), "筛选有变更仓库 ↗") { git.filter("changed") },
            clickableMetric("需要关注", allAttention.size.toString(), attentionSub, if (allAttention.isEmpty()) U.text else U.amber, tooltip = attentionTip) {
                if (featIssues.isNotEmpty()) {
                    changing = true
                    lifecycle.selectedItem = "待评审"
                    changing = false
                    navigate("features")
                    filterFeatures()
                    if (featureModel.size > 0) {
                        featureList.selectedIndex = 0
                    }
                } else if (repoIssues.isNotEmpty()) {
                    git.filter("attention")
                }
            }
        ))
        ongoing.removeAll(); U.append(ongoing, U.section("进行中的 Feature", U.button("查看全部  →", true) { navigate("features") }))
        state.features.filter { it.str("status") != "done" }.take(5).forEach { U.append(ongoing, featureRow(it)) }
        if (state.features.isEmpty()) U.append(ongoing, U.empty("暂无需求记录", "需求由你的 Agent 或其他工具推进，工作台只负责查看。"))
        renderAttention(overviewAttention, allAttention)
        overviewBody.revalidate()
        overviewBody.repaint()
        SwingUtilities.invokeLater {
            overviewScrollPane.viewport.viewPosition = savedPos
        }
    }
    private fun repoAttention(): List<String> = repositories().mapNotNull { repo ->
        val id = repo.str("id").orEmpty(); val scene = scenes[id]
        when {
            repo.str("availability") != "present" -> "$id · 仓库目录不可用"
            scene is NativeGit.Snapshot.Unavailable -> "$id · 无法读取 Git 现场"
            scene is NativeGit.Snapshot.Available && scene.conflicts -> "$id · 有未处理冲突"
            else -> null
        }
    }
    private fun featureAttention(): List<String> = state.features.filter {
        it.str("status") != "done" && KitSemantics.reviewPending(it.get("documentReviews").obj()?.str("plan"))
    }.map { "${it.str("title")} · 计划${it.get("documentReviews").obj()?.str("plan")}" }

    private fun attention(): List<String> = repoAttention() + featureAttention() + listOfNotNull(state.error)
    private fun renderAttention(target:JPanel, items:List<String>) { target.removeAll(); U.append(target,U.section("当前关注")); if(items.isEmpty()) U.append(target,U.copy("暂无已知阻塞。验证的当前代码适用性需单独核对。")) else items.forEach { U.append(target,U.copy(it),10) }; target.revalidate(); target.repaint() }
    private fun filterFeatures() {
        if(changing) return
        val query=search.text.trim()
        val statusMatch: (JsonObject) -> Boolean = { feat ->
            when (lifecycle.selectedItem) {
                "全部" -> true
                "待评审" -> KitSemantics.reviewPending(feat.get("documentReviews").obj()?.str("plan"))
                else -> feat.str("status") == lifecycle.selectedItem
            }
        }
        var rows=state.features.filter { statusMatch(it) && (query.isBlank() || "${it.str("slug")} ${it.str("title")}".contains(query,true)) && (repoFilter.selectedItem=="全部仓库" || it.objects("repositoryBindings").any { b -> b.str("repository")==repoFilter.selectedItem }) }
        rows=if(sort.selectedIndex==0) rows.sortedByDescending { it.str("lastUpdated") } else rows.sortedBy { it.str("title") }

        val currentFingerprint = "${lifecycle.selectedItem}:${repoFilter.selectedItem}:${sort.selectedIndex}:$query;" +
            rows.joinToString(";") { "${it.str("slug")}:${it.str("status")}:${it.str("lastUpdated")}" }
        if (currentFingerprint == lastFeatureFilterFingerprint && featureModel.size == rows.size) {
            return
        }
        lastFeatureFilterFingerprint = currentFingerprint

        val selectedSlug = featureList.selectedValue?.str("slug")
        val scrollViewport = featureList.parent as? JViewport
        val savedScroll = scrollViewport?.viewPosition

        featureModel.clear(); rows.forEach(featureModel::addElement)
        if (selectedSlug != null) {
            val idx = (0 until featureModel.size).indexOfFirst { featureModel.getElementAt(it).str("slug") == selectedSlug }
            if (idx >= 0) featureList.selectedIndex = idx
        }
        if (savedScroll != null) {
            SwingUtilities.invokeLater { scrollViewport?.viewPosition = savedScroll }
        }
        featureCount.text="${rows.size} 个需求 · 已加载 ${state.features.size} 个"
        featureList.revalidate(); featureList.repaint()
    }
    private fun searchHistory() {
        val query = historySearch.text.trim()
        if (query.isBlank()) {
            historyModel.clear()
            historyCount.text = "输入关键词后搜索当前工作区的历史文档"
            return
        }
        if (!service.supports("search")) {
            historyCount.text = "当前 Kit 不支持历史检索，请升级后重试"
            return
        }
        val repository = historyRepository.selectedItem?.toString()?.takeUnless { it == "全部仓库" }
        val status = historyStatus.selectedItem?.toString()?.takeUnless { it == "全部状态" }
        historyCount.text = "正在搜索…"
        service.searchHistory(query, repository, status) { result ->
            if (disposed || historySearch.text.trim() != query) return@searchHistory
            val data = result.search
            if (result.error != null || data?.query != query) {
                historyCount.text = "搜索失败：${result.error ?: "结果不可用"}"
                return@searchHistory
            }
            historyModel.clear()
            data.items.forEach(historyModel::addElement)
            historyCount.text = buildString {
                append("${data.total} 条结果")
                if (data.hasMore) append(" · 仅显示前 ${data.items.size} 条")
                if (data.incomplete) append(" · 部分文档无法读取，结果不完整")
            }
        }
    }
    private fun openSearchHit(hit: SearchHit) {
        if (!WorkbenchNavigation.openFeatureInEditor(project, hit.slug, hit.path, hit.line)) {
            WorkbenchNotifier.warn(project, "无法打开搜索结果", "${hit.slug}/${hit.path}:${hit.line}")
        }
    }
    private fun copySearchHit(hit: SearchHit) {
        val source = "${hit.slug}/${hit.path}:${hit.line}\n${hit.snippet}"
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(source), null)
    }
    private fun openSelectedFeature() { featureList.selectedValue?.str("slug")?.let(::selectFeature) }
    private fun paintBackground(component: Component, color: Color) {
        component.background = color
        if (component is Container) component.components.forEach { paintBackground(it, color) }
    }
    private fun featureRow(item:JsonObject): JPanel {
        val plan=item.get("planSummary").obj();val done=plan?.str("completed")?.toIntOrNull()?:0;val total=plan?.str("total")?.toIntOrNull()?:0
        val trustedObj = plan?.get("trustedProgress").obj()
        val isTrustedApplicable = trustedObj?.get("applicable")?.asBoolean == true
        val trustedDone = if (isTrustedApplicable) trustedObj?.get("completed")?.takeIf { it.isJsonPrimitive }?.asInt ?: done else null
        val untrustedCount = if (isTrustedApplicable && trustedDone != null && done > trustedDone) done - trustedDone else 0

        val title=U.flow(*listOfNotNull(U.button(item.str("title")?:item.str("slug").orEmpty(),true) { selectFeature(item.str("slug")!!) }.apply { foreground=U.text;font=U.label("",12,bold=true).font;border=JBUI.Borders.empty(3,0) }, U.badge(U.status(item.str("status"))), verificationBadge(item)).toTypedArray())
        val left=U.column(8,title,U.mono("${item.str("slug")}  ·  ${item.objects("repositoryBindings").size} 个仓库  ·  ${item.str("lastUpdated")}"))
        val progressLabel = U.label(
            U.planProgress(done, total, trustedCompleted = if (isTrustedApplicable) trustedDone else null),
            11,
            if (untrustedCount > 0) U.amber else U.muted
        ).apply {
            if (untrustedCount > 0) toolTipText = "已打勾 $done 项，但其中 $untrustedCount 项缺乏有效测试/执行凭据"
        }
        val progress = if (total > 0) U.column(10, progressLabel, U.progress(done, total)) else U.label("暂无计划", 11, U.faint)
        return U.row(left,progress).apply { border=BorderFactory.createCompoundBorder(BottomLine(U.border),JBUI.Borders.empty(17,0)); name="feature-${item.str("slug")}" }
    }
    fun selectFeature(slug:String) {
        selectedSlug=slug;selectedDocument=null;checkedFeatureRevision=null;verificationData=null;detailData=null;tasks=emptyList();files=emptyList();lastLocalReqKey=null;lastReviewKey=null;reviewService.invalidate();renderTasks()
        featureHeader.removeAll();featureOverview.removeAll();U.append(featureOverview,U.empty("正在读取需求…",slug));navigate("feature")
        changing=true;tabs.selectedIndex=SUMMARY;featureChangesTabs.selectedIndex=REVIEW_CHANGES;changing=false;remember()
        service.loadFeature(slug) { result ->
            if(disposed||selectedSlug!=slug) return@loadFeature
            val data=result.detail?.data.obj()
            if(result.error!=null||data?.get("summary").obj()?.str("slug")!=slug) { notice.text=result.error?:"详情不可用";notice.isVisible=true;return@loadFeature }
            state=result;showFeature(data!!)
            service.loadVerification(slug) { evidence -> if(!disposed&&selectedSlug==slug&&evidence.error==null) { verificationData=evidence.verification?.data.obj();renderFeatureOverview(data);renderVerification() } }
        }
    }
    private fun showFeature(data: JsonObject) {
        detailData = data; val summary = data.get("summary").obj() ?: return
        val related = summary.objects("repositoryBindings").mapNotNull { it.str("repository") }.toSet()
        git.setRelated(related); workingGit.setRelated(related); workingGit.filter("related"); repositoryGit.setRelated(related)
        
        featureHeader.removeAll()
        val slug = summary.str("slug").orEmpty()
        val title = summary.str("title").orEmpty()
        val status = summary.str("status") ?: "development"
        val progression = data.get("progression").obj()
        val currentStage = progression?.str("currentStage") ?: "feature.implement"
        tasks = data.objects("tasks")
        files = documentFiles(data)
        val completedCount = tasks.count { it.get("completed")?.asBoolean == true }
        val totalCount = tasks.size

        // Line 1: Breadcrumb + Copy Prompt Button
        val breadcrumb = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            val fBtn = JButton("Feature").apply {
                isBorderPainted = false
                isContentAreaFilled = false
                font = font.deriveFont(11.5f)
                foreground = U.muted
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { navigate("features") }
            }
            val arrow = JLabel("›").apply {
                font = font.deriveFont(11.5f)
                foreground = U.faint
            }
            val slugLabel = JLabel(slug).apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
                foreground = U.muted
            }
            add(fBtn)
            add(arrow)
            add(slugLabel)
        }

        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            val copyPromptBtn = U.button("预览接手包", true) {
                if (service.supports("handoff")) service.loadHandoff(slug) { result ->
                    val data = result.handoff
                    if (selectedSlug != slug) return@loadHandoff
                    if (result.error == null && data?.slug == slug) HandoffDialog(project, data).show()
                    else WorkbenchNotifier.warn(project, "获取接手包失败", result.error ?: "接手包不可用")
                } else service.copyPrompt(slug) { result ->
                    result.fold({ text ->
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                        WorkbenchNotifier.info(project, "已复制接手提示词", "当前 Kit 不支持接手包预览，已复制兼容版 brief。")
                    }, { failure -> WorkbenchNotifier.warn(project, "获取接手提示词失败", failure.message ?: "未知错误") })
                }
            }.apply {
                icon = AllIcons.Actions.Preview
                toolTipText = "预览带来源和 token 估算的接手包；旧 Kit 自动复制兼容版 brief"
            }
            val openDocBtn = U.button("查看文档 (F4)", true) {
                WorkbenchNavigation.openFeatureInEditor(project, slug)
            }.apply {
                icon = AllIcons.Actions.Preview
            }
            val locateBtn = U.button("在工程中定位", true) {
                WorkbenchNavigation.locateFeatureInProjectView(project, slug)
            }.apply {
                icon = AllIcons.General.Locate
            }
            val refreshBtn = U.button("刷新") { reload() }.apply {
                icon = AllIcons.Actions.Refresh
            }
            add(copyPromptBtn)
            add(openDocBtn)
            add(locateBtn)
            add(refreshBtn)
        }

        val line1 = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(breadcrumb, BorderLayout.WEST)
            add(rightActions, BorderLayout.EAST)
        }

        // Line 2: Big bold title
        val titleLabel = JLabel(title).apply {
            font = font.deriveFont(Font.BOLD, 20f)
            foreground = U.text
        }
        val line2 = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 8, 0)
            add(titleLabel, BorderLayout.CENTER)
        }

        // Line 3: Status capsules
        val leftCapsules = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            val statusPill = JLabel(U.status(status)).apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = com.intellij.ui.JBColor(0x2563EB, 0x60A5FA)
                toolTipText = status
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(com.intellij.ui.JBColor(0x93C5FD, 0x1E3A8A), 1),
                    JBUI.Borders.empty(3, 8)
                )
            }
            val stagePill = JLabel(U.stage(currentStage)).apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
                foreground = U.muted
                toolTipText = currentStage
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(com.intellij.ui.JBColor(0xE5E7EB, 0x374151), 1),
                    JBUI.Borders.empty(3, 8)
                )
            }
            add(statusPill)
            add(stagePill)

            val planSummary = summary.get("planSummary").obj()
            val completionPolicy = planSummary?.str("completionPolicy")
            val trustedObj = planSummary?.get("trustedProgress").obj()
            val isTrustedApplicable = trustedObj?.get("applicable")?.asBoolean == true
            val trustedDone = if (isTrustedApplicable) trustedObj?.get("completed")?.takeIf { it.isJsonPrimitive }?.asInt ?: completedCount else null
            val untrustedCount = if (isTrustedApplicable && trustedDone != null && completedCount > trustedDone) completedCount - trustedDone else 0

            if (completionPolicy == "task-evidence-v1") {
                val policyPill = JLabel("凭据门禁").apply {
                    font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
                    foreground = com.intellij.ui.JBColor(0x047857, 0x10B981)
                    toolTipText = "采用 task-evidence-v1 策略，任务需具备有效执行/验证凭据"
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(com.intellij.ui.JBColor(0xA7F3D0, 0x064E3B), 1),
                        JBUI.Borders.empty(3, 8)
                    )
                }
                add(policyPill)
            }

            if (untrustedCount > 0) {
                val warningPill = JLabel("⚠ $untrustedCount 项缺凭据").apply {
                    font = font.deriveFont(Font.BOLD, 11f)
                    foreground = com.intellij.ui.JBColor(0xD97706, 0xF59E0B)
                    toolTipText = "已标记完成 $completedCount 项，但其中 $untrustedCount 项缺乏有效凭据记录或未通过验证"
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(com.intellij.ui.JBColor(0xFDE68A, 0x78350F), 1),
                        JBUI.Borders.empty(3, 8)
                    )
                }
                add(warningPill)
            }

            val blockers = progression?.objects("blockers").orEmpty()
            if (blockers.isNotEmpty()) {
                val blockerPill = JLabel("⛔ ${blockers.size} 项阻塞").apply {
                    font = font.deriveFont(Font.BOLD, 11f)
                    foreground = com.intellij.ui.JBColor(0xDC2626, 0xEF4444)
                    toolTipText = blockers.joinToString("\n") { it.str("message") ?: it.str("code") ?: "阻塞项" }
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(com.intellij.ui.JBColor(0xFECACA, 0x7F1D1D), 1),
                        JBUI.Borders.empty(3, 8)
                    )
                }
                add(blockerPill)
            }
        }

        val line3 = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(leftCapsules, BorderLayout.WEST)
        }

        featureHeader.add(line1)
        featureHeader.add(line2)
        featureHeader.add(line3)
        progression?.objects("nextActions").orEmpty().takeIf { it.isNotEmpty() }?.let { featureHeader.add(nextActionsRow(it)) }
        featureHeader.border = JBUI.Borders.empty(0, 0, 8, 0)

        tabs.setTitleAt(PLAN, if (totalCount > 0) "计划 $completedCount/$totalCount" else "计划")
        renderTasks(); renderFeatureOverview(data); renderVerification()
        committedChanges.showFeature(data, repositories())
        changing = true
        docPanel.setDocuments(files, data.get("documentReviews").obj())
        selectedDocument = docPanel.activePath ?: selectedDocument ?: files.firstOrNull()?.str("path")
        changing = false
        featureHeader.revalidate(); featureHeader.repaint(); editorTitle.text = title; loadVisible()
    }

    /** progression.nextActions：工作流对「下一步该做什么」的建议，附操作指引 runbook 入口。 */
    private fun nextActionsRow(actions: List<JsonObject>): JComponent {
        val flow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply { isOpaque = false }
        flow.add(U.label("下一步", 11, U.muted))
        actions.take(3).forEach { action ->
            val runbook = action.str("runbook")
            val text = listOfNotNull(action.str("stage")?.let(U::stage), action.str("reason")).joinToString(" · ").ifBlank { "查看操作指引" }
            flow.add(U.button(text, true) {
                if (runbook == null || !WorkbenchNavigation.openInEditor(project, state.kitRoot, runbook)) {
                    WorkbenchNotifier.warn(project, "未找到操作指引", runbook ?: text)
                }
            }.apply {
                icon = AllIcons.Actions.Execute
                toolTipText = runbook?.let { "打开操作指引：$it" } ?: text
            })
        }
        return flow.apply { border = JBUI.Borders.empty(6, 0, 0, 0) }
    }

    private fun renderFeatureOverview(data: JsonObject) {        val summary = data.get("summary").obj() ?: return
        val slug = summary.str("slug").orEmpty()
        val fDir = summary.str("path").orEmpty()
        val root = state.kitRoot
        val repoRoots = repositoryRoots(repositories())

        val reqFileMeta = files.firstOrNull { f ->
            val p = f.str("path") ?: ""
            p.equals("requirements/requirements.md", true) || p.equals("requirements.md", true) || p.endsWith("/requirements.md", true)
        } ?: files.firstOrNull { it.str("path")?.endsWith("requirements.md", true) == true }
          ?: files.firstOrNull { it.str("path")?.contains("requirements.md", true) == true }
          ?: files.firstOrNull { it.str("path")?.contains("requirements", true) == true }
        val reqPath = reqFileMeta?.str("path") ?: "requirements/requirements.md"

        val localContent: String? = null

        featureDashboard.update(
            featureData = data,
            tasks = tasks,
            repositoryBindings = summary.objects("repositoryBindings"),
            repoRoots = repoRoots,
            requirementsContent = localContent,
            requirementsPath = reqPath,
            scenes = scenes,
            onNavigateTab = { tabIndex -> tabs.selectedIndex = tabIndex }
        )

        // 本地兜底读取放后台线程：EDT 上同步读几十 KB 的 PRD 会卡界面；每个 (slug, path) 只读一次，
        // 之后的内容更新由带 revision 的 loadDocument 远程读取覆盖。
        val localReqKey = "$slug:$reqPath"
        if (!root.isNullOrBlank() && fDir.isNotBlank() && localReqKey != lastLocalReqKey) {
            lastLocalReqKey = localReqKey
            val candidates = listOf(
                Path.of(root, fDir, reqPath),
                Path.of(root, fDir, "requirements.md"),
                Path.of(root, fDir, "requirements", "requirements.md"),
                Path.of(root, fDir, "README.md"),
            )
            ApplicationManager.getApplication().executeOnPooledThread {
                val content = candidates.firstOrNull { java.nio.file.Files.exists(it) }
                    ?.let { runCatching { java.nio.file.Files.readString(it) }.getOrNull() }
                if (content != null) ApplicationManager.getApplication().invokeLater {
                    if (!disposed && selectedSlug == slug) featureDashboard.setRequirementsContent(reqPath, content)
                }
            }
        }

        service.loadDocument(slug, reqPath, reqFileMeta?.str("revision")) { docSnapshot ->
            if (disposed || selectedSlug != slug) return@loadDocument
            val remoteContent = docSnapshot.document?.data.obj()?.str("content")
            if (!remoteContent.isNullOrBlank()) {
                featureDashboard.setRequirementsContent(reqPath, remoteContent)
            }
        }
    }

    private fun refreshReviews(force: Boolean = false) {
        val data = detailData ?: return
        val summary = data.get("summary").obj() ?: return
        refreshReviews(summary.str("slug").orEmpty(), summary, repositoryRoots(repositories()), force)
    }

    private fun refreshReviews(slug: String, summary: JsonObject, repoRoots: Map<String, Path>, force: Boolean = false) {
        if (route != "feature" || tabs.selectedIndex != CHANGES || featureChangesTabs.selectedIndex != REVIEW_CHANGES || slug.isBlank()) return
        val root = state.kitRoot ?: return
        val bindings = summary.objects("repositoryBindings").mapNotNull { binding ->
            val repository = binding.str("repository") ?: return@mapNotNull null
            ReviewBinding(repository, repoRoots[repository], binding.str("workBranch"))
        }
        val key = listOf(root, slug, *bindings.map { "${it.repository}:${it.root}:${it.workBranch}" }.toTypedArray()).joinToString("\u0000")
        if (!force && key == lastReviewKey) return
        lastReviewKey = key
        reviewService.refresh(root, slug, bindings)
    }

    private fun renderTasks() {
        displayedTasks = tasks.filter {
            when (taskFilter.selectedIndex) {
                1 -> it.get("completed")?.asBoolean != true
                2 -> it.get("completed")?.asBoolean == true
                3 -> it.get("completed")?.asBoolean == true && it.get("trusted")?.takeIf { p -> p.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull() == true
                4 -> it.get("completed")?.asBoolean == true && it.get("trusted")?.takeIf { p -> p.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull() == false
                else -> true
            }
        }
        val planSummary = detailData?.get("summary").obj()?.get("planSummary").obj()
        val trustedObj = planSummary?.get("trustedProgress").obj()
        val isTrustedApplicable = trustedObj?.get("applicable")?.asBoolean == true
        val trustedDone = if (isTrustedApplicable) trustedObj?.get("completed")?.takeIf { it.isJsonPrimitive }?.asInt else null

        val totalCount = tasks.size
        val completedCount = tasks.count { it.get("completed")?.asBoolean == true }
        val progressDesc = if (isTrustedApplicable && trustedDone != null) {
            "$completedCount/$totalCount 完成 (可信 $trustedDone)"
        } else {
            "$completedCount/$totalCount 项完成"
        }
        planTitle.text = "实施计划 · $progressDesc"

        setRows(taskTable, displayedTasks.map {
            val isDone = it.get("completed")?.asBoolean == true
            val trusted = it.get("trusted")?.takeIf { p -> p.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull()
            val statusStr = when {
                isDone && trusted == true -> "✓ 可信"
                isDone && trusted == false -> "⚠ 缺凭据"
                isDone -> "✓ 已完成"
                else -> "○ 待完成"
            }
            val kind = it.str("validationKind")
            val titleDisplay = if (kind != null) "[$kind] ${it.str("title")}" else it.str("title")
            listOf(
                it.str("id") ?: "未编号",
                statusStr,
                titleDisplay,
                it.get("dependencies").texts().ifBlank { "无" },
                "${it.str("path")}:${it.str("startLine")}"
            )
        })
    }
    private fun showDocument(path: String, line: Int? = null) {
        changing = true
        tabs.selectedIndex = DOCUMENTS
        selectedDocument = path
        docPanel.selectDocument(path, fireEvent = false)
        changing = false
        openDocument(path, line = line)
    }
    private fun locateTask(task: JsonObject) {
        val path = task.str("path") ?: return
        changing = true
        tabs.selectedIndex = DOCUMENTS
        selectedDocument = path
        docPanel.selectDocument(path, fireEvent = false)
        changing = false
        openDocument(path, task.str("startLine")?.toIntOrNull(), taskKey = task.str("id") ?: task.str("title"))
    }
    private fun openDocument(path: String, line: Int? = null, retried: Boolean = false, taskKey: String? = null, anchor: String? = null) {
        val slug = selectedSlug ?: return
        val metadata = files.firstOrNull { it.str("path") == path } ?: return
        val revision = metadata.str("revision")
        selectedDocument = path
        remember()
        docPanel.selectDocument(path, fireEvent = false)
        documentStatus.text = "正在读取 $path"
        service.loadDocument(slug, path, revision) { updated ->
            if (disposed || selectedSlug != slug || selectedDocument != path) return@loadDocument
            val doc = updated.document?.data.obj()
            if (doc == null || doc.str("slug") != slug || doc.str("path") != path || updated.error != null) {
                if (!retried) service.loadFeature(slug) { fresh ->
                    if (!disposed && selectedSlug == slug) {
                        fresh.detail?.data.obj()?.let { files = documentFiles(it); tasks = it.objects("tasks") }
                        val relocated = taskKey?.let { key -> tasks.filter { (it.str("id") ?: it.str("title")) == key }.singleOrNull()?.str("startLine")?.toIntOrNull() }
                        openDocument(path, if (taskKey != null) relocated else line, true, taskKey, anchor)
                    }
                } else documentStatus.text = updated.error ?: "文档持续变化，保留上次内容"
                return@loadDocument
            }
            documentRevision = doc.str("revision")
            val saved = state.kitRoot?.let { settings.preference(it).positions.lastOrNull { p -> p.key == "$slug:$path:$documentRevision" }?.offset }
            val plain = doc.str("mediaType") != "text/markdown"
            val content = doc.str("content").orEmpty()
            // Markdown 解析与锚点扫描在后台完成，EDT 只做应用。
            ApplicationManager.getApplication().executeOnPooledThread {
                val prepared = DocumentReader.prepare(content)
                ApplicationManager.getApplication().invokeLater {
                    if (disposed || selectedSlug != slug || selectedDocument != path) return@invokeLater
                    reader.showPrepared(prepared, line ?: saved, plain)
                    anchor?.let(reader::goToAnchor)
                    documentStatus.text = "${doc.str("lineCount")} 行"
                }
            }
        }
    }

    private fun taskSelected(): JsonObject? = displayedTasks.getOrNull(taskTable.selectedRow)
    private fun locateTask() { displayedTasks.getOrNull(taskTable.selectedRow)?.let(::locateTask) }
    private fun locateTaskInEditor(task: JsonObject) {
        val slug = selectedSlug ?: return
        val path = task.str("path") ?: "plans/implementation.md"
        val line = task.str("startLine")?.toIntOrNull()
        WorkbenchNavigation.openFeatureInEditor(project, slug, path, line)
    }

    private fun openLink(target: String) {
        val uri = runCatching { URI(target) }.getOrNull() ?: return
        if (uri.scheme in listOf("http", "https")) { BrowserUtil.browse(uri); return }
        if (uri.scheme != null || target.startsWith("/")) { documentStatus.text = "不支持的链接：$target"; return }
        if (uri.path.isNullOrBlank() && uri.fragment != null) { if (!reader.goToAnchor(uri.fragment)) documentStatus.text = "未找到文档锚点：${uri.fragment}"; return }
        val base = selectedDocument?.let(Path::of)?.parent ?: Path.of("")
        val path = if (uri.path.isNullOrBlank()) selectedDocument.orEmpty() else base.resolve(uri.path).normalize().toString()
        if (path.startsWith("..")) {
            val featurePath = state.detail?.data.obj()?.get("summary").obj()?.str("path")?.let(Path::of) ?: return
            val area = featurePath.parent ?: return
            val resolved = featurePath.resolve(base).resolve(uri.path).normalize()
            if (!resolved.startsWith(area)) { documentStatus.text = "链接不在工作流需求目录：$target"; return }
            val relative = area.relativize(resolved)
            if (relative.nameCount < 2) return
            val slug = relative.getName(0).toString()
            val origin = selectedSlug
            val root = state.kitRoot
            service.loadFeature(slug) { result ->
                if (disposed || selectedSlug != origin || result.kitRoot != root) return@loadFeature
                if (result.error != null) { documentStatus.text = "链接目标需求不可读取：$slug"; return@loadFeature }
                val data = result.detail?.data.obj()?.takeIf { it.get("summary").obj()?.str("slug") == slug } ?: run { documentStatus.text = "链接目标需求不可读取：$slug"; return@loadFeature }
                selectedSlug = slug; selectedDocument = relative.subpath(1, relative.nameCount).toString(); state = result
                showFeature(data); navigate("feature"); tabs.selectedIndex = DOCUMENTS
                openDocument(selectedDocument!!, anchor = uri.fragment)
            }
            return
        }
        if (files.none { it.str("path") == path }) { documentStatus.text = "此链接不在当前需求文档集合：$target"; return }
        changing = true; docPanel.selectDocument(path, fireEvent = false); changing = false
        openDocument(path, anchor = uri.fragment)
    }

    private fun queryVerification(checkCode:Boolean=false) {
        val slug=selectedSlug?:return
        if(checkCode) { checkingCode=true; renderVerification() }
        service.loadVerification(slug,checkCode) { result ->
            if(disposed||selectedSlug!=slug) return@loadVerification
            if(checkCode) checkingCode=false
            if(result.error!=null) { notice.text="验证读取失败：${result.error}";notice.isVisible=true;renderVerification();return@loadVerification }
            verificationData=result.verification?.data.obj()?.takeIf { it.str("slug")==slug }?:return@loadVerification
            renderVerification();detailData?.let(::renderFeatureOverview)
            val revision=verificationData?.str("featureRevision")
            if(checkCode) checkedFeatureRevision=revision
            else if(active&&route=="feature"&&tabs.selectedIndex==VERIFY&&revision!=checkedFeatureRevision&&verificationData?.get("selectedBatch").obj()!=null&&verificationData?.str("applicability")!="historical") queryVerification(true)
        }
    }
    private fun renderVerification() {
        verificationBody.removeAll(); verificationBody.border=JBUI.Borders.empty(26,0,20,0)
        val data=verificationData;val batch=data?.get("selectedBatch").obj()
        U.append(verificationBody,U.metrics(U.metric("上次检查结果",U.state(batch?.str("recordedResult")),"${batch?.str("recordedAt")?:"尚无验证批次"} · ${batch?.objects("checks")?.size?:0} 项检查",U.stateColor(batch?.str("recordedResult")),true),U.metric("对当前代码是否有效",U.state(data?.str("applicability")),if(checkingCode) "正在核对当前代码…" else "记录完整性：${U.state(batch?.str("completeness"))}",U.stateColor(data?.str("applicability")),true)))
        U.append(verificationBody,U.section("检查证据",U.flow(U.button("查看批次原文") { showDocument("testing/verification.md") },U.button("核对当前代码") { queryVerification(true) })),24)
        batch?.objects("checks")?.forEach { check ->
            val passed=check.str("exitStatus")=="0"
            val title=U.row(U.column(6,U.label("${if(passed) "✓" else "!"}  检查 ${check.str("id")}",13,bold=true),U.mono(check.str("workingDirectory")?:"工作目录未记录")),U.flow(U.label("退出 ${check.str("exitStatus")?:"未记录"}",11,if(passed) U.green else U.red),U.label(check.str("duration")?:"耗时未记录",10,U.faint)))
            val evidence=U.column(9,U.copy(check.str("result").orEmpty()),U.mono("命令：${check.str("command")?:"未记录"}"),U.mono("退出状态：${check.str("exitStatus")?:"未记录"}"))
            check.str("testCount")?.let { U.append(evidence,U.label("$it 个测试",11,U.muted),8) }
            U.append(verificationBody,disclosure(title,evidence,expanded=true),8)
        }
        if(batch==null) U.append(verificationBody,U.empty("尚无验证记录","这里展示已有验证证据；工作台不会执行测试。"))
        val taskEvidences = data?.objects("taskEvidence").orEmpty()
        if (taskEvidences.isNotEmpty()) {
            U.append(verificationBody, U.section("任务凭据矩阵 (Task Evidence)", U.label("针对实施计划各任务的独立验证与测试凭据", 11, U.faint)), 20)
            taskEvidences.forEach { evidence ->
                val taskId = evidence.str("taskId") ?: "—"
                val trusted = evidence.get("trusted")?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull()
                val source = evidence.get("source").obj()
                val path = source?.str("path") ?: "testing/verification.md"
                val startLine = source?.str("startLine")?.toIntOrNull()
                val endLine = source?.str("endLine")?.toIntOrNull()
                val rangeText = if (startLine != null && endLine != null) "$path:$startLine-$endLine" else path
                val diags = evidence.objects("diagnostics")

                val stateBadge = when (trusted) {
                    true -> U.badge("✓ 凭据有效", U.green)
                    false -> U.badge("⚠ 凭据异常/待补", U.amber)
                    else -> U.badge("○ 未验证", U.faint)
                }

                val taskRow = U.row(
                    U.column(4,
                        U.row(U.label("任务 $taskId", 12, bold = true), stateBadge),
                        U.mono(rangeText, U.muted)
                    ),
                    U.button("查看凭据原文") { showDocument(path, startLine) }
                )
                val content = if (diags.isNotEmpty()) {
                    U.column(6,
                        taskRow,
                        *diags.map { diag ->
                            U.label("• ${diag.str("message") ?: diag.str("code") ?: "诊断告警"}", 11, U.red)
                        }.toTypedArray()
                    )
                } else {
                    taskRow
                }
                U.append(verificationBody, content.apply {
                    border = BorderFactory.createCompoundBorder(BottomLine(U.border), JBUI.Borders.empty(8, 0))
                }, 6)
            }
        }
        U.append(verificationBody,U.section("各仓代码核对",U.label("记录的代码状态 → 当前现场",11,U.faint)),26)
        data?.objects("repositoryStates")?.forEach { repo ->
            val content=U.column(12,U.row(U.label(repo.str("repository").orEmpty(),13,bold=true),U.badge(U.state(repo.str("state")),U.stateColor(repo.str("state")))),U.copy(repo.get("reasonCodes").texts().ifBlank { if(repo.str("state")=="not_checked") "尚未核对当前工作目录" else "当前代码与批次记录一致" }),U.mono("HEAD ${repo.str("currentHead")?:"未记录"}"))
            U.append(verificationBody,content.apply { border=BorderFactory.createCompoundBorder(BottomLine(U.border),JBUI.Borders.empty(14,0)) },8)
        }
        // 历史验证批次（batches[]）：最新批次已作为 selectedBatch 展示，历史批次提供记录原文入口。
        val batches = data?.objects("batches").orEmpty()
        if (batches.size > 1) {
            val selectedId = batch?.str("id")
            U.append(verificationBody, U.section("历史验证批次", U.label("共 ${batches.size} 批 · 点击查看记录原文", 11, U.faint)), 26)
            batches.asReversed().forEach { item ->
                val source = item.get("source").obj()
                val sourcePath = source?.str("path") ?: "testing/verification.md"
                val startLine = source?.str("startLine")?.toIntOrNull()
                val isSelected = item.str("id") != null && item.str("id") == selectedId
                val row = U.row(
                    U.column(4,
                        U.flow(*listOfNotNull(U.label(item.str("recordedAt") ?: "未记录时间", 12, bold = isSelected), if (isSelected) U.badge("当前展示") else null).toTypedArray()),
                        U.mono(item.str("id")?.take(32).orEmpty(), U.faint)),
                    U.button("查看原文", true) { showDocument(sourcePath, startLine) }
                )
                U.append(verificationBody, row.apply { border = BorderFactory.createCompoundBorder(BottomLine(U.border), JBUI.Borders.empty(10, 0)) }, 6)
            }
        }
        verificationBody.revalidate();verificationBody.repaint()
    }
    private fun queryWorkflow() {
        service.loadWorkflow { result ->
            if(disposed) return@loadWorkflow
            if(result.error!=null) { runStatus.text="读取失败，保留当前流程";return@loadWorkflow }
            workflowData=result.workflow?.data.obj();renderWorkflow();renderExtensions();renderSidebar()
        }
        // feature 上下文按需求由 Kit 服务端过滤（inspect runs --feature）；runs 全局页拉全量。
        val featureFilter = selectedSlug.takeIf { route == "feature" }
        service.loadRuns(featureFilter) { result ->
            if(disposed) return@loadRuns
            if(result.error!=null) { runStatus.text="流程记录读取失败，保留当前内容";return@loadRuns }
            val previous=runPicker.selectedItem ?: state.kitRoot?.let { settings.preference(it).run.takeIf(String::isNotBlank) }
            changing=true;runPicker.removeAllItems()
            val records=result.runs?.data.obj()?.objects("items").orEmpty()
            val pickerRecords = if (featureFilter != null) records else records.filter { it.str("featureSlug")==selectedSlug }
            pickerRecords.forEach { runPicker.addItem(it.str("id")) }
            if(previous!=null&&(0 until runPicker.itemCount).any { runPicker.getItemAt(it)==previous }) runPicker.selectedItem=previous
            changing=false
            renderGlobalRuns(records, result.runs?.data.obj()?.get("counts").obj())
            if(runPicker.itemCount>0) loadRun() else { runData=null;renderWorkflow() }
        }
    }
    private fun loadRun() { val id=runPicker.selectedItem?.toString()?:return; runData=null;renderWorkflow();readRun(id) }
    private fun readRun(id:String) {
        service.loadRun(id) { result ->
            if(disposed) return@loadRun
            if(result.error!=null) { runStatus.text="Run 读取失败，保留当前内容";return@loadRun }
            runData=result.run?.data.obj()?.takeIf { it.str("id")==id };renderWorkflow()
            if(route=="runs") showRunDetail()
        }
    }
    private fun renderWorkflow() {
        workflowBody.removeAll();val data=workflowData
        runStatus.text=if(runPicker.itemCount==0) "此需求尚无 Run" else "来源：.workspace/runs/${runPicker.selectedItem}.json"
        U.append(workflowBody,U.row(U.flow(U.badge("当前阶段建议"),U.label(U.stage(detailData?.get("progression").obj()?.str("currentStage")),11)),U.label("Core 阶段不推断完成；扩展展示所选 Run 的记录",10,U.faint)).apply { border=BorderFactory.createCompoundBorder(BottomLine(U.border),JBUI.Borders.empty(0,0,18,0)) })
        if(data==null) { U.append(workflowBody,U.empty("流程待读取","打开流程页后读取当前配置。"));return }
        if(data.get("orderedStages")?.isJsonNull!=false) U.append(workflowBody,U.copy("流程配置不可解析，无法展示有效顺序。已有 Run 原文仍可查看。"),16)
        val records=runData?.objects("records").orEmpty().associateBy { it.str("stage") }
        data.objects("orderedStages").forEach { stage ->
            val id=stage.str("id");val record=records[id];val current=id==detailData?.get("progression").obj()?.str("currentStage")
            val action=stage.str("action")
            val actionTitle=data.objects("extensions").flatMap { extension -> extension.objects("actions").map { "${extension.str("id")}/${it.str("id")}" to it.str("title") } }.firstOrNull { it.first==action }?.second
            val label=if(stage.get("core")?.asBoolean==true) U.stage(id) else actionTitle ?: stage.str("title")?.takeIf { it!=id } ?: id.orEmpty()
            val heading=U.row(U.column(8,U.label(label,13,if(current) U.accent else U.text,true),U.mono(id.orEmpty(),U.faint)),U.flow(*listOfNotNull(if(current) U.badge("阶段建议") else null,record?.let { U.badge(U.state(it.str("status")),U.stateColor(it.str("status"))) },record?.get("configurationMatch").obj()?.let { U.badge("配置${U.state(it.str("state"))}",U.stateColor(it.str("state"))) }).toTypedArray()))
            val inside=if(record==null) heading else disclosure(heading,U.column(10,U.copy(record.str("summary").orEmpty()),U.mono("更新时间：${record.str("updatedAt")}"),U.label("配置匹配不代表业务代码有效",11,U.muted)))
            val marker = TimelineMarker(
                current = current,
                extension = stage.get("core")?.asBoolean != true,
                stageTitle = label,
                stageState = record?.str("status")?.let { U.state(it) }
            )
            U.append(workflowBody,U.panel(BorderLayout(12,0)).apply { add(marker,BorderLayout.WEST);add(inside);border=JBUI.Borders.empty(18,3,8,0) })
        }
        val currentIds=data.objects("orderedStages").mapNotNull { it.str("id") }.toSet()
        records.filterKeys { it !in currentIds }.values.forEach { record -> U.append(workflowBody,U.column(8,U.badge("已删除的历史步骤",U.amber),U.label(record.str("stage").orEmpty()),U.copy(record.str("summary").orEmpty())),16) }
        if(runData!=null) U.append(workflowBody,U.button("查看 Run 原始记录",true) { showRunDetail();navigate("runs") },24)
        workflowBody.revalidate();workflowBody.repaint()
    }
    private fun renderGlobalRuns(records:List<JsonObject>, counts:JsonObject?=null) {
        val parsedTotal = counts?.str("parsed")?.toIntOrNull() ?: records.size
        globalRuns.removeAll();U.append(globalRuns,pageHeader("流程记录","现有运行摘要 · 共 $parsedTotal 条 · 不代表完整操作历史",U.button("刷新") { queryWorkflow() }.apply { icon = AllIcons.Actions.Refresh }))
        records.forEach { record ->
            val title=state.features.firstOrNull { it.str("slug")==record.str("featureSlug") }?.str("title")?:record.str("featureSlug")?:record.str("id").orEmpty()
            U.append(globalRuns,U.row(U.column(9,U.button(title,true) { readRun(record.str("id")!!) },U.mono("${record.str("id")}  ·  ${record.str("updatedAt")?:"未记录时间"}")),U.label("${record.get("recordCounts").obj()?.str("total")?:"0"} 个步骤  →",11,U.muted)).apply { border=BorderFactory.createCompoundBorder(BottomLine(U.border),JBUI.Borders.empty(22,0)) },12)
        }
        if(records.isEmpty()) U.append(globalRuns,U.empty("暂无流程记录","此工作区尚未保存 Run。"),24)
        globalRuns.revalidate();globalRuns.repaint()
    }
    private fun showRunDetail() {
        val record=runData?:return
        globalRuns.removeAll();U.append(globalRuns,pageHeader(record.str("id").orEmpty(),"来源：${record.str("source")}",U.button("← 所有流程记录") { queryWorkflow() }))
        record.objects("records").forEach { step -> U.append(globalRuns,disclosure(U.row(U.label(step.str("stage").orEmpty(),13,bold=true),U.badge(U.state(step.str("status")),U.stateColor(step.str("status")))),U.column(10,*listOfNotNull<JComponent>(U.copy(step.str("summary").orEmpty()),U.label("配置：${U.state(step.get("configurationMatch").obj()?.str("state"))}",11,U.muted),step.get("configurationMatch").obj()?.get("reasonCodes").texts().takeIf(String::isNotBlank)?.let { U.mono("原因码：$it") },U.mono(step.str("updatedAt").orEmpty())).toTypedArray())),18) }
        U.append(globalRuns,disclosure(U.label("Run 原始记录",13),U.copy(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(record.get("rawRecord")))) ,20)
        globalRuns.revalidate();globalRuns.repaint()
    }
    private fun renderExtensions() {
        val target = globalExtensions
        target.removeAll();U.append(target,pageHeader("扩展", "来自当前声明与锁定状态"))
        workflowData?.objects("extensions")?.forEach { extension ->
            val head=U.row(U.column(8,U.label(extension.str("id").orEmpty(),15,bold=true),U.label("声明 ${extension.str("declaredVersion")?:"未记录"} · 锁定 ${extension.str("lockedVersion")?:"未锁定"}",11,U.muted)),U.badge(U.state(extension.str("activation")),U.stateColor(extension.str("activation"))))
            val body=U.column(14,head)
            extension.objects("actions").forEach { action ->
                U.append(body,U.row(U.label(action.str("title")?:action.str("id").orEmpty(),12),U.mono(action.get("effects").texts())),8)
                describeSummaries["${extension.str("id")}/${action.str("id")}"]?.let { U.append(body,U.copy(it),4) }
            }
            U.append(target,body.apply { border=BorderFactory.createCompoundBorder(BottomLine(U.border),JBUI.Borders.empty(20,0)) },18)
        }
        if(workflowData?.objects("extensions").isNullOrEmpty()) U.append(target,U.empty("暂无已登记扩展","工作台按实际声明展示，不创建或激活扩展。"),20)
        target.revalidate();target.repaint()
    }
    private fun renderConfiguration() {
        configuration.removeAll();U.append(configuration,pageHeader("工作区配置","只读查看配置与分支策略来源"))
        U.append(configuration,U.column(9,U.label("Kit 根目录",11,U.muted),U.mono(state.kitRoot?:"未绑定"),U.label("Python 解释器",11,U.muted),U.mono(state.python?:"未配置")),22)
        repositories().forEach { repo ->
            U.append(configuration,U.section(repo.str("id").orEmpty()),28)
            repo.get("effectiveBranchPolicy").obj()?.entrySet()?.forEach { (key,value) -> U.append(configuration,U.row(U.column(6,U.label(key,12),U.label(repo.get("policySources").obj()?.get(key).obj()?.str("source")?:"未记录来源",10,U.faint)),U.mono(value.text())),12) }
        }
        state.workspace?.data.obj()?.get("protocol").obj()?.let { protocol ->
            U.append(configuration,U.section("协议与限额"),28)
            U.append(configuration,U.row(U.label("Kit 版本",12),U.mono(protocol.str("kitVersion")?:"未记录")),12)
            protocol.get("limits").obj()?.entrySet()?.forEach { (key,value) -> U.append(configuration,U.row(U.label(key,12),U.mono(value.text())),12) }
        }
        U.append(configuration,U.button("查看读取诊断  →",true) { navigate("diagnostics") },22)
        configuration.revalidate();configuration.repaint()
    }
    /** 结构化渲染 Inspect 诊断：severity 着色，带 path/line 的支持跳转到源文件。 */
    private fun renderDiagnostics() {
        diagnosticsBody.removeAll()
        state.error?.let { U.append(diagnosticsBody, U.copy("读取错误：$it"), 4) }
        val items = state.workspace?.diagnostics.orEmpty()
        if (items.isEmpty() && state.error == null) U.append(diagnosticsBody, U.copy("无诊断"))
        items.forEach { diag ->
            val color = when (diag.severity?.lowercase()) { "error" -> U.red; "warning", "warn" -> U.amber; else -> U.muted }
            val block = U.column(6,
                U.flow(U.badge(diag.severity?.uppercase() ?: "INFO", color), U.mono(diag.code, U.faint)),
                U.copy(diag.message))
            diag.path?.let { path ->
                val line = diag.line
                U.append(block, U.button(if (line != null) "$path:$line" else path, true) {
                    if (!WorkbenchNavigation.openInEditor(project, state.kitRoot, path, line)) {
                        WorkbenchNotifier.warn(project, "无法打开诊断位置", path)
                    }
                }.apply { icon = AllIcons.General.Locate }, 4)
            }
            U.append(diagnosticsBody, block.apply { border = BorderFactory.createCompoundBorder(BottomLine(U.border), JBUI.Borders.empty(10, 0)) }, 8)
        }
        diagnosticsBody.revalidate(); diagnosticsBody.repaint()
    }
    /** featureSummary.verificationSummary 的轻量徽章；无验证记录时不显示，避免列表噪音。 */
    private fun verificationBadge(item: JsonObject): JComponent? {
        val summary = item.get("verificationSummary").obj() ?: return null
        if (summary.get("exists")?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull() != true) return null
        return when (summary.str("recordedResult")) {
            "passed" -> U.badge("验证通过", U.green)
            "failed" -> U.badge("验证失败", U.red)
            else -> U.badge("有验证记录", U.muted)
        }
    }
    private fun loadVisible() {
        if(!active||disposed) return
        when(route) {
            "runs" -> { showWorkflowLoading(); queryWorkflow() }
            "extensions" -> { showWorkflowLoading(); queryWorkflow(); runDescribe() }
            "diagnostics" -> runDoctor()
            "feature" -> when(tabs.selectedIndex) { CHANGES -> if(featureChangesTabs.selectedIndex==REVIEW_CHANGES) refreshReviews(); DOCUMENTS -> (selectedDocument ?: docPanel.activePath)?.let { openDocument(it) };VERIFY -> queryVerification();WORKFLOW -> { showWorkflowLoading(); queryWorkflow() } }
        }
    }
    private fun showWorkflowLoading() {
        if(workflowData==null&&workflowBody.componentCount==0) { U.append(workflowBody,U.empty("正在读取流程…","从 Kit 读取流程配置与 Run 记录。"));workflowBody.revalidate();workflowBody.repaint() }
        if(route=="runs"&&globalRuns.componentCount==0) { U.append(globalRuns,U.empty("正在读取流程记录…","从 Kit 读取现有 Run 摘要。"));globalRuns.revalidate();globalRuns.repaint() }
    }
    private fun reload() {
        if(disposed) return
        scanService.refresh(repositories())
        state.kitRoot?.let { root -> state.python?.let { python -> service.bind(root,python) { refresh() } } }
        if(route=="feature") restoreFeature()
    }
    private fun remember() { if(!changing) state.kitRoot?.let { settings.preference(it).apply { query=search.text;status=lifecycle.selectedItem.toString();tab=tabs.selectedIndex;feature=selectedSlug.orEmpty();document=selectedDocument.orEmpty();run=runPicker.selectedItem?.toString().orEmpty() } } }
    private fun restore() { state.kitRoot?.let { settings.preference(it).let { saved -> changing=true;search.text=saved.query;lifecycle.selectedItem=saved.status;selectedSlug=saved.feature.takeIf(String::isNotBlank);selectedDocument=saved.document.takeIf(String::isNotBlank);tabs.selectedIndex=saved.tab.coerceIn(0,tabs.tabCount-1);changing=false } } }
    private fun restoreFeature() { selectedSlug?.let { slug -> service.loadFeature(slug) { latest -> if(!disposed&&selectedSlug==slug) {
        if(latest.error!=null) { notice.text="读取失败：${latest.error}，保留上次成功内容";notice.isVisible=true }
        else latest.detail?.data.obj()?.takeIf { it.get("summary").obj()?.str("slug")==slug }?.let { state=latest;showFeature(it) }
    } } } }
    override fun dispose() { disposed=true;refreshTimer.stop();debounce.stop();kitDebounce.stop() }

    /** 把 .workspace 下的文件变化映射到需要重查的具体数据类别，去抖后按需刷新。 */
    private fun classifyKitChanges(events: List<VFileEvent>) {
        val kit = state.kitRoot ?: return
        val prefix = "$kit/.workspace/"
        var touched = false
        events.forEach { event ->
            if (!event.path.startsWith(prefix)) return@forEach
            val rel = event.path.removePrefix(prefix)
            touched = true
            when {
                rel.startsWith("docs/features/") -> { pendingKit += "features"; if (rel.split('/').getOrNull(2) == selectedSlug) pendingKit += "feature" }
                rel.startsWith("runs/") || rel == "workflow.json" || rel.startsWith("extensions/") -> pendingKit += "workflow"
                else -> pendingKit += "workspace"
            }
        }
        if (touched && active) kitDebounce.restart()
    }
    private fun applyKitChanges() {
        if (disposed || !active) return
        val changes = pendingKit.toSet(); pendingKit.clear()
        if (changes.isEmpty()) return
        if ("workspace" in changes || "features" in changes) state.kitRoot?.let { r -> state.python?.let { p -> service.bind(r, p) { refresh() } } }
        if ("feature" in changes && route == "feature") { restoreFeature(); if (tabs.selectedIndex == VERIFY) queryVerification() }
        if ("workflow" in changes && (route in listOf("runs", "extensions") || (route == "feature" && tabs.selectedIndex == WORKFLOW))) queryWorkflow()
    }
    private fun runDoctor(force: Boolean = false) {
        val root = state.kitRoot ?: return
        val python = state.python ?: return
        if (runningDoctor || (doctorRan && !force)) return
        runningDoctor = true; doctorRan = true
        updateFeatureDoctor("环境检查中…", U.muted, AllIcons.General.Information)
        doctorBody.removeAll(); U.append(doctorBody, U.copy("正在运行 doctor 检查…")); doctorBody.revalidate(); doctorBody.repaint()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = KitClient(Path.of(python), Path.of(root)).tool("doctor", listOf("--root", root, "--json"))
            ApplicationManager.getApplication().invokeLater {
                if (disposed || state.kitRoot != root) return@invokeLater
                runningDoctor = false
                renderDoctor(result)
            }
        }
    }
    private fun renderDoctor(result: Result<String>) {
        doctorBody.removeAll()
        overviewDoctorBanner.removeAll()
        overviewDoctorBanner.isVisible = false
        result.fold({ text ->
            val parsed = runCatching { com.google.gson.JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (parsed == null) {
                U.append(doctorBody, U.copy("doctor 输出不可解析，请在终端直接运行 kit doctor 查看。"))
                updateFeatureDoctor("环境状态不可用", U.amber, AllIcons.General.Warning)
            } else {
                val summary = parsed.get("summary").obj()
                val errors = summary?.str("errors")?.toIntOrNull() ?: 0
                val warnings = summary?.str("warnings")?.toIntOrNull() ?: 0
                val info = summary?.str("info")?.toIntOrNull() ?: 0
                U.append(doctorBody, U.label("错误 $errors · 警告 $warnings · 提示 $info", 12, U.muted))
                val findings = parsed.objects("findings")
                if (findings.isEmpty()) {
                    U.append(doctorBody, U.label("✓ 未发现问题", 12, U.green), 12)
                    updateFeatureDoctor("环境正常", U.green, AllIcons.General.InspectionsOK)
                } else {
                    val hasError = errors > 0
                    val bannerIcon = if (hasError) AllIcons.General.Error else AllIcons.General.Warning
                    val bannerBg = if (hasError) com.intellij.ui.JBColor(0xFDF2F2, 0x362224) else com.intellij.ui.JBColor(0xFFFBEB, 0x382F19)
                    val bannerBorderColor = if (hasError) com.intellij.ui.JBColor(0xF8B4B4, 0x5C2B2F) else com.intellij.ui.JBColor(0xFCE96A, 0x614F18)
                    val bannerText = if (hasError) "工作区存在 $errors 项异常问题需处理" else "工作区存在 $warnings 项警告建议优化"
                    updateFeatureDoctor(if (hasError) "$errors 项环境异常" else "$warnings 项环境警告", if (hasError) U.red else U.amber, bannerIcon)
                    overviewDoctorBanner.apply {
                        background = bannerBg
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(bannerBorderColor, 1),
                            JBUI.Borders.empty(8, 14)
                        )
                        add(javax.swing.JLabel(bannerText, bannerIcon, javax.swing.JLabel.LEFT), BorderLayout.WEST)
                        add(U.button("立即排查 →", true) { navigate("diagnostics") }.apply {
                            font = font.deriveFont(11f)
                        }, BorderLayout.EAST)
                        isVisible = true
                    }
                }
                findings.forEach { finding ->
                    val level = finding.str("level")?.uppercase() ?: "INFO"
                    val color = when (level) { "ERROR" -> U.red; "WARNING", "WARN" -> U.amber; else -> U.muted }
                    val block = U.column(6, U.flow(U.badge(level, color), U.mono(finding.str("code").orEmpty(), U.faint)), U.copy(finding.str("message").orEmpty()))
                    remediationText(finding)?.let { U.append(block, U.copy(it), 4) }
                    U.append(doctorBody, block.apply { border = BorderFactory.createCompoundBorder(BottomLine(U.border), JBUI.Borders.empty(12, 0)) }, 8)
                }
            }
        }, { failure ->
            val msg = failure.message ?: "未知错误"
            U.append(doctorBody, U.copy("doctor 运行失败：$msg"))
            updateFeatureDoctor("环境检查失败", U.amber, AllIcons.General.Warning, msg)
        })
        doctorBody.revalidate(); doctorBody.repaint()
        overviewDoctorBanner.revalidate(); overviewDoctorBanner.repaint()
    }

    private fun updateFeatureDoctor(value: String, color: Color, icon: Icon, detail: String? = null) {
        featureDoctorLabel.text = value
        featureDoctorLabel.foreground = color
        featureDoctorLabel.icon = icon
        val tip = detail?.let { "<html><b>$value</b><br/>${it.replace("\n", "<br/>")}</html>" } ?: value
        featureDoctorLabel.toolTipText = tip
        featureDoctorStrip.toolTipText = tip
    }

    private fun runDescribe() {
        if (describeLoaded) return
        val root = state.kitRoot ?: return
        val python = state.python ?: return
        describeLoaded = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = KitClient(Path.of(python), Path.of(root)).tool("describe", listOf("--json"))
            ApplicationManager.getApplication().invokeLater {
                if (disposed || state.kitRoot != root) return@invokeLater
                describeSummaries = result.getOrNull()?.let { text ->
                    runCatching {
                        com.google.gson.JsonParser.parseString(text).asJsonObject.objects("extensionActions")
                            .mapNotNull { action -> action.str("id")?.let { id -> action.str("summary")?.let { id to it } } }.toMap()
                    }.getOrNull()
                } ?: emptyMap()
                if (describeSummaries.isNotEmpty()) renderExtensions()
            }
        }
    }
    private fun relativeTime(instant: java.time.Instant?): String {
        instant ?: return "未记录"
        val seconds = java.time.Duration.between(instant, java.time.Instant.now()).seconds
        return when {
            seconds < 60 -> "刚刚"
            seconds < 3600 -> "${seconds / 60} 分钟前"
            else -> instant.atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }
    }

    private fun pageHeader(title:String,subtitle:String,actions:JComponent?=null) = U.column(10,U.row(U.label(title,23,bold=true),actions),U.label(subtitle,11,U.muted))
    private fun clickableMetric(caption:String,value:String,sub:String,color:Color=U.text,tooltip:String?=null,action:()->Unit) = U.column(8,U.label(caption,11,U.muted),U.label(value,26,color,true),U.button(sub,true,action).apply { horizontalAlignment=SwingConstants.LEFT;border=JBUI.Borders.empty();font=U.label("",10).font }).apply {
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) action()
            }
        })
        if (tooltip != null) {
            toolTipText = tooltip
            components.forEach { (it as? JComponent)?.toolTipText = tooltip }
        }
    }
    private fun disclosure(title:JComponent,content:JComponent,expanded:Boolean=false):JPanel = U.column().apply {
        val wrapper=U.padded(content,16,22,14,12).apply { isVisible=expanded }
        val toggle=U.button("",true) {}
        toggle.icon=if(expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
        toggle.addActionListener { wrapper.isVisible=!wrapper.isVisible;toggle.icon=if(wrapper.isVisible) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight;revalidate();repaint() }
        U.append(this,U.row(title,toggle));U.append(this,wrapper)
        border=BorderFactory.createCompoundBorder(BottomLine(U.border),JBUI.Borders.empty(15,0))
    }
    private fun twoColumns(main:JPanel,aside:JPanel,width:Int):JPanel = U.panel().apply {
        val mainWrap=U.panel().apply { add(main,BorderLayout.NORTH) }
        val sideWrap=U.panel().apply { add(aside,BorderLayout.NORTH) }
        layout=object:LayoutManager {
            override fun addLayoutComponent(name:String?,component:Component)=Unit
            override fun removeLayoutComponent(component:Component)=Unit
            override fun minimumLayoutSize(parent:Container)=Dimension(0,0)
            override fun preferredLayoutSize(parent:Container):Dimension {
                val full=parent.width.takeIf { it>0 } ?: 1100
                val stacked=full<JBUI.scale(760)
                val side=JBUI.scale(width)
                mainWrap.setSize(if(stacked) full else (full-side-28).coerceAtLeast(0),1);mainWrap.doLayout()
                sideWrap.setSize(if(stacked) full else side,1);sideWrap.doLayout()
                return Dimension(full,if(stacked) main.preferredSize.height+aside.preferredSize.height+24 else maxOf(main.preferredSize.height,aside.preferredSize.height))
            }
            override fun layoutContainer(parent:Container) {
                val stacked=parent.width<JBUI.scale(760);val side=JBUI.scale(width)
                aside.border=if(stacked) JBUI.Borders.emptyTop(24) else BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,1,0,0,U.border),JBUI.Borders.emptyLeft(24))
                val height=main.preferredSize.height
                mainWrap.setBounds(0,0,if(stacked) parent.width else (parent.width-side-28).coerceAtLeast(0),if(stacked) height else parent.height)
                sideWrap.setBounds(if(stacked) 0 else parent.width-side,if(stacked) height else 0,if(stacked) parent.width else side,if(stacked) aside.preferredSize.height+24 else parent.height)
            }
        }
        add(mainWrap);add(sideWrap)
    }
    private fun table(vararg columns:String) = JBTable(object:DefaultTableModel(columns,0){override fun isCellEditable(row:Int,column:Int)=false}).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION);U.table(this);emptyText.text="暂无内容" }
    private fun setRows(table: JTable, rows: List<List<Any?>>) { 
        val model = table.model as DefaultTableModel 
        val currentSelectedRow = table.selectedRow 
        val existingRowCount = model.rowCount 
        val newRowCount = rows.size 
        
        for (i in 0 until maxOf(existingRowCount, newRowCount)) { 
            if (i < newRowCount && i < existingRowCount) { 
                val row = rows[i] 
                var rowChanged = false 
                for (j in row.indices) { 
                    if (model.getValueAt(i, j) != row[j]) { 
                        model.setValueAt(row[j], i, j) 
                        rowChanged = true 
                    } 
                } 
            } else if (i < newRowCount) { 
                model.addRow(rows[i].toTypedArray()) 
            } else { 
                model.removeRow(newRowCount) 
            } 
        } 
        if (currentSelectedRow != -1 && currentSelectedRow < model.rowCount) { 
            table.setRowSelectionInterval(currentSelectedRow, currentSelectedRow) 
        } 
    }

    
    private fun documentFiles(data:JsonObject) = (data.objects("files")+data.objects("artifacts").map { it.deepCopy().apply { addProperty("exists",true) } }).distinctBy { it.str("path") }
    companion object {
        const val SUMMARY = 0
        const val DOCUMENTS = 1
        const val PLAN = 2
        const val CHANGES = 3
        const val REVIEW_CHANGES = 0
        const val VERIFY = 4
        const val WORKFLOW = 5
    }
}

private class HandoffDialog(
    private val project: Project,
    private val data: HandoffData,
) : DialogWrapper(project) {
    private val reader = DocumentReader(project) { }
    private val sourceModel = DefaultListModel<org.agentworkbench.intellij.kit.HandoffSource>()
    private val sourceList = JBList(sourceModel)

    init {
        title = "接手包 · ${data.slug}"
        setOKButtonText("复制")
        setCancelButtonText("关闭")
        data.sources.forEach(sourceModel::addElement)
        sourceList.cellRenderer = ListCellRenderer { list, value, index, selected, focus ->
            DefaultListCellRenderer().getListCellRendererComponent(
                list,
                "${value.kind} · ${value.path}:${value.startLine}",
                index,
                selected,
                focus,
            )
        }
        sourceList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount == 2) openSource()
            }
        })
        init()
        reader.showText(data.content)
    }

    override fun createCenterPanel(): JComponent {
        val sourcePanel = U.panel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(250), JBUI.scale(500))
            add(U.label("来源", 12, bold = true), BorderLayout.NORTH)
            add(U.scroll(sourceList))
            add(
                U.button("打开原文", true) { openSource() }
                    .apply { icon = AllIcons.General.OpenInToolWindow },
                BorderLayout.SOUTH,
            )
        }
        return U.panel(BorderLayout(JBUI.scale(12), 0)).apply {
            preferredSize = Dimension(JBUI.scale(900), JBUI.scale(620))
            add(
                U.label("估算 ${data.estimatedTokens} tokens · 内容按当前文件即时生成", 11, U.muted),
                BorderLayout.NORTH,
            )
            add(reader)
            add(sourcePanel, BorderLayout.EAST)
        }
    }

    override fun doOKAction() {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(data.content), null)
        super.doOKAction()
    }

    private fun openSource() {
        val source = sourceList.selectedValue ?: return
        val opened = if (source.kind == "rule") {
            WorkbenchNavigation.openInEditor(
                project,
                WorkbenchService.getInstance(project).snapshot().kitRoot,
                source.path,
                source.startLine,
            )
        } else {
            WorkbenchNavigation.openFeatureInEditor(
                project, data.slug, source.path, source.startLine
            )
        }
        if (!opened) WorkbenchNotifier.warn(project, "无法打开接手来源", source.path)
    }

    override fun dispose() {
        Disposer.dispose(reader)
        super.dispose()
    }
}

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
import org.agentworkbench.intellij.review.WorkItemReviewService
import org.agentworkbench.intellij.review.ReviewBinding
import org.agentworkbench.intellij.kit.KitClient
import org.agentworkbench.intellij.kit.HandoffData
import org.agentworkbench.intellij.kit.SearchHit
import java.awt.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*
import javax.swing.table.DefaultTableModel
import org.agentworkbench.intellij.ui.WorkbenchUi as U

internal class WorkbenchPanel(private val project: Project) : JPanel(CardLayout()), Disposable {
    private val service = WorkbenchService.getInstance(project)
    private val settings = WorkbenchSettings.getInstance()
    private val scanService = GitScanService.getInstance(project)
    private val reviewService = WorkItemReviewService.getInstance(project)
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
    private var lastWorkItemFilterFingerprint: String? = null
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
    private val lifecycle = JComboBox(arrayOf("未完成", "全部", "待评审", "active", "paused", "cancelled", "done"))
    private val repoFilter = JComboBox(arrayOf("全部仓库"))
    private val sort = JComboBox(arrayOf("最近更新", "名称"))
    private val featureHeader = U.column()
    private val featureOverview = U.column()
    private val featureDashboard = WorkItemDashboardPanel(
        project = project,
        onOpenTask = { locateTask(it) },
        onOpenDoc = { openDocument(it) }
    )
    private val featureReviewPanel = WorkItemReviewPanel(
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
    private val taskTable = table("编号", "状态", "任务", "依赖", "原文位置")
    private val planScrollPane = U.scroll(taskTable)
    private val taskFilter = JComboBox(arrayOf("全部任务", "可继续", "待验证", "等待条件", "已完成"))
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
                WorkbenchNavigation.openWorkItemInEditor(project, slug, path, line)
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
    private val historyStatus = JComboBox(arrayOf("全部状态", "active", "paused", "cancelled", "done"))
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
    private val committedChanges = WorkItemChangesPanel(project)
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
    private var pendingPlanOffset: Pair<String, Int>? = null
    private var planOffset = 0
    private var disposed = false
    private var active = false
    private var route = "overview"
    private var lastWorkspaceRevision: String? = null
    private var documentRevision: String? = null
    private var checkedWorkItemRevision: String? = null
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

        U.append(featuresPage, pageHeader("工作项工作台", "需求与历史记录"))
        lifecycle.renderer = object : DefaultListCellRenderer() { override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component = super.getListCellRendererComponent(list, if(value == "未完成" || value == "全部") value else U.status(value?.toString()), index, selected, focus) }
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
            val plan = value.get("progress").obj(); val done = plan?.str("completed")?.toIntOrNull() ?: 0; val total = plan?.str("total")?.toIntOrNull() ?: 0

            val left = U.column(6, U.flow(*listOfNotNull(U.label(value.str("title") ?: value.str("slug").orEmpty(), 12, U.text, bold = true), U.badge(U.status(value.str("status"))), verificationBadge(value)).toTypedArray()), U.mono("${value.str("slug")}  ·  ${value.objects("repositoryBindings").size} 个仓库  ·  ${value.str("updatedAt")}"))
            val progressLabel = U.label(
                U.planProgress(done, total),
                11,
                U.muted
            ).apply {
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
            override fun mouseClicked(e: java.awt.event.MouseEvent) { if (e.clickCount == 2) openSelectedWorkItem() }
        })
        featureList.registerKeyboardAction({ openSelectedWorkItem() }, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        featureList.registerKeyboardAction({
            featureList.selectedValue?.let { f ->
                val slug = f.str("slug") ?: return@let
                WorkbenchNavigation.openWorkItemInEditor(project, slug)
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
                    menu.add(JMenuItem("打开需求面板").apply { addActionListener { openSelectedWorkItem() } })
                    menu.add(JMenuItem("在编辑器中打开 (F4)").apply {
                        icon = AllIcons.General.OpenInToolWindow
                        addActionListener { WorkbenchNavigation.openWorkItemInEditor(project, slug) }
                    })
                    menu.add(JMenuItem("在项目视图中定位").apply {
                        icon = AllIcons.General.Locate
                        addActionListener { WorkbenchNavigation.locateWorkItemInProjectView(project, slug) }
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
        pages.add(U.page(U.padded(featuresPage) as JPanel), "items")

        val locateTaskBtn = U.button("定位所选任务原文", action = ::locateTask).apply { toolTipText = "在编辑器中查看任务定义" }
        val openTaskInEditorBtn = U.button("在编辑器中打开任务", true) {
            val task = taskSelected() ?: return@button
            locateTaskInEditor(task)
        }.apply { icon = AllIcons.General.OpenInToolWindow; toolTipText = "在主编辑器中打开 implementation.md 并跳转到该任务定义行" }
        val planPanel = U.panel().apply {
            add(U.row(planTitle, U.flow(taskFilter, locateTaskBtn, openTaskInEditorBtn)).apply { border = JBUI.Borders.empty(24,0,16,0) }, BorderLayout.NORTH)
            add(planScrollPane)
        }
        featureChangesTabs.addTab("代码评审", U.page(U.padded(featureReviewPanel) as JPanel))
        featureChangesTabs.addTab("需求分支已提交", committedChanges)
        committedChanges.onLocationChanged = { repo, file -> selectedSlug?.let { slug -> state.kitRoot?.let { root -> settings.itemView(root, slug).apply { repository = repo; this.file = file } } } }
        featureChangesTabs.addTab("当前工作目录", workingGit)

        tabs.addTab("计划", planPanel)
        tabs.addTab("变更", featureChangesTabs)
        tabs.addTab("流程", U.panel().apply { add(U.row(U.flow(U.label("运行记录",11,U.muted), runPicker), runStatus).apply { border = JBUI.Borders.empty(22,0,20,0) }, BorderLayout.NORTH); add(U.page(workflowBody)) })

        val featureNorth = U.column(4, featureHeader, featureDoctorStrip)
        pages.add(U.page(U.padded(U.panel().apply { add(featureNorth, BorderLayout.NORTH); add(tabs) })), "item")
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
        U.append(historyPage, pageHeader("历史检索", "搜索当前工作区的 WorkItem 文档"))
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
            if (tabs.selectedIndex == PLAN) SwingUtilities.invokeLater { applyPlanOffset() }
            remember(); loadVisible()
        } }
        featureChangesTabs.onChange = { if (!changing) {
            if (featureChangesTabs.selectedIndex != REVIEW_CHANGES) { lastReviewKey=null; reviewService.invalidate() }
            remember(); loadVisible()
        } }
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) { filterWorkItems(); remember() }
        })
        lifecycle.addActionListener { filterWorkItems(); remember() }; repoFilter.addActionListener { filterWorkItems() }; sort.addActionListener { filterWorkItems() }
        taskFilter.addActionListener { renderTasks(); remember() }
        planScrollPane.verticalScrollBar.addAdjustmentListener {
            if (pendingPlanOffset != null) applyPlanOffset()
            else if (!changing && tabs.selectedIndex == PLAN && route == "item") {
                planOffset = planScrollPane.verticalScrollBar.value
                remember()
            }
        }
        planScrollPane.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) { applyPlanOffset() }
        })
        taskTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) remember() }
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
            // 详情不再自动读取隐藏的文档概览。
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

    private fun invalidateCode() { ApplicationManager.getApplication().invokeLater { if (!disposed) { checkedWorkItemRevision = null; verificationData = null; if(active) { renderVerification(); debounce.restart() } } } }
    fun setActive(value: Boolean) {
        active = value
        if(value && !disposed) {
            if (snapshotDirty) snapshotDirty = false
            refresh(); refreshTimer.start()
            if (pendingKit.isNotEmpty()) applyKitChanges()
            if(route=="item") restoreWorkItem(); loadVisible()
        }
        else { refreshTimer.stop(); debounce.stop(); kitDebounce.stop() }
    }
    fun navigate(page: String) {
        route = page; (pages.layout as CardLayout).show(pages, page)
        editorTitle.text = when(page) { "item" -> detailData?.get("summary").obj()?.str("title") ?: "工作项工作台"; "items" -> "工作项工作台"; "search" -> "历史检索"; "runs" -> "流程记录"; "extensions" -> "扩展"; "configuration" -> "工作区配置"; "repositories" -> "业务仓库"; "diagnostics" -> "读取诊断"; else -> "" }
        renderSidebar(); loadVisible()
    }
    fun refresh() {
        if(disposed) return
        val next = service.snapshot(); val changedRoot = state.kitRoot != next.kitRoot; state = next
        (layout as? CardLayout)?.show(this, if (state.kitRoot.isNullOrBlank()) "EMPTY" else "CONTENT")
        if(changedRoot) { selectedSlug=null; detailData=null; verificationData=null; workflowData=null; deliveryData=null; runData=null; changing=true;runPicker.removeAllItems();changing=false;runStatus.text="无当前 Run";renderWorkflow(); lastWorkspaceRevision=null; scenes=emptyMap(); doctorRan=false; businessReposExpanded=false; describeLoaded=false; describeSummaries=emptyMap(); lastLocalReqKey=null;lastReviewKey=null;reviewService.invalidate(); pendingKit.clear(); historyModel.clear(); historyCount.text="输入关键词后搜索当前工作区的历史文档"; restore() }
        overviewPath.text=state.kitRoot ?: "在侧栏入口绑定工作流 Kit"
        workspaceName.text = state.workspace?.data.obj()?.get("identity").obj()?.str("name") ?: "研发工作区"
        workspacePath.text = state.kitRoot?.let { Path.of(it).fileName.toString() } ?: "尚未绑定 Kit"
        activeLabel.text = selectedSlug ?: "请选择需求"
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
        renderSidebar(); renderWorkspaceSummary(); filterWorkItems(); renderConfiguration()
        if (state.kitRoot != null && !doctorRan && !runningDoctor) {
            runDoctor()
        }
    }
    fun isGitSnapshotReady() = scenes.size == repositories().size && scenes.isNotEmpty()
    private fun repositories() = state.workspace?.data.obj()?.objects("repositories").orEmpty()
    private fun renderSidebar() {
        nav.removeAll(); nav.background=U.surface; nav.border=JBUI.Borders.empty(0,9)
        U.append(nav, navButton("overview", "工作区总览", AllIcons.Nodes.HomeFolder) { navigate("overview") })
        U.append(nav, navButton("items", "工作项工作台", AllIcons.Actions.ListFiles, state.items.size.toString()) { navigate("items") },2)
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
        horizontalAlignment=SwingConstants.LEFT; foreground=if(route==key || (key=="items"&&route=="item")) U.accent else U.muted
        background=if(foreground==U.accent) U.selection else U.surface; isOpaque=true; isContentAreaFilled=true; border=JBUI.Borders.empty(9,11)
        preferredSize=Dimension(JBUI.scale(204),JBUI.scale(36)); maximumSize=Dimension(Int.MAX_VALUE,JBUI.scale(36)); toolTipText=value; name="nav-$key"
    }
    private fun renderWorkspaceSummary() {
        val values = scenes.values.filterIsInstance<NativeGit.Snapshot.Available>()
        val pending = state.items.count { it.str("status") !in listOf("done","paused") }
        val repoIssues = repoAttention()
        val featIssues = featureAttention()
        val allAttention = attention()

        val unreadable = repositories().any { scenes[it.str("id")] !is NativeGit.Snapshot.Available }
        val summaryFingerprint = buildString {
            append(repositories().size).append(';')
            append(pending).append(';')
            append(values.sumOf { it.changes }).append(';').append(unreadable).append(';')
            append(allAttention.joinToString(",")).append(';')
            state.items.filter { it.str("status") != "done" }.take(5).forEach { append(it).append(';') }
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
            clickableMetric("进行中的 WorkItem", pending.toString(), "查看需求列表 ↗") { navigate("items") },
            clickableMetric("未提交文件", if (unreadable) "—" else values.sumOf { it.changes }.toString(), if (unreadable) "部分仓库读取失败，合计未知 ↗" else "筛选有变更仓库 ↗") { git.filter("changed") },
            clickableMetric("需要关注", allAttention.size.toString(), attentionSub, if (allAttention.isEmpty()) U.text else U.amber, tooltip = attentionTip) {
                if (featIssues.isNotEmpty()) {
                    changing = true
                    lifecycle.selectedItem = "待评审"
                    changing = false
                    navigate("items")
                    filterWorkItems()
                    if (featureModel.size > 0) {
                        featureList.selectedIndex = 0
                    }
                } else if (repoIssues.isNotEmpty()) {
                    git.filter("attention")
                }
            }
        ))
        ongoing.removeAll(); U.append(ongoing, U.section("进行中的 WorkItem", U.button("查看全部  →", true) { navigate("items") }))
        state.items.filter { it.str("status") != "done" }.take(5).forEach { U.append(ongoing, featureRow(it)) }
        if (state.items.isEmpty()) U.append(ongoing, U.empty("暂无需求记录", "需求由你的 Agent 或其他工具推进，工作台只负责查看。"))
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
    private fun featureAttention(): List<String> = state.items.filter {
        it.str("status") != "done" && KitSemantics.reviewPending(it.get("reviews").obj()?.str("plan"))
    }.map { "${it.str("title")} · 计划${it.get("reviews").obj()?.str("plan")}" }

    private fun attention(): List<String> = repoAttention() + featureAttention() + listOfNotNull(state.error)
    private fun renderAttention(target:JPanel, items:List<String>) { target.removeAll(); U.append(target,U.section("当前关注")); if(items.isEmpty()) U.append(target,U.copy("暂无已知阻塞。验证的当前代码适用性需单独核对。")) else items.forEach { U.append(target,U.copy(it),10) }; target.revalidate(); target.repaint() }
    private fun filterWorkItems() {
        if(changing) return
        val query=search.text.trim()
        val statusMatch: (JsonObject) -> Boolean = { feat ->
            when (lifecycle.selectedItem) {
                "未完成" -> feat.str("status") !in setOf("done", "cancelled")
                "全部" -> true
                "待评审" -> KitSemantics.reviewPending(feat.get("reviews").obj()?.str("plan"))
                else -> feat.str("status") == lifecycle.selectedItem
            }
        }
        var rows=state.items.filter { statusMatch(it) && (query.isBlank() || "${it.str("slug")} ${it.str("title")}".contains(query,true)) && (repoFilter.selectedItem=="全部仓库" || it.objects("repositoryBindings").any { b -> b.str("repository")==repoFilter.selectedItem }) }
        rows=if(sort.selectedIndex==0) rows.sortedByDescending { it.str("updatedAt") } else rows.sortedBy { it.str("title") }

        val currentFingerprint = "${lifecycle.selectedItem}:${repoFilter.selectedItem}:${sort.selectedIndex}:$query;" +
            rows.joinToString(";")
        if (currentFingerprint == lastWorkItemFilterFingerprint && featureModel.size == rows.size) {
            return
        }
        lastWorkItemFilterFingerprint = currentFingerprint

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
        featureCount.text="${rows.size} 个需求 · 已完成 ${state.items.count { it.str("status") == "done" }} 个"
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
        if (!WorkbenchNavigation.openWorkItemInEditor(project, hit.slug, hit.path, hit.line)) {
            WorkbenchNotifier.warn(project, "无法打开搜索结果", "${hit.slug}/${hit.path}:${hit.line}")
        }
    }
    private fun copySearchHit(hit: SearchHit) {
        val source = "${hit.slug}/${hit.path}:${hit.line}\n${hit.snippet}"
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(source), null)
    }
    private fun openSelectedWorkItem() { featureList.selectedValue?.str("slug")?.let(::selectWorkItem) }
    private fun paintBackground(component: Component, color: Color) {
        component.background = color
        if (component is Container) component.components.forEach { paintBackground(it, color) }
    }
    private fun featureRow(item:JsonObject): JPanel {
        val plan=item.get("progress").obj();val done=plan?.str("completed")?.toIntOrNull()?:0;val total=plan?.str("total")?.toIntOrNull()?:0

        val title=U.flow(*listOfNotNull(U.button(item.str("title")?:item.str("slug").orEmpty(),true) { selectWorkItem(item.str("slug")!!) }.apply { foreground=U.text;font=U.label("",12,bold=true).font;border=JBUI.Borders.empty(3,0) }, U.badge(U.status(item.str("status"))), verificationBadge(item)).toTypedArray())
        val left=U.column(8,title,U.mono("${item.str("slug")}  ·  ${item.objects("repositoryBindings").size} 个仓库  ·  ${item.str("updatedAt")}"))
        val progressLabel = U.label(
            U.planProgress(done, total),
            11,
            U.muted
        ).apply {
        }
        val progress = if (total > 0) U.column(10, progressLabel, U.progress(done, total)) else U.label("暂无计划", 11, U.faint)
        return U.row(left,progress).apply { border=BorderFactory.createCompoundBorder(BottomLine(U.border),JBUI.Borders.empty(17,0)); name="item-${item.str("slug")}" }
    }
    fun selectWorkItem(slug:String) {
        if (selectedSlug != slug) remember()
        pendingPlanOffset = null
        planOffset = 0
        selectedSlug=slug;selectedDocument=null;checkedWorkItemRevision=null;checkingCode=false;verificationData=null;workflowData=null;deliveryData=null;runData=null;detailData=null;tasks=emptyList();files=emptyList();lastLocalReqKey=null;lastReviewKey=null;reviewService.invalidate()
        changing=true;runPicker.removeAllItems();changing=false
        runStatus.text="无当前 Run"
        verificationBody.removeAll();renderWorkflow()
        featureHeader.removeAll()
        val view = state.kitRoot?.let { settings.itemView(it, slug) }
        changing=true
        tabs.selectedIndex=view?.tab?.coerceIn(0, tabs.tabCount-1) ?: PLAN
        featureChangesTabs.selectedIndex=view?.changeTab?.coerceIn(0, featureChangesTabs.tabCount-1) ?: COMMITTED_CHANGES
        taskFilter.selectedItem = view?.filter?.takeIf { value -> (0 until taskFilter.itemCount).any { taskFilter.getItemAt(it) == value } } ?: "全部任务"
        committedChanges.restoreLocation(view?.repository.orEmpty(), view?.file.orEmpty(), view?.startCommit.orEmpty())
        changing=false;renderTasks();navigate("item")
        service.loadWorkItem(slug) { result ->
            if(disposed||selectedSlug!=slug) return@loadWorkItem
            val data=result.detail?.data.obj()
            if(result.error!=null||data?.get("summary").obj()?.str("slug")!=slug) { notice.text=result.error?:"详情不可用";notice.isVisible=true;return@loadWorkItem }
            state=result;notice.isVisible=false;showWorkItem(data!!)
        }
    }
    private fun showWorkItem(data: JsonObject) {
        detailData = data; val summary = data.get("summary").obj() ?: return
        files = documentFiles(data)
        val related = summary.objects("repositoryBindings").mapNotNull { it.str("repository") }.toSet()
        git.setRelated(related); workingGit.setRelated(related); workingGit.filter("related"); repositoryGit.setRelated(related)
        
        featureHeader.removeAll()
        val slug = summary.str("slug").orEmpty()
        val title = summary.str("title").orEmpty()
        val status = summary.str("status") ?: "development"
        val progression = data.get("progression").obj()
        val currentStage = progression?.str("currentStage") ?: "item.implement"
        tasks = data.objects("tasks")
        renderWorkItemOverview(data)
        val completedCount = tasks.count { it.get("completed")?.asBoolean == true }
        val totalCount = tasks.size

        // Line 1: Breadcrumb + Copy Prompt Button
        val breadcrumb = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            val fBtn = JButton("工作项").apply {
                isBorderPainted = false
                isContentAreaFilled = false
                font = font.deriveFont(11.5f)
                foreground = U.muted
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { navigate("items") }
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
            val documents = listOf(
                "requirements.md", "design.md", "plan.md", "change.md", "README.md"
            )
            val featureDir = WorkbenchNavigation.resolveWorkItemDir(project, slug)
            val documentMenu = JPopupMenu().apply {
                documents.filter { path -> featureDir?.resolve(path)?.let(Files::isRegularFile) == true }.forEach { path ->
                    add(JMenuItem(path).apply {
                        actionCommand = path
                        addActionListener { WorkbenchNavigation.openWorkItemInEditor(project, slug, path) }
                    })
                }
            }
            val openDocBtn = U.button("查看文档 (F4)", true) {
                documentMenu.show(this, 0, height)
            }.apply {
                icon = AllIcons.Actions.Preview
                componentPopupMenu = documentMenu
                isEnabled = documentMenu.componentCount > 0
            }
            val locateBtn = U.button("在工程中定位", true) {
                WorkbenchNavigation.locateWorkItemInProjectView(project, slug)
            }.apply {
                icon = AllIcons.General.Locate
            }
            val refreshBtn = U.button("刷新") { reload() }.apply {
                icon = AllIcons.Actions.Refresh
            }
            val completionBlocker = WorkbenchService.completionBlocker(summary)
            if (status == "active") add(U.button("标记完成") { completeWorkItem(slug, completedCount, totalCount) }.apply {
                icon = AllIcons.Vcs.CommitNode
                toolTipText = completionBlocker ?: "确认验收完成，并由 Kit 核对验证与验收后标记完成"
                isEnabled = completionBlocker == null
            })
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

        summary.get("cancellation").obj()?.str("reason")?.let { reason ->
            featureHeader.add(U.copy("取消原因：$reason"))
        }
        summary.objects("executionBlockers").filter { it.str("status") == "open" }.forEach { blocker ->
            featureHeader.add(U.copy("${blocker.str("reason")} · ${blocker.str("owner")} · 解除条件：${blocker.str("condition")}"))
        }
        featureHeader.add(line1)
        featureHeader.add(line2)
        featureHeader.add(line3)
        progression?.objects("nextActions").orEmpty().takeIf { it.isNotEmpty() }?.let { featureHeader.add(nextActionsRow(it)) }
        featureHeader.border = JBUI.Borders.empty(0, 0, 8, 0)

        tabs.setTitleAt(PLAN, "计划")
        renderTasks()
        featureHeader.revalidate(); featureHeader.repaint(); editorTitle.text = title; loadVisible()
    }

    private fun completeWorkItem(slug: String, completed: Int, total: Int) {
        val progress = if (total > 0) "当前计划进度为 $completed/$total。\n\n" else ""
        if (com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                "${progress}确认该需求已验收完成？Kit 将再次核对当前验证与外部验收状态。",
                "标记 WorkItem 完成",
                com.intellij.openapi.ui.Messages.getQuestionIcon(),
            ) != com.intellij.openapi.ui.Messages.YES
        ) return
        service.completeWorkItem(slug) { result ->
            result.fold(
                onSuccess = {
                    WorkbenchNotifier.info(project, "WorkItem 已完成", slug)
                    lifecycle.selectedItem = "未完成"
                    navigate("items")
                    reload()
                },
                onFailure = { WorkbenchNotifier.warn(project, "标记完成失败", it.message ?: "未知错误") },
            )
        }
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

    private fun renderWorkItemOverview(data: JsonObject) {        val summary = data.get("summary").obj() ?: return
        val slug = summary.str("slug").orEmpty()
        val fDir = summary.str("path").orEmpty()
        val root = state.kitRoot
        val repoRoots = repositoryRoots(repositories())

        val reqFileMeta = files.firstOrNull { it.str("role") == "requirements" }
            ?: files.firstOrNull { it.str("role") == "change" }
            ?: return
        val reqPath = reqFileMeta.str("path") ?: return
        featureDashboard.update(
            featureData = data, tasks = tasks, repositoryBindings = summary.objects("repositoryBindings"),
            repoRoots = repoRoots, requirementsContent = null, requirementsPath = reqPath, scenes = scenes,
            onNavigateTab = { tabIndex -> tabs.selectedIndex = tabIndex }
        )

        service.loadDocument(slug, reqPath, reqFileMeta?.str("documentRevision")) { docSnapshot ->
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
        if (route != "item" || tabs.selectedIndex != CHANGES || featureChangesTabs.selectedIndex != REVIEW_CHANGES || slug.isBlank()) return
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

    private fun taskStatus(task: JsonObject): String = if (task.get("executionBlocked")?.asBoolean == true) "等待条件" else when (task.str("status")) {
        "completed" -> "已完成"
        "failed", "changed", "blocked" -> "待验证"
        else -> if (task.getAsJsonArray("dependencies")?.any { dependency ->
            tasks.none { it.str("id") == dependency.asString && it.str("status") == "completed" }
        } == true) "等待条件" else "可继续"
    }

    private fun renderTasks() {
        displayedTasks = tasks.filter { taskFilter.selectedIndex == 0 || taskStatus(it) == taskFilter.selectedItem }
        val totalCount = tasks.size
        val completedCount = tasks.count { it.str("status") == "completed" }
        val progressDesc = "$completedCount/$totalCount 项完成"
        planTitle.text = "实施计划 · $progressDesc"

        val selectedTask = taskSelected()?.str("id") ?: selectedSlug?.let { slug -> state.kitRoot?.let { settings.itemView(it, slug).task } }
        setRows(taskTable, displayedTasks.map {
            val statusStr = taskStatus(it)
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
        val selectedRow = displayedTasks.indexOfFirst { it.str("id") == selectedTask }
        if (selectedRow >= 0) taskTable.selectionModel.setSelectionInterval(selectedRow, selectedRow)
        selectedSlug?.let(::restorePlanOffset)
    }
    private fun restorePlanOffset(slug: String) {
        val root = state.kitRoot ?: return
        planOffset = settings.itemView(root, slug).offset.coerceAtLeast(0)
        pendingPlanOffset = if (planOffset > 0) slug to planOffset else null
        SwingUtilities.invokeLater { applyPlanOffset() }
    }
    private fun applyPlanOffset() {
        val (slug, offset) = pendingPlanOffset ?: return
        if (disposed || selectedSlug != slug) { pendingPlanOffset = null; return }
        val bar = planScrollPane.verticalScrollBar
        if (planScrollPane.viewport.extentSize.height <= 0 || taskTable.height <= 0) return
        pendingPlanOffset = null
        changing = true
        try { bar.value = offset } finally { changing = false }
    }
    private fun showDocument(path: String, line: Int? = null) {
        changing = true
        changing = false
        selectedSlug?.let { WorkbenchNavigation.openWorkItemInEditor(project, it, path, line) }
    }
    private fun locateTask(task: JsonObject) {
        val path = task.str("path") ?: return
        changing = true
        changing = false
        selectedSlug?.let { WorkbenchNavigation.openWorkItemInEditor(project, it, path, task.str("startLine")?.toIntOrNull()) }
    }
    private fun openDocument(path: String, line: Int? = null, retried: Boolean = false, taskKey: String? = null, anchor: String? = null) {
        val slug = selectedSlug ?: return
        val metadata = files.firstOrNull { it.str("path") == path } ?: return
        val revision = metadata.str("documentRevision")
        selectedDocument = path
        remember()
        docPanel.selectDocument(path, fireEvent = false)
        documentStatus.text = "正在读取 $path"
        service.loadDocument(slug, path, revision) { updated ->
            if (disposed || selectedSlug != slug || selectedDocument != path) return@loadDocument
            val doc = updated.document?.data.obj()
            if (doc == null || doc.str("slug") != slug || doc.str("path") != path || updated.error != null) {
                if (!retried) service.loadWorkItem(slug) { fresh ->
                    if (!disposed && selectedSlug == slug) {
                        fresh.detail?.data.obj()?.let { files = documentFiles(it); tasks = it.objects("tasks") }
                        val relocated = taskKey?.let { key -> tasks.filter { (it.str("id") ?: it.str("title")) == key }.singleOrNull()?.str("startLine")?.toIntOrNull() }
                        openDocument(path, if (taskKey != null) relocated else line, true, taskKey, anchor)
                    }
                } else documentStatus.text = updated.error ?: "文档持续变化，保留上次内容"
                return@loadDocument
            }
            documentRevision = doc.str("documentRevision")
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
        val path = task.str("path") ?: "plan.md"
        val line = task.str("startLine")?.toIntOrNull()
        WorkbenchNavigation.openWorkItemInEditor(project, slug, path, line)
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
            service.loadWorkItem(slug) { result ->
                if (disposed || selectedSlug != origin || result.kitRoot != root) return@loadWorkItem
                if (result.error != null) { documentStatus.text = "链接目标需求不可读取：$slug"; return@loadWorkItem }
                val data = result.detail?.data.obj()?.takeIf { it.get("summary").obj()?.str("slug") == slug } ?: run { documentStatus.text = "链接目标需求不可读取：$slug"; return@loadWorkItem }
                selectedSlug = slug; selectedDocument = relative.subpath(1, relative.nameCount).toString(); state = result
                showWorkItem(data); navigate("item"); WorkbenchNavigation.openWorkItemInEditor(project, slug, path)
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
            if(disposed||selectedSlug!=slug||route!="item"||tabs.selectedIndex!=WORKFLOW) return@loadVerification
            if(checkCode) checkingCode=false
            if(result.error!=null) { notice.text="验证读取失败：${result.error}";notice.isVisible=true;renderVerification();return@loadVerification }
            verificationData=result.verification?.data.obj()?.takeIf { it.str("slug")==slug }?:return@loadVerification
            renderVerification()
            val revision=verificationData?.str("stateRevision")
            if(checkCode) checkedWorkItemRevision=revision
        }
    }
    private fun renderVerification() {
        verificationBody.removeAll()
        verificationBody.border = JBUI.Borders.empty(26, 0, 20, 0)
        val data = verificationData
        val record = data?.get("record").obj()
        U.append(verificationBody, U.metrics(
            U.metric("上次检查结果", U.state(data?.str("recordedResult")), record?.str("recordedAt") ?: "尚无验证", U.stateColor(data?.str("recordedResult")), true),
            U.metric("当前代码适用性", U.state(data?.str("applicability")), if (checkingCode) "正在核对…" else "记录通过不代表当前代码已核对", U.stateColor(data?.str("applicability")), true)))
        val document = files.firstOrNull { it.str("role") == "verification" }?.str("path")
        U.append(verificationBody, U.section("检查证据", U.flow(
            U.button("查看验证摘要") { document?.let(::showDocument) }.apply { isEnabled = document != null },
            U.button("核对当前代码") { queryVerification(true) })), 24)
        record?.objects("checks")?.forEachIndexed { index, check ->
            U.append(verificationBody, U.column(6,
                U.label("检查 ${index + 1} · 退出 ${check.str("exitStatus")}", 12, bold = true),
                U.copy(check.str("result").orEmpty()), U.mono(check.str("command").orEmpty())), 10)
        }
        if (record == null) U.append(verificationBody, U.empty("尚无验证记录", "工作台只读取实际证据，不执行测试。"))
        data?.objects("repositoryStates")?.forEach { row ->
            U.append(verificationBody, U.label("${row.str("repository")}：${U.state(row.str("state"))}", 12), 8)
        }
        data?.objects("pendingExternalChecks")?.forEach { row ->
            U.append(verificationBody, U.copy("${row.str("requirement")} · ${row.str("description")} · ${row.str("owner")} · ${row.str("status")}"), 8)
        }
        verificationBody.revalidate(); verificationBody.repaint()
    }
    private fun queryWorkflow() {
        val featureFilter = selectedSlug.takeIf { route == "item" }
        if (featureFilter == null) service.loadWorkflow { result ->
            if(disposed) return@loadWorkflow
            if(result.error!=null) return@loadWorkflow
            workflowData=result.workflow?.data.obj();renderExtensions();renderSidebar()
        }
        // item 上下文按需求由 Kit 服务端过滤（inspect runs --item）；runs 全局页拉全量。
        service.loadRuns(featureFilter) { result ->
            if(disposed || (featureFilter != null && (selectedSlug != featureFilter || route != "item" || tabs.selectedIndex != WORKFLOW)) || (featureFilter == null && route !in listOf("runs", "extensions"))) return@loadRuns
            if(result.error!=null) { runStatus.text="流程记录读取失败";return@loadRuns }
            val previous=runPicker.selectedItem ?: state.kitRoot?.let { settings.preference(it).run.takeIf(String::isNotBlank) }
            changing=true;runPicker.removeAllItems()
            val records=result.runs?.data.obj()?.objects("items").orEmpty()
            val pickerRecords = if (featureFilter != null) records.filter { it.str("itemSlug") == featureFilter } else emptyList()
            pickerRecords.mapNotNull { it.str("id") }.forEach(runPicker::addItem)
            if(previous!=null&&(0 until runPicker.itemCount).any { runPicker.getItemAt(it)==previous }) runPicker.selectedItem=previous
            changing=false
            if(featureFilter == null) renderGlobalRuns(records, result.runs?.data.obj()?.get("counts").obj())
            if(featureFilter != null) {
                if(runPicker.itemCount>0) loadRun() else { runData=null;runStatus.text="无当前 Run";renderWorkflow() }
            }
        }
    }
    private fun loadRun() { val id=runPicker.selectedItem?.toString()?:return; runData=null;runStatus.text="正在读取 Run…";renderWorkflow();readRun(id, selectedSlug.takeIf { route == "item" }) }
    private fun readRun(id:String, itemSlug:String? = null) {
        service.loadRun(id) { result ->
            if(disposed || (itemSlug != null && (selectedSlug != itemSlug || route != "item" || tabs.selectedIndex != WORKFLOW || runPicker.selectedItem != id)) || (itemSlug == null && route != "runs")) return@loadRun
            if(result.error!=null) { runStatus.text="Run 读取失败";return@loadRun }
            runData=result.run?.data.obj()?.takeIf { it.str("id")==id && (itemSlug == null || it.str("itemSlug")==itemSlug) }
            if(itemSlug != null) runStatus.text=if(runData==null) "无当前 Run" else "当前 Run：$id"
            renderWorkflow()
            if(route=="runs") showRunDetail()
        }
    }
    private var deliveryData: JsonObject? = null
    private fun renderWorkflow() {
        workflowBody.removeAll();val data=workflowData
        U.append(workflowBody, U.section("交付事实"), 12)
        val delivery = deliveryData
        delivery?.get("repositories").obj()?.entrySet()?.forEach { (repository, value) ->
            val row = value.asJsonObject
            U.append(workflowBody, U.label("$repository · 版本 ${row.str("version") ?: "未记录"} · 提测 ${row.str("submission") ?: "未执行"} · 部署 ${row.str("deployment") ?: "未确认"} · 验收 ${row.str("acceptance") ?: "未确认"}", 11, U.muted), 6)
        }
        delivery?.objects("externalChecks")?.forEach { row ->
            U.append(workflowBody, U.copy("${row.str("description")} · ${row.str("owner")} · ${row.str("status")}"), 6)
        }
        U.append(workflowBody, verificationBody, 12)
        U.append(workflowBody,U.row(U.flow(U.badge("当前阶段建议"),U.label(U.stage(detailData?.get("progression").obj()?.str("currentStage")),11)),U.label("阶段建议不代表真实部署或验收",10,U.faint)).apply { border=BorderFactory.createCompoundBorder(BottomLine(U.border),JBUI.Borders.empty(0,0,18,0)) })
        if(data==null) { U.append(workflowBody,U.empty("流程待读取","打开流程页后读取当前配置。"));workflowBody.revalidate();workflowBody.repaint();return }
        if(route=="item" && tabs.selectedIndex==WORKFLOW && runData==null && workflowData?.get("enabled")?.asBoolean == true) U.append(workflowBody,U.label("无当前 Run",11,U.muted),10)
        if(data.get("enabled")?.asBoolean == false) { workflowBody.revalidate(); workflowBody.repaint(); return }
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
        if(runData!=null) U.append(workflowBody,U.button("查看 Action 执行尝试",true) { showRunDetail();navigate("runs") },24)
        workflowBody.revalidate();workflowBody.repaint()
    }
    private fun renderGlobalRuns(records:List<JsonObject>, counts:JsonObject?=null) {
        val parsedTotal = counts?.str("parsed")?.toIntOrNull() ?: records.size
        globalRuns.removeAll();U.append(globalRuns,pageHeader("流程记录","现有运行摘要 · 共 $parsedTotal 条 · 不代表完整操作历史",U.button("刷新") { queryWorkflow() }.apply { icon = AllIcons.Actions.Refresh }))
        records.forEach { record ->
            val title=state.items.firstOrNull { it.str("slug")==record.str("itemSlug") }?.str("title")?:record.str("itemSlug")?:record.str("id").orEmpty()
            U.append(globalRuns,U.row(U.column(9,U.button(title,true) { readRun(record.str("id")!!) },U.mono("${record.str("id")}  ·  ${record.str("updatedAt")?:"未记录时间"}")),U.label("${record.get("recordCounts").obj()?.str("total")?:"0"} 个步骤  →",11,U.muted)).apply { border=BorderFactory.createCompoundBorder(BottomLine(U.border),JBUI.Borders.empty(22,0)) },12)
        }
        if(records.isEmpty()) U.append(globalRuns,U.empty("暂无流程记录","此工作区尚未保存 Run。"),24)
        globalRuns.revalidate();globalRuns.repaint()
    }
    private fun showRunDetail() {
        val record=runData?:return
        globalRuns.removeAll();U.append(globalRuns,pageHeader(record.str("id").orEmpty(),"来源：${record.str("source")}",U.button("← 所有流程记录") { queryWorkflow() }))
        record.objects("records").forEach { step -> U.append(globalRuns,disclosure(U.row(U.label(step.str("stage").orEmpty(),13,bold=true),U.badge(U.state(step.str("status")),U.stateColor(step.str("status")))),U.column(10,*listOfNotNull<JComponent>(U.copy(step.str("summary").orEmpty()),U.label("配置：${U.state(step.get("configurationMatch").obj()?.str("state"))}",11,U.muted),step.get("configurationMatch").obj()?.get("reasonCodes").texts().takeIf(String::isNotBlank)?.let { U.mono("原因码：$it") },U.mono(step.str("updatedAt").orEmpty())).toTypedArray())),18) }
        U.append(globalRuns,disclosure(U.label("Action 执行尝试",13),U.copy(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(record.get("attempts")))) ,20)
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
        val summary = item.get("verification").obj() ?: return null
        if (summary.str("evidenceId").isNullOrBlank()) return null
        return when (summary.str("recordedResult")) {
            "passed" -> U.badge("上次验证通过", U.green)
            "failed" -> U.badge("上次验证失败", U.red)
            else -> U.badge("有验证记录", U.muted)
        }
    }
    private fun loadVisible() {
        if(!active||disposed) return
        when(route) {
            "runs" -> { showWorkflowLoading(); queryWorkflow() }
            "extensions" -> { showWorkflowLoading(); queryWorkflow(); runDescribe() }
            "diagnostics" -> runDoctor()
            "item" -> when(tabs.selectedIndex) {
                CHANGES -> selectedSlug?.let { slug -> service.loadProjection(slug, "change") { result ->
                    if (disposed || selectedSlug != slug) return@loadProjection
                    if (result.error != null) { notice.text = result.error; notice.isVisible = true; return@loadProjection }
                    val change = result.change?.data.obj() ?: return@loadProjection
                    val summary = detailData?.get("summary").obj()?.deepCopy() ?: return@loadProjection
                    summary.add("repositoryBindings", change.get("repositories"))
                    committedChanges.showWorkItem(com.google.gson.JsonObject().apply { add("summary", summary) }, repositories())
                    if (featureChangesTabs.selectedIndex == REVIEW_CHANGES) refreshReviews(slug, summary, repositoryRoots(repositories()))
                    notice.isVisible = false
                } }
                WORKFLOW -> selectedSlug?.let { slug ->
                    runStatus.text = "正在读取当前 WorkItem 流程…"
                    service.loadProjection(slug, "flow") { result ->
                        if (disposed || selectedSlug != slug || route != "item" || tabs.selectedIndex != WORKFLOW) return@loadProjection
                        if (result.error != null) { runStatus.text = result.error; return@loadProjection }
                        val flow = result.flow?.data.obj() ?: return@loadProjection
                        workflowData = flow.get("workflow").obj()
                        deliveryData = flow.get("delivery").obj()
                        renderWorkflow()
                        queryVerification()
                        queryWorkflow()
                    }
                }
            }
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
        if(route=="item") restoreWorkItem()
    }
    private fun remember() { if(!changing) state.kitRoot?.let { root ->
        settings.preference(root).apply { query=search.text;status=lifecycle.selectedItem.toString();item=selectedSlug.orEmpty();document=selectedDocument.orEmpty();run=runPicker.selectedItem?.toString().orEmpty() }
        selectedSlug?.let { slug -> settings.itemView(root, slug).apply {
            tab=tabs.selectedIndex;changeTab=featureChangesTabs.selectedIndex;filter=taskFilter.selectedItem?.toString().orEmpty()
            task=taskSelected()?.str("id") ?: task;startCommit=committedChanges.selectedStartCommit()
            if (pendingPlanOffset?.first != slug && tabs.selectedIndex == PLAN && route == "item") offset=planOffset
        } }
    } }
    private fun restore() { state.kitRoot?.let { root -> settings.preference(root).let { saved -> changing=true;search.text=saved.query;lifecycle.selectedItem=settings.itemStatus(root);selectedSlug=saved.item.takeIf(String::isNotBlank);selectedDocument=saved.document.takeIf(String::isNotBlank);selectedSlug?.let { slug -> val view=settings.itemView(root,slug);tabs.selectedIndex=view.tab.coerceIn(0,tabs.tabCount-1);featureChangesTabs.selectedIndex=view.changeTab.coerceIn(0,featureChangesTabs.tabCount-1);taskFilter.selectedItem=view.filter.takeIf { value -> (0 until taskFilter.itemCount).any { taskFilter.getItemAt(it) == value } } ?: "全部任务";committedChanges.restoreLocation(view.repository,view.file,view.startCommit) };changing=false } } }
    private fun restoreWorkItem() { selectedSlug?.let { slug -> service.loadWorkItem(slug) { latest -> if(!disposed&&selectedSlug==slug) {
        if(latest.error!=null) { notice.text="读取失败：${latest.error}，保留上次成功内容";notice.isVisible=true }
        else latest.detail?.data.obj()?.takeIf { it.get("summary").obj()?.str("slug")==slug }?.let { state=latest;showWorkItem(it) }
    } } } }
    override fun dispose() { disposed=true;refreshTimer.stop();debounce.stop();kitDebounce.stop() }

    /** 把 .workspace 下的文件变化映射到需要重查的具体数据类别，去抖后按需刷新。 */
    private fun classifyKitChanges(events: List<VFileEvent>) {
        val kit = state.kitRoot ?: return
        var touched = false
        events.forEach { event ->
            val changes = classifyWorkItemChange(kit, event.path, selectedSlug)
            if (changes.isNotEmpty()) { touched = true; pendingKit.addAll(changes) }
        }
        if (touched && active) kitDebounce.restart()
    }
    private fun applyKitChanges() {
        if (disposed || !active) return
        val changes = pendingKit.toSet(); pendingKit.clear()
        if (changes.isEmpty()) return
        if ("workspace" in changes || "items" in changes) state.kitRoot?.let { r -> state.python?.let { p -> service.bind(r, p) { refresh() } } }
        if ("item" in changes && route == "item") restoreWorkItem()
        if ("workflow" in changes && route in listOf("runs", "extensions")) queryWorkflow()
        if ("workflow" in changes && route == "item" && tabs.selectedIndex == WORKFLOW) loadVisible()
    }
    private fun runDoctor(force: Boolean = false) {
        val root = state.kitRoot ?: return
        val python = state.python ?: return
        if (runningDoctor || (doctorRan && !force)) return
        runningDoctor = true; doctorRan = true
        updateWorkItemDoctor("环境检查中…", U.muted, AllIcons.General.Information)
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
                updateWorkItemDoctor("环境状态不可用", U.amber, AllIcons.General.Warning)
            } else {
                val summary = parsed.get("summary").obj()
                val errors = summary?.str("errors")?.toIntOrNull() ?: 0
                val warnings = summary?.str("warnings")?.toIntOrNull() ?: 0
                val info = summary?.str("info")?.toIntOrNull() ?: 0
                U.append(doctorBody, U.label("错误 $errors · 警告 $warnings · 提示 $info", 12, U.muted))
                val findings = parsed.objects("findings")
                if (findings.isEmpty()) {
                    U.append(doctorBody, U.label("✓ 未发现问题", 12, U.green), 12)
                    updateWorkItemDoctor("环境正常", U.green, AllIcons.General.InspectionsOK)
                } else {
                    val hasError = errors > 0
                    val bannerIcon = if (hasError) AllIcons.General.Error else AllIcons.General.Warning
                    val bannerBg = if (hasError) com.intellij.ui.JBColor(0xFDF2F2, 0x362224) else com.intellij.ui.JBColor(0xFFFBEB, 0x382F19)
                    val bannerBorderColor = if (hasError) com.intellij.ui.JBColor(0xF8B4B4, 0x5C2B2F) else com.intellij.ui.JBColor(0xFCE96A, 0x614F18)
                    val bannerText = if (hasError) "工作区存在 $errors 项异常问题需处理" else "工作区存在 $warnings 项警告建议优化"
                    updateWorkItemDoctor(if (hasError) "$errors 项环境异常" else "$warnings 项环境警告", if (hasError) U.red else U.amber, bannerIcon)
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
            updateWorkItemDoctor("环境检查失败", U.amber, AllIcons.General.Warning, msg)
        })
        doctorBody.revalidate(); doctorBody.repaint()
        overviewDoctorBanner.revalidate(); overviewDoctorBanner.repaint()
    }

    private fun updateWorkItemDoctor(value: String, color: Color, icon: Icon, detail: String? = null) {
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

    
    private fun documentFiles(data:JsonObject) = data.objects("documents").map { it.deepCopy().apply { addProperty("exists", true) } }
    companion object {
        const val PLAN = 0
        const val CHANGES = 1
        const val REVIEW_CHANGES = 0
        const val COMMITTED_CHANGES = 1
        const val WORKFLOW = 2
    }
}

/** Shared path classification for current workspace and Kit maintenance items. */
internal fun classifyWorkItemChange(kit: String, path: String, selectedSlug: String?): Set<String> {
    val itemRoot = listOf("$kit/.workspace/items/", "$kit/docs/development/items/").firstOrNull(path::startsWith)
    if (itemRoot != null) {
        val slug = path.removePrefix(itemRoot).substringBefore('/')
        return if (slug == selectedSlug) setOf("items", "item") else setOf("items")
    }
    val prefix = "$kit/.workspace/"
    if (!path.startsWith(prefix)) return emptySet()
    val relative = path.removePrefix(prefix)
    return if (relative.startsWith("runs/") || relative == "workflow.json" || relative.startsWith("extensions/")) setOf("workflow") else setOf("workspace")
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
            WorkbenchNavigation.openWorkItemInEditor(
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

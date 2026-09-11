package org.agentworkbench.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.google.gson.JsonObject
import org.agentworkbench.intellij.git.NativeGit
import org.agentworkbench.intellij.ui.WorkbenchUi as U
import java.awt.*
import java.nio.file.Path
import javax.swing.*
import javax.swing.event.HyperlinkEvent

/**
 * 需求工作台总览仪表盘（Executive Dashboard）
 * - 真实数据驱动：左侧渲染真实 requirements.md 内容，右侧渲染真实任务进度与已登记代码仓库/变更
 * - 可折叠收起：任务拆解与关联代码仓库默认收起，点击一键平滑展开
 * - 高密度紧凑：每个子任务单行排版，杜绝纵向拉伸与无用空白
 */
internal class FeatureDashboardPanel(
    private val project: Project,
    private val onOpenTask: (JsonObject) -> Unit,
    private val onOpenDoc: (String) -> Unit
) : JPanel(BorderLayout()), Disposable {

    private var currentSlug: String = ""
    private var currentReqPath: String = "requirements/requirements.md"
    private var currentReqContent: String = ""

    private val reqDocTitle = JLabel("requirements.md").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor(0x6B7280, 0x9CA3AF)
    }

    private val scopeLbl = JLabel("").apply {
        font = font.deriveFont(11f)
        foreground = JBColor(0x6B7280, 0x9CA3AF)
    }

    private val reqEditorPane = JEditorPane("text/html", "").apply {
        isEditable = false
        isOpaque = true
        background = U.bg
        foreground = U.text
        border = JBUI.Borders.empty(8, 12, 16, 12)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                e.description?.let(onOpenDoc)
            }
        }
    }

    private val leftScroll = JBScrollPane(reqEditorPane).apply {
        border = BorderFactory.createEmptyBorder()
        viewport.isOpaque = true
        viewport.background = U.bg
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = JBUI.scale(16)
    }

    private val tasksContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val reposContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val tasksHeaderLabel = JLabel("任务拆解").apply {
        font = font.deriveFont(Font.BOLD, 12.5f)
        foreground = JBColor(0x1F2937, 0xF3F4F6)
    }

    private val taskProgressLabel = JLabel("").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = JBColor(0x059669, 0x34D399)
    }

    private val taskProgressBar = JProgressBar(0, 100).apply {
        isStringPainted = false
        preferredSize = Dimension(JBUI.scale(90), JBUI.scale(5))
        maximumSize = Dimension(JBUI.scale(120), JBUI.scale(5))
        foreground = JBColor(0x10B981, 0x34D399)
        background = JBColor(0xE5E7EB, 0x374151)
        border = BorderFactory.createEmptyBorder()
    }

    private val reposHeaderLabel = JLabel("关联代码仓与变更").apply {
        font = font.deriveFont(Font.BOLD, 12.5f)
        foreground = JBColor(0x1F2937, 0xF3F4F6)
    }

    private val reposCountLabel = JLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor(0x6B7280, 0x9CA3AF)
    }

    private var lastTasksFingerprint: String? = null
    private var lastReposFingerprint: String? = null

    private var onNavigateToTab: ((Int) -> Unit)? = null

    // 折叠状态（默认同屏展开展示！）
    private var tasksExpanded = true
    private var reposExpanded = true

    private val tasksToggleBtn = JButton("收起", AllIcons.General.ArrowDown).apply {
        font = font.deriveFont(10.5f)
        foreground = JBColor(0x2563EB, 0x60A5FA)
        isBorderPainted = false
        isContentAreaFilled = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val reposToggleBtn = JButton("收起", AllIcons.General.ArrowDown).apply {
        font = font.deriveFont(10.5f)
        foreground = JBColor(0x2563EB, 0x60A5FA)
        isBorderPainted = false
        isContentAreaFilled = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val rightTasksCard: JPanel
    private val rightReposCard: JPanel
    private val tasksScroll: JBScrollPane
    private val reposScroll: JBScrollPane

    init {
        isOpaque = false

        // 构建左侧：需求概述卡片
        val leftCard = createCardPanel().apply {
            layout = BorderLayout()
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0xE5E7EB, 0x393B40), 1),
                JBUI.Borders.empty(10, 12)
            )

            // 头部：需求概述 · requirements.md + 在编辑器中打开
            val leftHeader = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(6)

                val titleBox = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    val title = JLabel("需求概述").apply {
                        font = font.deriveFont(Font.BOLD, 13.5f)
                        foreground = JBColor(0x1F2937, 0xF3F4F6)
                    }
                    add(title)
                    add(reqDocTitle)
                    add(scopeLbl)
                }

                val openDocBtn = JButton("在编辑器打开 (F4)").apply {
                    font = font.deriveFont(11f)
                    foreground = JBColor(0x2563EB, 0x60A5FA)
                    isBorderPainted = false
                    isContentAreaFilled = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener { onOpenDoc(currentReqPath) }
                }

                add(titleBox, BorderLayout.WEST)
                add(openDocBtn, BorderLayout.EAST)
            }

            // 底部：路径锚点
            val leftFooter = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(6)
                val icon = JLabel(AllIcons.Actions.Preview)
                val pathBtn = JButton(currentReqPath).apply {
                    font = font.deriveFont(Font.PLAIN, 10.5f)
                    foreground = JBColor(0x4B5563, 0x9CA3AF)
                    isBorderPainted = false
                    isContentAreaFilled = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener { onOpenDoc(currentReqPath) }
                }
                add(icon)
                add(pathBtn)
            }

            add(leftHeader, BorderLayout.NORTH)
            add(leftScroll, BorderLayout.CENTER)
            add(leftFooter, BorderLayout.SOUTH)
        }

        // 构建右侧：可折叠任务拆解卡片
        tasksScroll = JBScrollPane(tasksContainer).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.isOpaque = true
            viewport.background = U.surface
            background = U.surface
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(12)
            isVisible = true // 默认同屏展开！
            preferredSize = Dimension(JBUI.scale(260), JBUI.scale(220))
        }

        rightTasksCard = createCardPanel().apply {
            layout = BorderLayout()
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0xE5E7EB, 0x393B40), 1),
                JBUI.Borders.empty(8, 10)
            )

            val tasksHeader = JPanel(BorderLayout()).apply {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                val leftBox = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(tasksHeaderLabel)
                    add(taskProgressBar)
                    add(taskProgressLabel)
                }

                val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                    isOpaque = false
                    val viewAllBtn = JButton("查看全部 →").apply {
                        font = font.deriveFont(10.5f)
                        foreground = JBColor(0x6B7280, 0x9CA3AF)
                        isBorderPainted = false
                        isContentAreaFilled = false
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        addActionListener { onNavigateToTab?.invoke(2) } // PLAN tab
                    }
                    add(viewAllBtn)
                    add(tasksToggleBtn)
                }

                add(leftBox, BorderLayout.WEST)
                add(rightActions, BorderLayout.EAST)

                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        toggleTasks()
                    }
                })
            }

            tasksToggleBtn.addActionListener { toggleTasks() }

            add(tasksHeader, BorderLayout.NORTH)
            add(tasksScroll, BorderLayout.CENTER)
        }

        // 构建右侧：可折叠关联代码仓卡片
        reposScroll = JBScrollPane(reposContainer).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.isOpaque = true
            viewport.background = U.surface
            background = U.surface
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(12)
            isVisible = true // 默认同屏展开！
            preferredSize = Dimension(JBUI.scale(260), JBUI.scale(180))
        }

        rightReposCard = createCardPanel().apply {
            layout = BorderLayout()
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0xE5E7EB, 0x393B40), 1),
                JBUI.Borders.empty(8, 10)
            )

            val reposHeader = JPanel(BorderLayout()).apply {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                val leftBox = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(reposHeaderLabel)
                    add(reposCountLabel)
                }

                val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                    isOpaque = false
                    val viewDiffBtn = JButton("查看变更 →").apply {
                        font = font.deriveFont(10.5f)
                        foreground = JBColor(0x6B7280, 0x9CA3AF)
                        isBorderPainted = false
                        isContentAreaFilled = false
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        addActionListener { onNavigateToTab?.invoke(3) } // CHANGES tab
                    }
                    add(viewDiffBtn)
                    add(reposToggleBtn)
                }

                add(leftBox, BorderLayout.WEST)
                add(rightActions, BorderLayout.EAST)

                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        toggleRepos()
                    }
                })
            }

            reposToggleBtn.addActionListener { toggleRepos() }

            add(reposHeader, BorderLayout.NORTH)
            add(reposScroll, BorderLayout.CENTER)
        }

        // 右侧使用垂直 Splitter，让任务拆解与关联代码仓 100% 同屏并存、随时可见！
        val rightSplitter = OnePixelSplitter(true, "org.agentworkbench.intellij.featureDashboardRightProportion", 0.50f).apply {
            firstComponent = rightTasksCard
            secondComponent = rightReposCard
            dividerWidth = JBUI.scale(6)
        }

        // 主 Splitter：左侧 58% 需求概述，右侧 42% 任务与代码仓
        val mainSplitter = OnePixelSplitter(false, "org.agentworkbench.intellij.featureDashboardMainProportion", 0.58f).apply {
            firstComponent = leftCard
            secondComponent = rightSplitter
            dividerWidth = JBUI.scale(8)
        }

        add(mainSplitter, BorderLayout.CENTER)
        border = JBUI.Borders.empty(6, 10, 10, 10)
    }

    private fun toggleTasks() {
        tasksExpanded = !tasksExpanded
        tasksScroll.isVisible = tasksExpanded
        tasksToggleBtn.text = if (tasksExpanded) "收起" else "展开"
        tasksToggleBtn.icon = if (tasksExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        rightTasksCard.revalidate()
        rightTasksCard.repaint()
    }

    private fun toggleRepos() {
        reposExpanded = !reposExpanded
        reposScroll.isVisible = reposExpanded
        reposToggleBtn.text = if (reposExpanded) "收起" else "展开"
        reposToggleBtn.icon = if (reposExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        rightReposCard.revalidate()
        rightReposCard.repaint()
    }

    fun update(
        featureData: JsonObject,
        tasks: List<JsonObject>,
        repositoryBindings: List<JsonObject>,
        repoRoots: Map<String, Path>,
        requirementsContent: String?,
        requirementsPath: String?,
        scenes: Map<String, NativeGit.Snapshot> = emptyMap(),
        onNavigateTab: (Int) -> Unit
    ) {
        onNavigateToTab = onNavigateTab
        val summary = featureData.get("summary").obj()
        currentSlug = summary?.str("slug") ?: ""
        val previousReqPath = currentReqPath
        val previousReqContent = currentReqContent
        currentReqPath = requirementsPath ?: "requirements/requirements.md"
        reqDocTitle.text = currentReqPath
        scopeLbl.text = "需求范围"

        // 1. 渲染需求概述文本：调用方可能传 null（内容由后台本地读取或远程 loadDocument 异步补上），
        //    此时保留同一路径下已加载的内容，避免把已渲染正文打回占位文案。
        val contentToRender = when {
            !requirementsContent.isNullOrBlank() -> requirementsContent
            previousReqPath == currentReqPath && previousReqContent.isNotBlank() -> previousReqContent
            else -> {
                val desc = featureData.get("description").obj()?.str("content")
                if (!desc.isNullOrBlank()) desc else "正在读取 $currentReqPath…"
            }
        }
        setRequirementsContent(currentReqPath, contentToRender)

        // 2. 渲染任务清单（紧凑单行）
        tasksContainer.removeAll()
        val totalCount = tasks.size
        val completedCount = tasks.count { it.get("completed")?.asBoolean == true }
        val pct = if (totalCount > 0) (completedCount * 100 / totalCount) else 0

        val planSummary = summary?.get("planSummary").obj()
        val trustedObj = planSummary?.get("trustedProgress").obj()
        val isTrustedApplicable = trustedObj?.get("applicable")?.asBoolean == true
        val trustedDone = if (isTrustedApplicable) trustedObj?.get("completed")?.takeIf { it.isJsonPrimitive }?.asInt ?: completedCount else null
        val untrustedCount = if (isTrustedApplicable && trustedDone != null && completedCount > trustedDone) completedCount - trustedDone else 0

        taskProgressBar.value = pct
        taskProgressBar.isVisible = totalCount > 0
        taskProgressLabel.text = U.planProgress(completedCount, totalCount, percentage = true, trustedCompleted = if (isTrustedApplicable) trustedDone else null)
        if (untrustedCount > 0) {
            taskProgressLabel.toolTipText = "已标记完成 $completedCount 项，但其中 $untrustedCount 项缺乏有效凭据记录或未通过验证 (Untrusted)"
            taskProgressLabel.foreground = JBColor(0xD97706, 0xF59E0B)
        } else {
            taskProgressLabel.toolTipText = null
            taskProgressLabel.foreground = U.muted
        }

        val tasksFingerprint = tasks.joinToString(";") {
            "${it.str("id")}:${it.get("completed")?.asBoolean}:${it.get("trusted")?.takeIf { p -> p.isJsonPrimitive }?.asBoolean}:${it.str("title")}"
        }
        if (tasksFingerprint != lastTasksFingerprint || tasksContainer.componentCount == 0) {
            lastTasksFingerprint = tasksFingerprint
            val savedTaskScroll = tasksScroll.viewport.viewPosition
            tasksContainer.removeAll()

            if (tasks.isEmpty()) {
                tasksContainer.add(JLabel("该需求暂无任务记录").apply {
                    font = font.deriveFont(11f)
                    foreground = U.muted
                    border = JBUI.Borders.empty(8)
                })
            } else {
                var foundActive = false
                tasks.forEach { task ->
                    val id = task.str("id") ?: ""
                    val taskTitle = task.str("title") ?: id
                    val isDone = task.get("completed")?.asBoolean == true
                    val isActive = !isDone && !foundActive
                    if (isActive) foundActive = true

                    val row = createTaskRow(task, id, taskTitle, isDone, isActive) {
                        onOpenTask(task)
                    }
                    tasksContainer.add(row)
                    tasksContainer.add(Box.createVerticalStrut(JBUI.scale(2)))
                }
                tasksContainer.add(Box.createVerticalGlue())
            }
            tasksContainer.revalidate()
            tasksContainer.repaint()
            SwingUtilities.invokeLater {
                tasksScroll.viewport.viewPosition = savedTaskScroll
            }
        }

        // 3. 渲染真实关联代码仓与变更
        reposCountLabel.text = "${repositoryBindings.size} 个关联仓库"
        val reposFingerprint = repositoryBindings.joinToString(";") { b ->
            val repoName = b.str("repository").orEmpty()
            val scene = scenes[repoName] as? NativeGit.Snapshot.Available
            "$repoName:${scene?.branch}:${scene?.changes}:${scene?.ahead}:${scene?.behind}"
        }
        if (reposFingerprint != lastReposFingerprint || reposContainer.componentCount == 0) {
            lastReposFingerprint = reposFingerprint
            val savedRepoScroll = reposScroll.viewport.viewPosition
            reposContainer.removeAll()

            if (repositoryBindings.isEmpty()) {
                reposContainer.add(JLabel("该需求未关联任何业务仓库").apply {
                    font = font.deriveFont(11f)
                    foreground = U.muted
                    border = JBUI.Borders.empty(8)
                })
            } else {
                repositoryBindings.forEach { binding ->
                    val repoName = binding.str("repository").orEmpty()
                    val baseBranch = binding.str("baseBranch") ?: "master"
                    val workBranch = binding.str("workBranch") ?: "feat/$currentSlug"
                    val scene = scenes[repoName] as? NativeGit.Snapshot.Available

                    val card = createRepoCard(repoName, baseBranch, workBranch, scene, repoRoots[repoName])
                    reposContainer.add(card)
                    reposContainer.add(Box.createVerticalStrut(JBUI.scale(4)))
                }
                reposContainer.add(Box.createVerticalGlue())
            }
            reposContainer.revalidate()
            reposContainer.repaint()
            SwingUtilities.invokeLater {
                reposScroll.viewport.viewPosition = savedRepoScroll
            }
        }
    }

    fun setRequirementsContent(path: String, content: String) {
        if (currentReqPath == path && currentReqContent == content) return
        currentReqPath = path
        currentReqContent = content
        reqDocTitle.text = path

        val textColorHex = hex(U.text)
        val accentColorHex = hex(U.accent)
        val surfaceColorHex = hex(U.surface)
        val borderColorHex = hex(U.border)
        val faintColorHex = hex(U.faint)
        val cardBgHex = hex(U.bg)

        val renderedBody = DocumentView.render(content)
        val html = """
            <html>
            <head>
            <style>
            body {
                background-color: #$cardBgHex;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                font-size: 11.5px;
                color: #$textColorHex;
                margin: 0;
                padding: 0;
                line-height: 1.6;
            }
            h1 { font-size: 15px; margin: 10px 0 6px 0; color: #$textColorHex; font-weight: bold; }
            h2 { font-size: 13.5px; margin: 8px 0 4px 0; color: #$textColorHex; font-weight: bold; }
            h3 { font-size: 12px; margin: 6px 0 4px 0; color: #$textColorHex; font-weight: bold; }
            p { margin: 0 0 8px 0; }
            ul, ol { margin: 0 0 8px 18px; padding: 0; }
            li { margin-bottom: 3px; }
            a { color: #$accentColorHex; text-decoration: none; }
            code {
                font-family: "JetBrains Mono", Consolas, Menlo, monospace;
                font-size: 10.5px;
                background-color: #$surfaceColorHex;
                color: #$accentColorHex;
                padding: 1px 4px;
            }
            pre {
                font-family: "JetBrains Mono", Consolas, Menlo, monospace;
                font-size: 10.5px;
                background-color: #$surfaceColorHex;
                border: 1px solid #$borderColorHex;
                padding: 8px;
                margin: 6px 0;
            }
            blockquote {
                margin: 6px 0;
                padding-left: 10px;
                border-left: 3px solid #$accentColorHex;
                color: #$faintColorHex;
            }
            </style>
            </head>
            <body>
            $renderedBody
            </body>
            </html>
        """.trimIndent()

        reqEditorPane.text = html
        reqEditorPane.caretPosition = 0
    }

    private fun createTaskRow(
        task: JsonObject,
        id: String,
        taskTitle: String,
        isDone: Boolean,
        isActive: Boolean,
        onClick: () -> Unit
    ): JPanel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = true
        background = if (isActive) U.selection else U.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, if (isActive) JBUI.scale(2) else 0, 1, 0, if (isActive) U.accent else U.border),
            JBUI.Borders.empty(3, 6)
        )
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
        preferredSize = Dimension(JBUI.scale(200), JBUI.scale(28))

        val trusted = task.get("trusted")?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull()
        val validationKind = task.str("validationKind")
        val deliverables = task.objects("deliverables")

        val checkIcon = when {
            isDone && trusted == true -> AllIcons.General.InspectionsOK
            isDone && trusted == false -> AllIcons.General.Warning
            isDone -> AllIcons.General.InspectionsOK
            else -> AllIcons.General.TodoDefault
        }
        val checkTooltip = when {
            isDone && trusted == true -> "已完成并具备有效验证凭据"
            isDone && trusted == false -> "已标记完成，但缺乏有效执行凭据或退出码异常 (Untrusted)"
            isDone -> "已完成"
            else -> "待完成"
        }
        val checkLbl = JLabel(checkIcon).apply { toolTipText = checkTooltip }

        val idLbl = JLabel("[$id]").apply {
            font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scale(10))
            foreground = if (isDone && trusted == false) JBColor(0xD97706, 0xF59E0B) else if (isDone) JBColor(0x059669, 0x34D399) else if (isActive) JBColor(0x2563EB, 0x60A5FA) else U.muted
        }

        val delivTip = deliverables.joinToString("<br/>") { d -> "• " + (d.str("path") ?: d.str("symbol") ?: "") }
        val titleLbl = JLabel(taskTitle).apply {
            font = font.deriveFont(if (isActive) Font.BOLD else Font.PLAIN, 11f)
            foreground = if (isDone) JBColor(0x9CA3AF, 0x6B7280) else if (isActive) JBColor(0x1D4ED8, 0xF3F4F6) else JBColor(0x1F2937, 0xE5E7EB)
            toolTipText = buildString {
                append("<html><b>[").append(id).append("] ").append(taskTitle).append("</b>")
                append("<br/>状态: ")
                when {
                    isDone && trusted == true -> append("<font color='#10B981'>已完成 (凭据有效)</font>")
                    isDone && trusted == false -> append("<font color='#F59E0B'>已打勾但缺乏凭据 (Untrusted)</font>")
                    isDone -> append("已完成")
                    else -> append("待完成")
                }
                if (validationKind != null) append("<br/>验证: ").append(validationKind)
                if (deliverables.isNotEmpty()) {
                    append("<br/>交付物 (${deliverables.size}):<br/>").append(delivTip)
                }
                append("</html>")
            }
        }

        val leftBox = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(checkLbl)
            add(idLbl)
            if (validationKind != null) {
                add(JLabel("[$validationKind]").apply {
                    font = font.deriveFont(9f)
                    foreground = U.faint
                    toolTipText = "验证方式: $validationKind"
                })
            }
            if (deliverables.isNotEmpty()) {
                add(JLabel("📦${deliverables.size}").apply {
                    font = font.deriveFont(9f)
                    foreground = U.faint
                    toolTipText = "<html>交付文件 (${deliverables.size}):<br/>$delivTip</html>"
                })
            }
            add(titleLbl)
        }

        val locateBtn = JButton("定位原文").apply {
            font = font.deriveFont(10f)
            foreground = JBColor(0x2563EB, 0x60A5FA)
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { onClick() }
        }

        add(leftBox, BorderLayout.CENTER)
        add(locateBtn, BorderLayout.EAST)

        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) { onClick() }
        })
    }

    private fun createRepoCard(
        repoName: String,
        baseBranch: String,
        workBranch: String,
        scene: NativeGit.Snapshot.Available?,
        repoPath: Path?
    ): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = U.surface
        border = BorderFactory.createCompoundBorder(
            BottomLine(U.border),
            JBUI.Borders.empty(6, 4)
        )
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(76))
        preferredSize = Dimension(JBUI.scale(220), JBUI.scale(72))

        // 第 1 行：仓库名称 (加粗带文件夹图标) + 在工程定位按钮
        val row1 = JPanel(BorderLayout()).apply {
            isOpaque = false
            val nameLbl = JLabel(repoName, AllIcons.Nodes.Folder, JLabel.LEFT).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = JBColor(0x111827, 0xF9FAFB)
            }
            val locateRepoBtn = JButton("在工程定位").apply {
                font = font.deriveFont(10.5f)
                foreground = JBColor(0x2563EB, 0x60A5FA)
                isBorderPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener {
                    repoPath?.let { p ->
                        val vf = LocalFileSystem.getInstance().findFileByPath(p.toString())
                        if (vf != null) {
                            RevealFileAction.openFile(p)
                        }
                    }
                }
            }
            add(nameLbl, BorderLayout.WEST)
            add(locateRepoBtn, BorderLayout.EAST)
        }

        // 第 2 行：分支信息（独立一行！绝不与仓库名挤在同一行）
        val branchFullText = "$baseBranch → $workBranch"
        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            val branchIcon = JLabel(AllIcons.Vcs.Branch)
            val branchLbl = JLabel(branchFullText).apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(10))
                foreground = U.muted
                toolTipText = "分支流转: $branchFullText"
            }
            add(branchIcon)
            add(branchLbl)
        }

        // 第 3 行：Git 现场状态（独立一行！清晰展示）
        val row3 = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            val statusText = if (scene == null) {
                "Git 现场未就绪"
            } else if (scene.changes > 0) {
                "有 ${scene.changes} 个文件发生变更"
            } else {
                "✓ 分支现场干净，无未提交变更"
            }

            val statusColor = if (scene != null && scene.changes > 0) {
                JBColor(0xD97706, 0xFBBF24)
            } else {
                JBColor(0x059669, 0x34D399)
            }

            val statusLbl = JLabel(statusText).apply {
                font = font.deriveFont(10.5f)
                foreground = statusColor
            }
            add(statusLbl)
        }

        add(row1)
        add(row2)
        add(row3)
    }

    private fun createCardPanel(): JPanel = JPanel().apply {
        isOpaque = true
        background = U.surface
    }

    private fun hex(color: Color): String = "%02x%02x%02x".format(color.red, color.green, color.blue)

    override fun dispose() {}
}

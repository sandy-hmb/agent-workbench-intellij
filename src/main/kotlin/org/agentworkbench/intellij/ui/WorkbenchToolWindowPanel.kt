package org.agentworkbench.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.google.gson.JsonObject
import org.agentworkbench.intellij.WorkbenchNotifier
import org.agentworkbench.intellij.WorkbenchService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

internal class WorkbenchToolWindowPanel(private val project: Project) : JPanel(CardLayout()), Disposable {
    private val service = WorkbenchService.getInstance(project)
    private val U = WorkbenchUi
    private var disposed = false

    private val emptyPanel = JPanel(GridBagLayout())
    private val contentPanel = JPanel(BorderLayout())

    // 状态点 (在线绿色)
    private val onlineDot = object : JComponent() {
        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(8), JBUI.scale(8))
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(0x34C759, 0x59A869)
            g2.fillOval(0, 0, width, height)
            g2.dispose()
        }
    }

    private val workspaceTitle = U.label("企业核心研发工作区", 12, bold = true)
    private val workspaceSub = U.label("尚未绑定 Kit", 10, U.muted)

    // 分支检测横幅
    private var currentBranchMatchedSlug: String? = null
    private val branchBannerLabel = JLabel("", AllIcons.General.Information, SwingConstants.LEFT).apply {
        font = font.deriveFont(11f)
        foreground = JBColor(0x0C4A6E, 0x93C5FD)
    }
    private val branchBannerSwitchBtn = JButton("切换查看").apply {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        foreground = JBColor(0x0284C7, 0x38BDF8)
        font = font.deriveFont(11f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener {
            val slug = currentBranchMatchedSlug ?: return@addActionListener
            openInEditor { editor ->
                editor.panel.selectFeature(slug)
            }
        }
    }

    private val branchBanner = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        background = JBColor(0xF0F9FF, 0x1E2B38)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(0xBAE6FD, 0x2A3E52), 1),
            JBUI.Borders.empty(6, 10)
        )
        isVisible = false
        add(branchBannerLabel, BorderLayout.CENTER)
        add(branchBannerSwitchBtn, BorderLayout.EAST)
    }

    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "搜索需求..."
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
    }
    private val featureModel = DefaultListModel<JsonObject>()
    private val allFeatures = mutableListOf<JsonObject>()
    private val featureList = JBList(featureModel).apply {
        background = JBColor(0xFAFAFA, 0x2B2D30)
        selectionBackground = JBColor(0xE0E7FF, 0x2E436E)
    }

    private val repoModel = DefaultListModel<JsonObject>()
    private val repoList = JBList(repoModel).apply {
        background = JBColor(0xFAFAFA, 0x2B2D30)
        selectionBackground = JBColor(0xE0E7FF, 0x2E436E)
    }

    init {
        setupEmptyPanel()
        setupContentPanel()
        add(emptyPanel, "EMPTY")
        add(contentPanel, "CONTENT")

        service.subscribe(this) {
            refresh()
        }
        refresh()
    }

    private fun setupEmptyPanel() {
        emptyPanel.apply {
            background = U.bg
            val card = U.column(10).apply {
                background = U.bg
                add(U.label("研发工作台", 14, bold = true))
                add(U.copy("当前项目尚未绑定或未启动 Agent 工作流 Kit。"))
                add(U.button("配置工作区", true) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, WorkbenchConfigurable::class.java)
                })
            }
            add(card)
        }
    }

    private fun createNavGridBtn(title: String, icon: javax.swing.Icon, active: Boolean = false, action: () -> Unit): JComponent {
        val btn = object : JButton() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (active) {
                    g2.color = JBColor(0xEDF2F7, 0x1E2B38)
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
                    g2.color = JBColor(0xCBD5E1, 0x35538A)
                    g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(6), JBUI.scale(6))
                } else if (model.isRollover) {
                    g2.color = JBColor(0xF1F5F9, 0x32353A)
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
                }
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            layout = BorderLayout(0, JBUI.scale(2))
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(JBUI.scale(54), JBUI.scale(48))

            val iconLabel = JLabel(icon, SwingConstants.CENTER)
            val textLabel = JLabel(title, SwingConstants.CENTER).apply {
                font = font.deriveFont(10f)
                foreground = if (active) JBColor(0x0F172A, 0x38BDF8) else U.muted
            }
            add(iconLabel, BorderLayout.CENTER)
            add(textLabel, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(4, 2)
            addActionListener { action() }
        }
        return btn
    }

    private fun setupContentPanel() {
        contentPanel.background = JBColor(0xFAFAFA, 0x2B2D30)

        // 1. 顶部工作区卡片：名称 + 状态点 + 4宫格水平导航
        val titleRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(workspaceTitle)
                add(onlineDot)
            }
            add(left, BorderLayout.WEST)
            val openBtn = JButton(AllIcons.Actions.OpenNewTab).apply {
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                toolTipText = "全屏打开工作台"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { openInEditor { it.panel.navigate("overview") } }
            }
            add(openBtn, BorderLayout.EAST)
        }

        val navGrid = JPanel(GridLayout(1, 4, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(createNavGridBtn("总览", AllIcons.Nodes.HomeFolder, active = true) {
                openInEditor { it.panel.navigate("overview") }
            })
            add(createNavGridBtn("记录", AllIcons.Actions.Execute) {
                openInEditor { it.panel.navigate("runs") }
            })
            add(createNavGridBtn("体检", AllIcons.General.Warning) {
                openInEditor { it.panel.navigate("diagnostics") }
            })
            add(createNavGridBtn("配置", AllIcons.General.Settings) {
                openInEditor { it.panel.navigate("configuration") }
            })
        }

        val topCard = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            background = JBColor(0xFAFAFA, 0x2B2D30)
            border = JBUI.Borders.empty(10, 12, 8, 12)
            add(titleRow)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
            add(navGrid)
        }

        // 2. 需求列表 Item 卡片式渲染器
        featureList.cellRenderer = ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
            if (value == null) return@ListCellRenderer JPanel()

            val slug = value.str("slug") ?: ""
            val title = value.str("title") ?: slug
            val status = value.str("status") ?: "unknown"
            val isCheckedOut = (slug == currentBranchMatchedSlug)

            val panel = object : JPanel(BorderLayout(JBUI.scale(6), 0)) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    if (isSelected) {
                        g2.color = JBColor(0xDBEAFE, 0x1E2B38)
                        g2.fillRoundRect(JBUI.scale(4), JBUI.scale(2), width - JBUI.scale(8), height - JBUI.scale(4), JBUI.scale(6), JBUI.scale(6))
                        g2.color = JBColor(0x93C5FD, 0x2563EB)
                        g2.drawRoundRect(JBUI.scale(4), JBUI.scale(2), width - JBUI.scale(8) - 1, height - JBUI.scale(4) - 1, JBUI.scale(6), JBUI.scale(6))
                    }
                    g2.dispose()
                    super.paintComponent(g)
                }
            }.apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 8, 6, 8)
            }

            val iconLabel = JLabel(AllIcons.FileTypes.Text).apply {
                alignmentY = Component.TOP_ALIGNMENT
            }

            val centerText = JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                isOpaque = false
                val slugLbl = JLabel(slug).apply {
                    font = Font(Font.MONOSPACED, if (isSelected) Font.BOLD else Font.PLAIN, JBUI.scale(11))
                    foreground = if (isSelected) JBColor(0x1E293B, 0xFFFFFF) else U.text
                }
                val titleLbl = JLabel(title).apply {
                    font = font.deriveFont(Font.PLAIN, 10.5f)
                    foreground = if (isSelected) JBColor(0x475569, 0x94A3B8) else U.faint
                }
                add(slugLbl)
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)))
                add(titleLbl)
            }

            val rightBadges = JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                isOpaque = false
                if (isCheckedOut) {
                    val badge = JLabel("+ 当前检出").apply {
                        font = font.deriveFont(Font.BOLD, 9.5f)
                        foreground = JBColor(0x1D4ED8, 0x60A5FA)
                        background = JBColor(0xDBEAFE, 0x1E3A8A)
                        isOpaque = true
                        border = JBUI.Borders.empty(1, 6)
                        alignmentX = Component.RIGHT_ALIGNMENT
                    }
                    add(badge)
                    add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)))
                }
                val (statusText, statusFg) = when (status.lowercase()) {
                    "done", "completed" -> Pair("✓ 已完成", JBColor(0x15803D, 0x4ADE80))
                    "development", "in_progress" -> Pair("⟳ 进行中", JBColor(0x1D4ED8, 0x60A5FA))
                    "testing" -> Pair("◐ 测试中", JBColor(0xC2410C, 0xFB923C))
                    "planning" -> Pair("○ 规划中", JBColor(0x475569, 0x94A3B8))
                    else -> Pair(status, U.muted)
                }
                val stBadge = JLabel(statusText).apply {
                    font = font.deriveFont(10f)
                    foreground = statusFg
                    alignmentX = Component.RIGHT_ALIGNMENT
                }
                add(stBadge)
            }

            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(centerText, BorderLayout.CENTER)
            panel.add(rightBadges, BorderLayout.EAST)
            panel
        }

        featureList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    openSelectedFeature()
                }
            }
        })
        featureList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    openSelectedFeature()
                }
            }
        })

        // 右键菜单
        featureList.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val index = featureList.locationToIndex(Point(x, y))
                if (index >= 0) {
                    featureList.selectedIndex = index
                    val item = featureModel.getElementAt(index)
                    val slug = item?.str("slug") ?: return
                    val menu = JPopupMenu().apply {
                        add(JMenuItem("在工作台中打开", AllIcons.Actions.OpenNewTab).apply {
                            addActionListener { openSelectedFeature() }
                        })
                        add(JMenuItem("复制 Slug: $slug").apply {
                            addActionListener {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(slug), null)
                            }
                        })
                        add(JMenuItem("在系统文件管理器中显示", AllIcons.Actions.MenuOpen).apply {
                            addActionListener {
                                val dir = WorkbenchNavigation.resolveFeatureDir(project, slug)
                                if (dir != null && dir.toFile().exists()) RevealFileAction.openFile(dir)
                            }
                        })
                    }
                    menu.show(comp, x, y)
                }
            }
        })

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterFeatures(searchField.text)
            }
        })

        repoList.cellRenderer = object : ColoredListCellRenderer<JsonObject>() {
            override fun customizeCellRenderer(
                list: JList<out JsonObject>,
                value: JsonObject?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                val role = value.str("role")
                icon = if (role == "kit") AllIcons.Vcs.Branch else AllIcons.Nodes.Folder
                val name = value.str("id") ?: value.str("name") ?: ""
                val workBase = value.obj("effectiveBranchPolicy")?.str("workBase")
                append(name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (workBase != null) append("  ($workBase)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        repoList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    openInEditor { editor ->
                        editor.panel.navigate("repositories")
                    }
                }
            }
        })

        val scrollContent = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            background = JBColor(0xFAFAFA, 0x2B2D30)

            val featureHeader = JPanel(BorderLayout()).apply {
                background = JBColor(0xFAFAFA, 0x2B2D30)
                border = JBUI.Borders.empty(10, 12, 6, 12)
                add(U.label("需求", 11, bold = true), BorderLayout.WEST)
            }
            add(featureHeader)
            add(JPanel(BorderLayout()).apply {
                background = JBColor(0xFAFAFA, 0x2B2D30)
                border = JBUI.Borders.empty(0, 10, 6, 10)
                add(searchField, BorderLayout.CENTER)
            })
            add(featureList)

            add(javax.swing.Box.createVerticalStrut(JBUI.scale(10)))
            add(U.line())

            val repoHeader = JPanel(BorderLayout()).apply {
                background = JBColor(0xFAFAFA, 0x2B2D30)
                border = JBUI.Borders.empty(10, 12, 6, 12)
                add(U.label("已登记代码仓库", 11, bold = true), BorderLayout.WEST)
            }
            add(repoHeader)
            add(repoList)
        }

        // 底部常驻状态栏
        val bottomBar = JPanel(BorderLayout()).apply {
            background = JBColor(0xF1F5F9, 0x26282B)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor(0xE2E8F0, 0x393B40)),
                JBUI.Borders.empty(6, 12)
            )
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(onlineDot)
                add(U.label("工作区已连接", 10, U.muted))
            }
            val settingsBtn = JButton(AllIcons.General.Settings).apply {
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "工作区设置"
                addActionListener {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, WorkbenchConfigurable::class.java)
                }
            }
            add(left, BorderLayout.WEST)
            add(settingsBtn, BorderLayout.EAST)
        }

        val topPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            background = JBColor(0xFAFAFA, 0x2B2D30)
            add(topCard)
            add(branchBanner)
            add(U.line())
        }

        contentPanel.add(topPanel, BorderLayout.NORTH)
        contentPanel.add(JBScrollPane(scrollContent).apply {
            border = JBUI.Borders.empty()
            viewport.background = JBColor(0xFAFAFA, 0x2B2D30)
        }, BorderLayout.CENTER)
        contentPanel.add(bottomBar, BorderLayout.SOUTH)
    }

    fun refresh() {
        if (disposed) return
        val state = service.snapshot()
        val isConfigured = !state.kitRoot.isNullOrBlank()
        (layout as? CardLayout)?.show(this, if (isConfigured) "CONTENT" else "EMPTY")

        if (!isConfigured) return

        val identity = state.workspace?.data.obj()?.get("identity").obj()
        workspaceTitle.text = identity?.str("name") ?: "企业核心研发工作区"
        workspaceSub.text = state.kitRoot?.let { Path.of(it).fileName.toString() } ?: "已绑定 Kit"

        // 刷新检出分支关联
        val matched = service.currentBranchSlug()
        currentBranchMatchedSlug = matched
        if (matched != null) {
            val f = state.features.firstOrNull { it.str("slug") == matched }
            val name = f?.str("title") ?: matched
            branchBannerLabel.text = "当前检出分支属于需求：$name"
            branchBanner.isVisible = true
        } else {
            branchBanner.isVisible = false
        }

        allFeatures.clear()
        allFeatures.addAll(state.features)
        filterFeatures(searchField.text)

        repoModel.clear()
        val repos = state.workspace?.data.obj()?.objects("repositories").orEmpty()
        repos.forEach { repoModel.addElement(it) }
    }

    private fun filterFeatures(query: String) {
        val q = query.trim().lowercase()
        featureModel.clear()
        for (f in allFeatures) {
            val title = f.str("title").orEmpty().lowercase()
            val slug = f.str("slug").orEmpty().lowercase()
            if (q.isEmpty() || title.contains(q) || slug.contains(q)) {
                featureModel.addElement(f)
            }
        }
    }

    private fun openSelectedFeature() {
        val slug = featureList.selectedValue?.str("slug") ?: return
        openInEditor { editor ->
            editor.panel.selectFeature(slug)
        }
    }

    private fun openInEditor(action: (WorkbenchFileEditor) -> Unit) {
        WorkbenchToolWindowFactory.openWorkbench(project)
        val file = service.file()
        for (editor in FileEditorManager.getInstance(project).getEditors(file)) {
            if (editor is WorkbenchFileEditor) {
                action(editor)
            }
        }
    }

    override fun dispose() {
        disposed = true
    }
}

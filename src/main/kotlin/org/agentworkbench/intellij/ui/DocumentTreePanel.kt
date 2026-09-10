package org.agentworkbench.intellij.ui

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

internal class DocumentTreePanel(
    private val project: Project,
    val reader: DocumentReader,
    private val onDocumentSelected: (String) -> Unit,
    private val onOpenInEditor: (String) -> Unit
) : JPanel(BorderLayout()) {

    data class DocItem(
        val title: String,
        val path: String,
        val filename: String = "",
        val isCategory: Boolean = false,
        val count: Int = 0,
        val badge: String? = null,
        val badgeColor: Color? = null
    ) {
        override fun toString(): String = if (isCategory) "$title ($count)" else title
    }

    private val rootNode = DefaultMutableTreeNode(DocItem("Root", "", isCategory = true))
    private val treeModel = DefaultTreeModel(rootNode)
    val docTree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        rowHeight = JBUI.scale(26)
        border = JBUI.Borders.empty(4)
    }

    private var currentFiles: List<JsonObject> = emptyList()
    private var currentReviews: JsonObject? = null
    var activePath: String? = null
        private set
    private var updatingSelection = false

    // Header in left sidebar
    private val treeCountLabel = WorkbenchUi.label("0 篇", 11, WorkbenchUi.faint)

    // Header in right reading panel
    private val docIconLabel = JLabel(AllIcons.FileTypes.Custom)
    private val docTitleLabel = WorkbenchUi.label("请选择文档", 13, bold = true)
    private val docPathLabel = WorkbenchUi.mono("", WorkbenchUi.faint)
    private val docBadgeLabel = WorkbenchUi.badge("", WorkbenchUi.text).apply { isVisible = false }
    val documentStatus = WorkbenchUi.label("", 11, WorkbenchUi.faint)

    init {
        // Tree Renderer
        docTree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ) {
                val node = (value as? DefaultMutableTreeNode)?.userObject as? DocItem ?: return
                if (node.isCategory) {
                    icon = AllIcons.Nodes.Folder
                    append(node.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (node.count > 0) {
                        append(" (${node.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                } else {
                    icon = if (node.path.endsWith(".md")) AllIcons.FileTypes.Custom else AllIcons.FileTypes.Text
                    append(node.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (node.filename.isNotBlank() && node.filename != node.title) {
                        append(" · ${node.filename}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    if (!node.badge.isNullOrBlank()) {
                        val color = node.badgeColor ?: when (node.badge) {
                            "已批准", "已通过", "已闭环" -> WorkbenchUi.green
                            "待审阅", "待核验" -> WorkbenchUi.amber
                            else -> WorkbenchUi.muted
                        }
                        append(" [${node.badge}]", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
                    }
                }
            }
        }

        // Tree Selection Listener
        docTree.addTreeSelectionListener {
            if (updatingSelection) return@addTreeSelectionListener
            val selectedNode = docTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val item = selectedNode.userObject as? DocItem ?: return@addTreeSelectionListener
            if (!nodeIsCategory(item)) {
                activePath = item.path
                updateHeader(item)
                onDocumentSelected(item.path)
            }
        }

        // Double click to open in editor
        docTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    val path = activePath ?: return
                    onOpenInEditor(path)
                }
            }
        })

        // Build Left Sidebar
        val leftSidebar = JPanel(BorderLayout()).apply {
            val leftHeader = JPanel(BorderLayout()).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, WorkbenchUi.border),
                    JBUI.Borders.empty(8, 12)
                )
                val titleBox = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(JLabel(AllIcons.Nodes.Folder))
                    add(WorkbenchUi.label("文档目录", 12, bold = true))
                }
                add(titleBox, BorderLayout.WEST)
                add(treeCountLabel, BorderLayout.EAST)
            }
            add(leftHeader, BorderLayout.NORTH)
            add(JBScrollPane(docTree).apply {
                border = BorderFactory.createEmptyBorder()
            }, BorderLayout.CENTER)
        }

        // Build Right Panel Header
        val rightHeader = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, WorkbenchUi.border),
                JBUI.Borders.empty(8, 14)
            )
            val leftInfo = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(docIconLabel)
                add(docTitleLabel)
                add(docPathLabel)
                add(docBadgeLabel)
            }
            val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
                isOpaque = false
                add(documentStatus)
                val openBtn = WorkbenchUi.button("在编辑器中打开 (F4)", true) {
                    activePath?.let(onOpenInEditor)
                }.apply {
                    icon = AllIcons.General.OpenInToolWindow
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "在 IDEA 主编辑器 Tab 中打开此文档，支持原生预览与快捷键 (F4)"
                }
                add(openBtn)
            }
            add(leftInfo, BorderLayout.WEST)
            add(rightActions, BorderLayout.EAST)
        }

        val rightPanel = JPanel(BorderLayout()).apply {
            add(rightHeader, BorderLayout.NORTH)
            add(reader, BorderLayout.CENTER)
        }

        // Splitter
        val splitter = JBSplitter(false, 0.24f, 0.16f, 0.45f).apply {
            firstComponent = leftSidebar
            secondComponent = rightPanel
            dividerWidth = JBUI.scale(2)
        }

        add(splitter, BorderLayout.CENTER)
    }

    private fun nodeIsCategory(item: DocItem) = item.isCategory || item.path.isBlank()

    fun setDocuments(files: List<JsonObject>, reviews: JsonObject?) {
        currentFiles = files.filter { it.get("exists")?.asBoolean == true }
        currentReviews = reviews
        treeCountLabel.text = "${currentFiles.size} 篇"

        rootNode.removeAllChildren()

        val categorized = groupDocuments(currentFiles, reviews)
        for ((groupName, items) in categorized) {
            if (items.isEmpty()) continue
            val categoryNode = DefaultMutableTreeNode(
                DocItem(
                    title = groupName,
                    path = "",
                    isCategory = true,
                    count = items.size
                )
            )
            for (item in items) {
                categoryNode.add(DefaultMutableTreeNode(item))
            }
            rootNode.add(categoryNode)
        }

        treeModel.reload()
        expandAll()

        // Restore or select first document
        val toSelect = activePath?.takeIf { path -> currentFiles.any { it.str("path") == path } }
            ?: currentFiles.firstOrNull()?.str("path")

        if (toSelect != null) {
            selectDocument(toSelect, fireEvent = false)
        }
    }

    fun selectDocument(path: String, fireEvent: Boolean = true) {
        activePath = path
        val node = findNodeByPath(rootNode, path)
        if (node != null) {
            updatingSelection = true
            val treePath = TreePath(node.path)
            docTree.selectionPath = treePath
            docTree.scrollPathToVisible(treePath)
            updatingSelection = false

            val item = node.userObject as? DocItem
            if (item != null) {
                updateHeader(item)
            }
        }
        if (fireEvent) {
            onDocumentSelected(path)
        }
    }

    private fun updateHeader(item: DocItem) {
        docTitleLabel.text = item.title
        docPathLabel.text = item.path
        if (!item.badge.isNullOrBlank()) {
            docBadgeLabel.text = " ${item.badge} "
            docBadgeLabel.foreground = item.badgeColor ?: when (item.badge) {
                "已批准", "已通过", "已闭环" -> WorkbenchUi.green
                "待审阅", "待核验" -> WorkbenchUi.amber
                else -> WorkbenchUi.muted
            }
            docBadgeLabel.isVisible = true
        } else {
            docBadgeLabel.isVisible = false
        }
    }

    private fun findNodeByPath(current: DefaultMutableTreeNode, path: String): DefaultMutableTreeNode? {
        val userObj = current.userObject as? DocItem
        if (userObj != null && !userObj.isCategory && userObj.path == path) {
            return current
        }
        for (i in 0 until current.childCount) {
            val child = current.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val found = findNodeByPath(child, path)
            if (found != null) return found
        }
        return null
    }

    private fun expandAll() {
        var i = 0
        while (i < docTree.rowCount) {
            docTree.expandRow(i)
            i++
        }
    }

    private fun groupDocuments(files: List<JsonObject>, reviews: JsonObject?): List<Pair<String, List<DocItem>>> {
        val coreItems = mutableListOf<DocItem>()
        val reqItems = mutableListOf<DocItem>()
        val designItems = mutableListOf<DocItem>()
        val planItems = mutableListOf<DocItem>()
        val verifyItems = mutableListOf<DocItem>()
        val artifactItems = mutableListOf<DocItem>()
        val otherItems = mutableListOf<DocItem>()

        val coreMapping = mapOf(
            "requirements/requirements.md" to ("需求说明" to "requirements"),
            "requirements.md" to ("需求说明" to "requirements"),
            "design/design.md" to ("技术设计" to "design"),
            "design.md" to ("技术设计" to "design"),
            "plans/implementation.md" to ("实施计划" to "plan"),
            "plan.md" to ("实施计划" to "plan"),
            "testing/verification.md" to ("验证记录" to null),
            "verification.md" to ("验证记录" to null),
            "README.md" to ("需求索引概览" to null)
        )

        for (file in files) {
            val path = file.str("path") ?: continue
            val fileName = Path.of(path).fileName?.toString() ?: path

            if (coreMapping.containsKey(path)) {
                val (title, reviewKey) = coreMapping.getValue(path)
                val badge = reviewKey?.let { reviews?.str(it) }
                val color = when (badge) {
                    "已批准", "已通过" -> WorkbenchUi.green
                    "待审阅", "待核验" -> WorkbenchUi.amber
                    else -> null
                }
                coreItems.add(DocItem(title = title, path = path, filename = fileName, badge = badge, badgeColor = color))
            } else if (path.startsWith("requirements/")) {
                val title = if (fileName.startsWith("jira-", ignoreCase = true)) {
                    "Jira 原始记录 (${fileName.removePrefix("jira-").removeSuffix(".md")})"
                } else {
                    fileName.removeSuffix(".md")
                }
                reqItems.add(DocItem(title = title, path = path, filename = fileName))
            } else if (path.startsWith("design/")) {
                designItems.add(DocItem(title = fileName.removeSuffix(".md"), path = path, filename = fileName))
            } else if (path.startsWith("plans/")) {
                planItems.add(DocItem(title = fileName.removeSuffix(".md"), path = path, filename = fileName))
            } else if (path.startsWith("testing/") || path.startsWith("tests/")) {
                verifyItems.add(DocItem(title = fileName.removeSuffix(".md"), path = path, filename = fileName))
            } else if (path.startsWith("artifacts/") || path.startsWith("attachments/")) {
                artifactItems.add(DocItem(title = fileName, path = path, filename = fileName))
            } else {
                otherItems.add(DocItem(title = fileName.removeSuffix(".md"), path = path, filename = fileName))
            }
        }

        val canonicalOrder = listOf(
            "requirements/requirements.md", "requirements.md",
            "design/design.md", "design.md",
            "plans/implementation.md", "plan.md",
            "testing/verification.md", "verification.md",
            "README.md"
        )
        coreItems.sortBy { item ->
            val idx = canonicalOrder.indexOf(item.path)
            if (idx >= 0) idx else 99
        }

        return listOf(
            "核心阶段文档" to coreItems,
            "需求与背景" to reqItems,
            "技术设计附件" to designItems,
            "实施计划附件" to planItems,
            "测试验证附件" to verifyItems,
            "交付产物与脚本" to artifactItems,
            "其他文档" to otherItems
        )
    }
}

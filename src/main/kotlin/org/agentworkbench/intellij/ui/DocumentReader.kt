package org.agentworkbench.intellij.ui

import com.intellij.find.EditorSearchSession
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Heading
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Toolkit
import java.awt.event.KeyEvent
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.event.HyperlinkEvent

/** 文档内容仅来自 Inspect；所有资源链接交回工作台进行边界判断。 */
internal class DocumentReader(private val project: Project, private val link: (String) -> Unit) : JPanel(BorderLayout()), Disposable {
    private val editor = EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument(""), project)
    private val html = JEditorPane("text/html", "").apply { isEditable = false }
    private val cards = CardLayout()
    private val body = JPanel(cards)
    private val headings = DefaultListModel<HeadingLocation>()
    private val outline = JBList(headings)
    private val anchors = mutableMapOf<String, Int>()
    private val readToggle = JToggleButton("阅读", AllIcons.General.LayoutPreviewOnly, true)
    private val sourceToggle = JToggleButton("原文", AllIcons.General.LayoutEditorOnly)
    private val outlineToggle = JToggleButton("大纲", AllIcons.Actions.ListFiles, true).apply {
        toolTipText = "显示/隐藏文档大纲导航栏"
    }
    private var disposed = false
    var onPosition: ((Int) -> Unit)? = null
    var onOpenInEditor: (() -> Unit)? = null
    private val openInEditorBtn = WorkbenchUi.button("在编辑器中打开 (F4)", action = { onOpenInEditor?.invoke() }).apply {
        icon = AllIcons.General.OpenInToolWindow
        toolTipText = "在主编辑器 Tab 中打开真实文件（支持双栏并排、编辑与 Markdown 插件渲染）"
    }

    init {
        getAccessibleContext().accessibleName = "需求文档阅读器"
        background=WorkbenchUi.bg;html.background=WorkbenchUi.bg;html.foreground=WorkbenchUi.text
        html.font=WorkbenchUi.label("",12).font;html.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true)
        outline.background=WorkbenchUi.bg;outline.foreground=WorkbenchUi.muted;outline.selectionBackground=WorkbenchUi.selection
        outline.setEmptyText("暂无大纲")
        ListSpeedSearch.installOn(outline)
        editor.settings.isLineNumbersShown = true
        body.add(WorkbenchUi.scroll(html), "rendered")
        body.add(editor.component, "source")
        ButtonGroup().apply { add(readToggle); add(sourceToggle) }
        readToggle.addActionListener { showMode("rendered") }
        sourceToggle.addActionListener { showMode("source") }
        readToggle.toolTipText = "渲染视图"
        sourceToggle.toolTipText = "原文视图（支持行定位与查找）"

        val outlineScroll = WorkbenchUi.scroll(outline)
        val splitter = JBSplitter(false, 0.2f, 0.05f, 0.45f).apply {
            firstComponent = outlineScroll
            secondComponent = body
            dividerWidth = com.intellij.util.ui.JBUI.scale(1)
            border = com.intellij.util.ui.JBUI.Borders.empty()
            background = WorkbenchUi.bg
        }
        outlineToggle.addActionListener {
            outlineScroll.isVisible = outlineToggle.isSelected
            splitter.revalidate()
            splitter.repaint()
        }

        add(WorkbenchUi.panel(BorderLayout()).apply {
            add(WorkbenchUi.panel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 8)).apply {
                add(readToggle)
                add(sourceToggle)
                add(outlineToggle)
            }, BorderLayout.WEST)
        }, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        html.addHyperlinkListener { event -> if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) link(event.description) }
        outline.addListSelectionListener { if (!it.valueIsAdjusting) outline.selectedValue?.let { heading -> goToLine(heading.line) } }
        editor.caretModel.addCaretListener(object : com.intellij.openapi.editor.event.CaretListener {
            override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) { onPosition?.invoke(event.newPosition.line + 1) }
        }, this)
        // IDEA 习惯：Cmd/Ctrl+F 直接唤起查找（自动切到原文视图）。
        registerKeyboardAction({ openSearch() },
            KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        // IDEA 习惯：F4 跳转到主编辑器
        registerKeyboardAction({ onOpenInEditor?.invoke() },
            KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
    }

    private fun showMode(mode: String) {
        cards.show(body, mode)
        readToggle.isSelected = mode == "rendered"
        sourceToggle.isSelected = mode == "source"
    }

    private fun openSearch() {
        if (disposed) return
        showMode("source")
        EditorSearchSession.start(editor, project)
    }

    fun showText(content: String, line: Int? = null, plainText: Boolean = false) = showPrepared(prepare(content), line, plainText)

    /** 应用已在后台解析好的文档；仅做 EDT 上必须的 UI 更新。 */
    fun showPrepared(prepared: Prepared, line: Int? = null, plainText: Boolean = false) {
        if (disposed) return
        ApplicationManager.getApplication().runWriteAction { editor.document.setText(prepared.content) }
        html.text = prepared.html
        html.caretPosition = 0
        if (plainText) showMode("source")
        headings.clear()
        anchors.clear()
        prepared.headings.forEach(headings::addElement)
        anchors.putAll(prepared.anchors)
        if (line != null) goToLine(line)
    }

    fun currentLine(): Int = if (!disposed) editor.caretModel.logicalPosition.line + 1 else 1

    fun goToAnchor(anchor: String): Boolean = anchors[anchor]?.let { goToLine(it); true } ?: false

    fun goToLine(line: Int) {
        if (disposed || line < 1 || line > editor.document.lineCount) return
        showMode("source")
        editor.caretModel.moveToOffset(editor.document.getLineStartOffset(line - 1))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    override fun dispose() { if (!disposed) { disposed = true; EditorSearchSession.get(editor)?.close(); EditorFactory.getInstance().releaseEditor(editor) } }
    internal data class HeadingLocation(val title: String, val line: Int) { override fun toString() = title }

    /** 解析结果；prepare 是纯函数，可在任意线程执行。 */
    internal class Prepared internal constructor(
        val content: String,
        internal val html: String,
        internal val headings: List<HeadingLocation>,
        internal val anchors: Map<String, Int>,
    )

    companion object {
        /** 解析 Markdown 生成渲染 HTML、大纲与锚点表；不触碰任何 Swing 组件。 */
        fun prepare(content: String): Prepared {
            fun hex(color: java.awt.Color) = "%02x%02x%02x".format(color.red, color.green, color.blue)
            val rendered = "<html><head><style>body{font-family:sans-serif;font-size:12px;color:#${hex(WorkbenchUi.text)};margin:20px;} p{margin-bottom:14px;} h1{font-size:23px;} h2{font-size:17px;margin-top:22px;} a{color:#${hex(WorkbenchUi.accent)};} code,pre{font-family:monospace;} td,th{padding:8px;border:1px solid #${hex(WorkbenchUi.border)};}</style></head><body>" + DocumentView.render(content) + "</body></html>"
            val headings = mutableListOf<HeadingLocation>()
            val anchors = mutableMapOf<String, Int>()
            val parsed = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().parse(content)
            parsed.accept(object : AbstractVisitor() {
                override fun visit(heading: Heading) {
                    val title = StringBuilder()
                    heading.accept(object : AbstractVisitor() {
                        override fun visit(text: Text) { title.append(text.literal) }
                        override fun visit(code: org.commonmark.node.Code) { title.append(code.literal) }
                    })
                    val line = (heading.sourceSpans.firstOrNull()?.lineIndex ?: 0) + 1
                    headings += HeadingLocation("  ".repeat((heading.level - 1).coerceAtLeast(0)) + title, line)
                    val key = title.toString().lowercase().replace(Regex("[^\\p{L}\\p{N}\\s_-]"), "").replace(Regex("\\s+"), "-")
                    var unique = key; var duplicate = 1
                    while (unique in anchors) unique = "$key-${duplicate++}"
                    anchors[unique] = line
                }
            })
            content.lines().forEachIndexed { index, text ->
                Regex("(?i)<a\\s+(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']").findAll(text).forEach { anchors[it.groupValues[1]] = index + 1 }
            }
            return Prepared(content, rendered, headings, anchors)
        }
    }
}

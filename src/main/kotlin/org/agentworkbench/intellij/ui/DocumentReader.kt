package org.agentworkbench.intellij.ui

import com.intellij.find.EditorSearchSession
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Heading
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.event.HyperlinkEvent

/** 文档内容仅来自 Inspect；所有资源链接交回工作台进行边界判断。 */
internal class DocumentReader(project: Project, private val link: (String) -> Unit) : JPanel(BorderLayout()), Disposable {
    private val editor = EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument(""), project)
    private val html = JEditorPane("text/html", "").apply { isEditable = false }
    private val cards = CardLayout()
    private val body = JPanel(cards)
    private val headings = DefaultListModel<HeadingLocation>()
    private val outline = JBList(headings)
    private val anchors = mutableMapOf<String, Int>()
    private var disposed = false
    var onPosition: ((Int) -> Unit)? = null

    init {
        getAccessibleContext().accessibleName = "需求文档阅读器"
        background=WorkbenchUi.bg;html.background=WorkbenchUi.bg;html.foreground=WorkbenchUi.text
        html.font=WorkbenchUi.label("",12).font;html.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true)
        outline.background=WorkbenchUi.bg;outline.foreground=WorkbenchUi.muted;outline.selectionBackground=WorkbenchUi.selection
        editor.settings.isLineNumbersShown = true
        body.add(WorkbenchUi.scroll(html), "rendered")
        body.add(editor.component, "source")
        add(WorkbenchUi.panel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT,8,8)).apply {
            add(WorkbenchUi.button("阅读",true) { cards.show(body, "rendered") })
            add(WorkbenchUi.button("原文",true) { cards.show(body, "source") })
            add(WorkbenchUi.button("查找") { cards.show(body, "source"); EditorSearchSession.start(editor, project) })
        }, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, WorkbenchUi.scroll(outline), body).apply { resizeWeight = 0.18; dividerLocation=190; border=com.intellij.util.ui.JBUI.Borders.empty(); background=WorkbenchUi.bg }, BorderLayout.CENTER)
        html.addHyperlinkListener { event -> if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) link(event.description) }
        outline.addListSelectionListener { if (!it.valueIsAdjusting) outline.selectedValue?.let { heading -> goToLine(heading.line) } }
        editor.caretModel.addCaretListener(object : com.intellij.openapi.editor.event.CaretListener {
            override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) { onPosition?.invoke(event.newPosition.line + 1) }
        }, this)
    }

    fun showText(content: String, line: Int? = null, plainText: Boolean = false) {
        if (disposed) return
        ApplicationManager.getApplication().runWriteAction { editor.document.setText(content) }
        fun hex(color:java.awt.Color)="%02x%02x%02x".format(color.red,color.green,color.blue)
        html.text = "<html><head><style>body{font-family:sans-serif;font-size:12px;color:#${hex(WorkbenchUi.text)};margin:20px;} p{margin-bottom:14px;} h1{font-size:23px;} h2{font-size:17px;margin-top:22px;} a{color:#${hex(WorkbenchUi.accent)};} code,pre{font-family:monospace;} td,th{padding:8px;border:1px solid #${hex(WorkbenchUi.border)};}</style></head><body>"+DocumentView.render(content)+"</body></html>"
        html.caretPosition = 0
        if (plainText) cards.show(body, "source")
        headings.clear()
        anchors.clear()
        val parsed = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().parse(content)
        parsed.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                val title = StringBuilder()
                heading.accept(object : AbstractVisitor() {
                    override fun visit(text: Text) { title.append(text.literal) }
                    override fun visit(code: org.commonmark.node.Code) { title.append(code.literal) }
                })
                val line = (heading.sourceSpans.firstOrNull()?.lineIndex ?: 0) + 1
                headings.addElement(HeadingLocation("  ".repeat((heading.level - 1).coerceAtLeast(0)) + title, line))
                val key = title.toString().lowercase().replace(Regex("[^\\p{L}\\p{N}\\s_-]"), "").replace(Regex("\\s+"), "-")
                var unique = key; var duplicate = 1
                while (unique in anchors) unique = "$key-${duplicate++}"
                anchors[unique] = line
            }
        })
        content.lines().forEachIndexed { index, text ->
            Regex("(?i)<a\\s+(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']").findAll(text).forEach { anchors[it.groupValues[1]] = index + 1 }
        }
        if (line != null) goToLine(line)
    }

    fun goToAnchor(anchor: String): Boolean = anchors[anchor]?.let { goToLine(it); true } ?: false

    fun goToLine(line: Int) {
        if (disposed || line < 1 || line > editor.document.lineCount) return
        cards.show(body, "source")
        editor.caretModel.moveToOffset(editor.document.getLineStartOffset(line - 1))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    override fun dispose() { if (!disposed) { disposed = true; EditorSearchSession.get(editor)?.close(); EditorFactory.getInstance().releaseEditor(editor) } }
    private data class HeadingLocation(val title: String, val line: Int) { override fun toString() = title }
}

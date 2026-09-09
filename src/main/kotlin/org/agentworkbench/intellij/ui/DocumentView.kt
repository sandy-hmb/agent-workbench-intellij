package org.agentworkbench.intellij.ui

import org.commonmark.Extension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Text

internal object DocumentView {
    private val extensions: List<Extension> = listOf(TablesExtension.create(), TaskListItemsExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build()

    fun render(markdown: String): String {
        val safe = markdown.replace(Regex("(?i)\\]\\((?:javascript|data):[^)]*\\)"), "]()")
        val document = parser.parse(safe)
        document.accept(object : AbstractVisitor() {
            override fun visit(image: Image) {
                image.insertBefore(Text("[图片附件]"))
                image.unlink()
            }
        })
        return renderer.render(document)
    }
}

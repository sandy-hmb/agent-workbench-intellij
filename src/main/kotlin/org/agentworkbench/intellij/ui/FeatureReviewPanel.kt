package org.agentworkbench.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.agentworkbench.intellij.review.ReviewCandidate
import org.agentworkbench.intellij.review.ReviewRow
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/** Feature 变更页中的只读 PR/MR 入口。 */
internal class FeatureReviewPanel(
    private val project: Project,
    private val onRefresh: () -> Unit,
    private val onConfigure: () -> Unit,
    private val onChooseRemote: (String, String) -> Unit,
    private val onChooseCandidate: (String, String) -> Unit,
) : JPanel(BorderLayout()) {
    private val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private val refresh = JButton("刷新", AllIcons.Actions.Refresh).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        font = font.deriveFont(10.5f)
        foreground = WorkbenchUi.accent
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onRefresh() }
    }

    init {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(0xE5E7EB, 0x393B40), 1),
            JBUI.Borders.empty(8, 12),
        )
        val title = JLabel("代码评审").apply { font = font.deriveFont(Font.BOLD, 12.5f); foreground = WorkbenchUi.text }
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.WEST)
            add(refresh, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        render(emptyList())
    }

    fun render(rows: List<ReviewRow>) {
        body.removeAll()
        if (rows.isEmpty()) {
            body.add(messageRow("配置代码托管服务后，可按 Feature 工作分支查询 PR/MR。", configure = true))
        } else {
            rows.forEach { body.add(row(it)); body.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4))) }
        }
        body.revalidate()
        body.repaint()
    }

    private fun row(row: ReviewRow): JPanel = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, 0)
        val repository = JLabel(row.repository).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
            foreground = WorkbenchUi.muted
        }
        add(repository, BorderLayout.WEST)
        val content = when (row) {
            is ReviewRow.Loading -> JLabel("正在查询…")
            is ReviewRow.Setup -> action("${row.message} · 配置", onConfigure)
            is ReviewRow.Missing -> JLabel("未找到 PR/MR")
            is ReviewRow.Link -> reviewLink(row.review, row.stale, row.incomplete, row.observedAt.toString())
            is ReviewRow.Multiple -> action("发现 ${row.candidates.size} 个候选 · 选择", { chooseCandidate(row.repository, row.candidates) })
            is ReviewRow.RemoteChoice -> action("多个 remote · 选择", { chooseRemote(row.repository, row.remotes) })
            is ReviewRow.Error -> error(row)
        }
        add(content, BorderLayout.CENTER)
    }

    private fun reviewLink(review: ReviewCandidate, stale: Boolean, incomplete: String? = null, observedAt: String? = null): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
        isOpaque = false
        add(action("${reviewLabel(review)}${if (stale) "（上次结果）" else ""}") { BrowserUtil.browse(review.url) }.apply {
            toolTipText = listOfNotNull(review.title, "${review.sourceBranch} → ${review.targetBranch}", observedAt?.let { "查询：$it" }).joinToString("\n")
        })
        add(JLabel(stateLabel(review.state)).apply { font = font.deriveFont(10.5f); foreground = WorkbenchUi.muted })
        incomplete?.let { add(JLabel("结果不完整").apply { font = font.deriveFont(10.5f); foreground = JBColor(0xB45309, 0xF59E0B); toolTipText = it }) }
    }

    private fun error(row: ReviewRow.Error): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
        isOpaque = false
        add(JLabel(row.message).apply { font = font.deriveFont(10.5f); foreground = JBColor(0xB45309, 0xF59E0B) })
        row.stale?.let { add(reviewLink(it, true)) }
    }

    private fun messageRow(text: String, configure: Boolean): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
        isOpaque = false
        add(JLabel(text).apply { font = font.deriveFont(10.5f); foreground = WorkbenchUi.muted })
        if (configure) add(action("配置", onConfigure))
    }

    private fun action(text: String, callback: () -> Unit): JButton = JButton(text).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        font = font.deriveFont(10.5f)
        foreground = WorkbenchUi.accent
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { callback() }
    }

    private fun chooseRemote(repository: String, remotes: List<String>) {
        val selected = choose("选择 remote", "选择用于查询评审的 remote", remotes)
        if (selected >= 0) onChooseRemote(repository, remotes[selected])
    }

    private fun chooseCandidate(repository: String, candidates: List<ReviewCandidate>) {
        val labels = candidates.map { "${reviewLabel(it)} · ${stateLabel(it.state)} · ${it.sourceBranch} → ${it.targetBranch}" }
        val selected = choose("选择代码评审", "该工作分支存在多个评审，请选择本次查看的记录", labels)
        if (selected >= 0) onChooseCandidate(repository, candidates[selected].id)
    }

    private fun choose(title: String, message: String, choices: List<String>): Int {
        val selector = JComboBox(choices.toTypedArray())
        val dialog = object : DialogWrapper(project, true) {
            init { this.title = title; init() }
            override fun createCenterPanel() = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
                preferredSize = java.awt.Dimension(JBUI.scale(520), JBUI.scale(90))
                add(JLabel(message), BorderLayout.NORTH)
                add(selector, BorderLayout.CENTER)
            }
        }
        dialog.show()
        return if (dialog.isOK) selector.selectedIndex else -1
    }

    private fun reviewLabel(review: ReviewCandidate): String = "${if (review.url.contains("/merge_requests/")) "MR" else "PR"} ${review.number} · ${review.title}"
    private fun stateLabel(state: String): String = when (state.lowercase()) {
        "open", "opened", "reopened" -> "待合并"
        "merged" -> "已合并"
        "closed" -> "已关闭"
        else -> state
    }
}

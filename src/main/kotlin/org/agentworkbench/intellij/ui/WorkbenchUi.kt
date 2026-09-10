package org.agentworkbench.intellij.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.table.DefaultTableCellRenderer

/** 原型的间距与层级使用原生组件实现，颜色跟随宿主深浅主题。 */
internal object WorkbenchUi {
    val bg = JBColor(Color(0xffffff), Color(0x1e1f22))
    val surface = JBColor(Color(0xf7f8fa), Color(0x26282b))
    val border = JBColor(Color(0xdce0e7), Color(0x393b40))
    val text = JBColor(Color(0x242934), Color(0xdfdfdf))
    val muted = JBColor(Color(0x656e7d), Color(0x9da5b4))
    val faint = JBColor(Color(0x87909e), Color(0x6f737a))
    val accent = JBColor(Color(0x2c66bb), Color(0x3574f0))
    val selection = JBColor(Color(0xe5eefc), Color(0x2e436e))
    val green = JBColor(Color(0x207855), Color(0x59a869))
    val amber = JBColor(Color(0x97651c), Color(0xe5a158))
    val red = JBColor(Color(0xbd4156), Color(0xdb5c5c))
    val purple = JBColor(Color(0x7859a9), Color(0xb99bf8))

    fun label(value: String, size: Int = 13, color: Color = text, bold: Boolean = false) = JBLabel(value).apply {
        foreground = color; font = UIUtil.getLabelFont().deriveFont(if (bold) Font.BOLD else Font.PLAIN, JBUI.scale(size).toFloat())
        toolTipText = value
    }
    fun mono(value: String, color: Color = muted) = label(value, 11, color).apply { font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11)) }
    fun panel(layout: LayoutManager = BorderLayout()) = JPanel(layout).apply { background = bg; isOpaque = true }
    fun column(gap: Int = 0, vararg components: JComponent): JPanel = object:JPanel(BorderLayout()) {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE,preferredSize.height)
    }.apply {
        background=bg
        layout = VerticalFlow()
        components.forEachIndexed { i, component -> if (i > 0 && gap > 0) add(Box.createVerticalStrut(JBUI.scale(gap))); component.alignmentX = Component.LEFT_ALIGNMENT; add(component) }
    }
    fun append(column: JPanel, component: JComponent, gap: Int = 0) {
        if (gap > 0) column.add(Box.createVerticalStrut(JBUI.scale(gap)))
        component.alignmentX = Component.LEFT_ALIGNMENT; column.add(component)
    }
    fun row(left: JComponent, right: JComponent? = null, gap: Int = 12) = object:JPanel(BorderLayout(JBUI.scale(gap),0)) {
        override fun getMaximumSize()=Dimension(Int.MAX_VALUE,preferredSize.height)
    }.apply {
        background=bg
        add(left, BorderLayout.CENTER); right?.let { add(it, BorderLayout.EAST) }
    }
    fun flow(vararg components: JComponent) = panel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply { components.forEach(::add) }
    fun padded(content: JComponent, top: Int = 20, left: Int = 28, bottom: Int = 24, right: Int = 28) = panel().apply {
        border = JBUI.Borders.empty(top, left, bottom, right); add(content)
    }
    fun scroll(component: JComponent) = JBScrollPane(component).apply {
        border = JBUI.Borders.empty(); viewport.background = bg; background = bg; verticalScrollBar.unitIncrement = JBUI.scale(18)
        if(component is JTable) setColumnHeaderView(component.tableHeader)
    }
    fun page(content: JPanel) = scroll(WidthPanel().apply { add(content, BorderLayout.NORTH) })
    fun line() = panel().apply { background = WorkbenchUi.border; preferredSize = Dimension(1, JBUI.scale(1)); maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(1)) }
    fun section(title: String, action: JComponent? = null) = row(label(title, 14, bold = true), action).apply { border = JBUI.Borders.empty(0, 0, 14, 0) }
    fun copy(value: String) = object:JTextArea(value) {
        override fun getPreferredSize():Dimension {
            val available=width.takeIf { it>0 } ?: JBUI.scale(240)
            val metrics=getFontMetrics(font)
            var lines=0
            text.split('\n').forEach { paragraph ->
                if(paragraph.isEmpty()) lines++ else {
                    val attributed=java.text.AttributedString(paragraph).apply { addAttribute(java.awt.font.TextAttribute.FONT,font) }
                    val measure=java.awt.font.LineBreakMeasurer(attributed.iterator,java.awt.font.FontRenderContext(null,true,true))
                    while(measure.position<paragraph.length) { measure.nextLayout(available.coerceAtLeast(20).toFloat());lines++ }
                }
            }
            return Dimension(available,metrics.height*(lines.coerceAtLeast(1)+1)+4)
        }
    }.apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; background = bg; foreground = muted
        font = UIUtil.getLabelFont().deriveFont(Font.PLAIN,JBUI.scale(12).toFloat()); border = JBUI.Borders.empty(); rows = 0
    }
    fun button(value: String, link: Boolean = false, action: () -> Unit) = JButton(value).apply {
        foreground = if (link) accent else WorkbenchUi.text; background = surface; isOpaque = !link; isContentAreaFilled = !link
        isBorderPainted = !link; border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkbenchUi.border), JBUI.Borders.empty(6, 10))
        font = UIUtil.getLabelFont().deriveFont(Font.PLAIN,JBUI.scale(11).toFloat()); isFocusPainted = true; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
    }
    fun badge(value: String, color: Color = accent) = object : JLabel(value) {
        init {
            font = JBFont.regular().deriveFont(JBUI.scaleFontSize(10.5f))
            foreground = color
            isOpaque = false
            border = JBUI.Borders.empty(3, 8)
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bgCol = Color(color.red, color.green, color.blue, if (UIUtil.isUnderDarcula()) 45 else 28)
            val borderCol = Color(color.red, color.green, color.blue, if (UIUtil.isUnderDarcula()) 90 else 60)
            val arc = JBUI.scale(10)
            g2.color = bgCol
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = borderCol
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }
    fun metric(name: String, value: String, sub: String = "", color: Color = text, large: Boolean = false): JPanel = column(8,
        label(name, 11, muted), label(value, if (large) 26 else 14, color, true), label(sub, 10, faint))
    fun metrics(vararg values: JPanel) = panel(GridLayout(1, values.size, JBUI.scale(22), 0)).apply {
        values.forEachIndexed { index,value -> if(index>0) value.border=BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,1,0,0,WorkbenchUi.border),JBUI.Borders.emptyLeft(22));add(value) }; border = BorderFactory.createCompoundBorder(BottomLine(WorkbenchUi.border), JBUI.Borders.empty(0, 0, 22, 0))
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
    fun progress(completed: Int, total: Int) = JProgressBar(0, total.coerceAtLeast(1)).apply {
        value = completed; isStringPainted = false; foreground = accent; background = WorkbenchUi.border; border = JBUI.Borders.empty()
        preferredSize = Dimension(JBUI.scale(96), JBUI.scale(3)); maximumSize = preferredSize
        isVisible = total > 0
    }
    @JvmOverloads
    fun planProgress(completed: Int, total: Int, percentage: Boolean = false, trustedCompleted: Int? = null): String = when {
        total <= 0 -> "暂无计划"
        trustedCompleted != null && trustedCompleted != completed -> {
            val pct = if (percentage) " (${completed * 100 / total}%)" else ""
            "$completed / $total 项 (可信 $trustedCompleted)$pct"
        }
        percentage -> "$completed / $total (${completed * 100 / total}%)"
        else -> "$completed / $total 项"
    }
    fun table(table: JTable) {
        table.background = bg; table.foreground = text; table.gridColor = border; table.setShowGrid(false)
        table.rowHeight = JBUI.scale(44); table.intercellSpacing = Dimension(0, 1); table.selectionBackground = selection; table.selectionForeground = text
        table.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN,JBUI.scale(12).toFloat())
        table.tableHeader.background = surface; table.tableHeader.foreground = muted; table.tableHeader.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN,JBUI.scale(10).toFloat())
        table.tableHeader.preferredSize = Dimension(1, JBUI.scale(30)); table.fillsViewportHeight = true
        table.setDefaultRenderer(Object::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, col: Int): Component {
                super.getTableCellRendererComponent(t, value, selected, focused, row, col)
                font=t.font
                background = if (selected) selection else bg; foreground = if (col == 0) WorkbenchUi.text else muted
                border = BorderFactory.createCompoundBorder(BottomLine(WorkbenchUi.border), JBUI.Borders.empty(6, 10)); toolTipText = value?.toString()
                return this
            }
        })
    }
    fun input(field:JTextField) { field.background=surface;field.foreground=text;field.caretColor=text;field.border=BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(border),JBUI.Borders.empty(6,9));field.font=UIUtil.getLabelFont().deriveFont(Font.PLAIN,JBUI.scale(11).toFloat());field.minimumSize=Dimension(80,30) }
    fun combo(combo:JComboBox<*>) { combo.background=surface;combo.foreground=text;combo.font=UIUtil.getLabelFont().deriveFont(Font.PLAIN,JBUI.scale(11).toFloat());combo.border=BorderFactory.createLineBorder(border);combo.minimumSize=Dimension(100,30) }
    fun empty(title: String, explanation: String) = padded(column(10, label(title, 15, bold = true), copy(explanation)), 28, 20)
    fun status(value: String?) = when(value) { "planning" -> "规划中"; "development" -> "开发中"; "testing" -> "测试中"; "paused" -> "已暂停"; "done" -> "已完成"; else -> "未记录" }
    fun stage(value: String?) = when(value) {
        "feature.context" -> "读取上下文"; "feature.classify" -> "需求分级"; "feature.analyze" -> "跨仓分析"; "feature.design" -> "需求与技术设计"
        "feature.prepare-branch" -> "准备工作分支"; "feature.implement" -> "开发实现"; "feature.verify" -> "验证与审查"; "feature.submit-test" -> "提交测试"; "feature.complete" -> "需求完成"; null -> "历史记录"; else -> value
    }
    fun state(value: String?) = when(value) {
        "passed", "succeeded" -> "检查通过"; "failed" -> "检查失败"; "complete" -> "完整"; "incomplete" -> "记录不完整"; "legacy" -> "历史格式"; "missing" -> "未记录"
        "valid" -> "当前代码有效"; "invalid" -> "当前记录不适用"; "not_checked" -> "尚未核对"; "historical" -> "历史验证"
        "matched" -> "一致"; "changed" -> "已变化"; "unknown" -> "无法判断"; "running" -> "记录为运行中"; "skipped" -> "已跳过"; "removed" -> "已删除的历史步骤"
        "inactive" -> "未激活"; "drifted" -> "内容已漂移"; else -> value ?: "未记录"
    }
    fun stateColor(value: String?) = when(value) { "passed", "succeeded", "valid", "matched", "done" -> green; "failed" -> red; "invalid", "changed", "unknown", "incomplete", "drifted" -> amber; else -> muted }
}

private class VerticalFlow : LayoutManager {
    override fun addLayoutComponent(name:String?,component:Component)=Unit
    override fun removeLayoutComponent(component:Component)=Unit
    override fun minimumLayoutSize(parent:Container)=Dimension(0,0)
    override fun preferredLayoutSize(parent:Container):Dimension {
        val inset=parent.insets
        val available=(parent.width-inset.left-inset.right).takeIf { it>0 } ?: 300
        var height=0
        parent.components.filter { it.isVisible }.forEach { c ->
            c.setSize(available,c.height);if(c is Container)c.doLayout()
            height+=c.preferredSize.height
        }
        return Dimension(available+inset.left+inset.right,height+inset.top+inset.bottom)
    }
    override fun layoutContainer(parent:Container) {
        val inset=parent.insets;val width=(parent.width-inset.left-inset.right).coerceAtLeast(0);var y=inset.top
        parent.components.filter { it.isVisible }.forEach { c ->
            c.setSize(width,c.height);if(c is Container)c.doLayout()
            val height=c.preferredSize.height;c.setBounds(inset.left,y,width,height);y+=height
        }
    }
}

internal class BottomLine(private val color: Color) : AbstractBorder() {
    override fun getBorderInsets(c: Component) = Insets(0, 0, JBUI.scale(1), 0)
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) { g.color = color; g.drawLine(x, y + height - 1, x + width, y + height - 1) }
}

private class WidthPanel : JPanel(BorderLayout()), Scrollable {
    init { background=WorkbenchUi.bg }
    override fun getPreferredScrollableViewportSize() = preferredSize
    override fun getScrollableUnitIncrement(rect:Rectangle,orientation:Int,direction:Int)=JBUI.scale(18)
    override fun getScrollableBlockIncrement(rect:Rectangle,orientation:Int,direction:Int)=rect.height
    override fun getScrollableTracksViewportWidth()=true
    override fun getScrollableTracksViewportHeight()=false
}

internal class WorkbenchTabs : JPanel(BorderLayout()) {
    private val bar = WorkbenchUi.panel(FlowLayout(FlowLayout.LEFT, 14, 0))
    private val cards = JPanel(CardLayout()).apply { background = WorkbenchUi.bg }
    private val buttons = mutableListOf<JButton>()
    var onChange: (() -> Unit)? = null
    var selectedIndex = 0
        set(value) { if (value !in buttons.indices) return; field = value; (cards.layout as CardLayout).show(cards, value.toString()); updateSelection(); onChange?.invoke() }
    val tabCount get() = buttons.size
    init { background = WorkbenchUi.bg; bar.border = BottomLine(WorkbenchUi.border); add(bar, BorderLayout.NORTH); add(cards) }
    fun addTab(title: String, content: JComponent) {
        val index = buttons.size
        val button = WorkbenchUi.button(title, true) { selectedIndex = index }.apply { preferredSize = Dimension(preferredSize.width + 8, JBUI.scale(43)); name = "feature-tab-$index" }
        buttons += button; bar.add(button); cards.add(content, index.toString()); updateSelection()
    }
    fun titleAt(index: Int) = buttons[index].text
    fun setTitleAt(index: Int, value: String) { buttons[index].text = value }
    private fun updateSelection() { buttons.forEachIndexed { i, b -> b.foreground = if (i == selectedIndex) WorkbenchUi.text else WorkbenchUi.muted; b.isBorderPainted = i == selectedIndex; b.border = if (i == selectedIndex) BorderFactory.createMatteBorder(0,0,2,0,WorkbenchUi.accent) else JBUI.Borders.empty(0,0,2,0) } }
}
/**
 * Kit 目前用中文字面量表达审阅状态；集中在此处，Kit 一旦改为结构化枚举只需改这里。
 * 契约来源：agent-workbench/scripts/workspace_status.py 的 DOCUMENT_REVIEW_VALUES
 * （{"未生成","待审阅","已批准"}），由 feature README 的「需求/设计/计划审阅」元数据行解析而来。
 */
internal object KitSemantics {
    const val REVIEW_APPROVED = "已批准"
    fun reviewPending(value: String?): Boolean = value != null && value != REVIEW_APPROVED
}

internal fun JsonElement?.obj(): JsonObject? = if (this?.isJsonObject == true) asJsonObject else null
internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.obj()
internal fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
internal fun JsonObject.objects(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.obj() }.orEmpty()
internal fun JsonElement?.texts(): String = if (this?.isJsonArray == true) asJsonArray.joinToString { if (it.isJsonPrimitive) it.asString else "" } else ""
internal fun JsonElement?.text(): String = if (this == null || isJsonNull) "—" else if (isJsonPrimitive) asString else toString()

/** Kit 仓库 id -> 本地绝对路径；字段名与 inspect schema 的 $defs/repository（id/absolutePath）对齐。 */
internal fun repositoryRoots(repositories: List<JsonObject>): Map<String, java.nio.file.Path> =
    repositories.mapNotNull { repo ->
        val id = repo.str("id") ?: return@mapNotNull null
        val path = repo.str("absolutePath") ?: return@mapNotNull null
        id to java.nio.file.Path.of(path)
    }.toMap()

/** doctor findings[].remediation 是 {kind, detail} 对象或 null；返回可展示的一行文案。 */
internal fun remediationText(finding: JsonObject): String? {
    val remediation = finding.obj("remediation") ?: return null
    val detail = remediation.str("detail")?.takeIf(String::isNotBlank) ?: return null
    val caption = if (remediation.str("kind") == "command") "修复命令" else "修复建议"
    return "$caption：$detail"
}

internal class TimelineMarker(
    private val current: Boolean,
    private val extension: Boolean,
    private val stageTitle: String? = null,
    private val stageState: String? = null
) : JPanel() {
    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(22), 1)
        if (stageTitle != null) {
            toolTipText = buildString {
                append("<html><b>").append(stageTitle).append("</b>")
                if (stageState != null) append("<br/>状态: ").append(stageState)
                if (current) append("<br/><i>(当前阶段)</i>")
                append("</html>")
            }
        }
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val x = width / 2
        val y = JBUI.scale(14)
        val r = JBUI.scale(5)
        g.color = WorkbenchUi.border
        g.stroke = BasicStroke(JBUI.scale(1.5f))
        g.drawLine(x, 0, x, height)

        val markerColor = if (current) WorkbenchUi.accent else if (extension) WorkbenchUi.purple else WorkbenchUi.faint
        if (current) {
            // 外圈光晕扩散
            val glowColor = Color(markerColor.red, markerColor.green, markerColor.blue, if (UIUtil.isUnderDarcula()) 60 else 40)
            g.color = glowColor
            g.fillOval(x - r - JBUI.scale(3), y - r - JBUI.scale(3), (r + JBUI.scale(3)) * 2, (r + JBUI.scale(3)) * 2)
        }

        g.color = WorkbenchUi.bg
        g.fillOval(x - r, y - r, r * 2, r * 2)
        g.color = markerColor
        g.stroke = BasicStroke(JBUI.scale(if (current) 2f else 1.5f))
        if (extension) {
            val d = r + 1
            g.drawPolygon(intArrayOf(x, x + d, x, x - d), intArrayOf(y - d, y, y + d, y), 4)
        } else {
            g.drawOval(x - r, y - r, r * 2, r * 2)
        }
        g.dispose()
    }
}

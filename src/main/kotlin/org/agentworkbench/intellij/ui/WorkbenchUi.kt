package org.agentworkbench.intellij.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.table.DefaultTableCellRenderer

/** 原型的间距与层级使用原生组件实现，颜色跟随宿主深浅主题。 */
internal object WorkbenchUi {
    val bg = JBColor(Color(0xfafbfc), Color(0x181a1e))
    val surface = JBColor(Color(0xf0f2f5), Color(0x202226))
    val border = JBColor(Color(0xdce0e7), Color(0x34373e))
    val text = JBColor(Color(0x242934), Color(0xe1e3e8))
    val muted = JBColor(Color(0x656e7d), Color(0x959aa5))
    val faint = JBColor(Color(0x87909e), Color(0x676e7a))
    val accent = JBColor(Color(0x2c66bb), Color(0x76a7ff))
    val selection = JBColor(Color(0xe5eefc), Color(0x273850))
    val green = JBColor(Color(0x207855), Color(0x72c6a0))
    val amber = JBColor(Color(0x97651c), Color(0xe6bb76))
    val red = JBColor(Color(0xbd4156), Color(0xef8d93))
    val purple = JBColor(Color(0x7859a9), Color(0xb6a0e3))

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
    fun badge(value: String, color: Color = accent) = label(value, 10, color).apply {
        isOpaque = true; background = JBColor(Color(color.red, color.green, color.blue, 22), Color(color.red, color.green, color.blue, 25))
        border = JBUI.Borders.empty(3, 6)
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
internal fun JsonElement?.obj(): JsonObject? = if (this?.isJsonObject == true) asJsonObject else null
internal fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
internal fun JsonObject.objects(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.obj() }.orEmpty()
internal fun JsonElement?.texts(): String = if (this?.isJsonArray == true) asJsonArray.joinToString { if (it.isJsonPrimitive) it.asString else "" } else ""
internal fun JsonElement?.text(): String = if (this == null || isJsonNull) "—" else if (isJsonPrimitive) asString else toString()

internal class TimelineMarker(private val current:Boolean,private val extension:Boolean):JPanel() {
    init { isOpaque=false;preferredSize=Dimension(JBUI.scale(18),1) }
    override fun paintComponent(graphics:Graphics) {
        super.paintComponent(graphics)
        val g=graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
        val x=width/2;val y=JBUI.scale(13);g.color=WorkbenchUi.border;g.drawLine(x,0,x,height)
        g.color=WorkbenchUi.bg;g.fillOval(x-5,y-5,10,10);g.color=if(current) WorkbenchUi.accent else if(extension) WorkbenchUi.purple else WorkbenchUi.faint
        if(extension) g.drawPolygon(intArrayOf(x,x+5,x,x-5),intArrayOf(y-5,y,y+5,y),4) else g.drawOval(x-5,y-5,10,10)
        g.dispose()
    }
}

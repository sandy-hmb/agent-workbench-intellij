package org.agentworkbench.intellij.ui

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import org.agentworkbench.intellij.git.HostGit
import org.agentworkbench.intellij.git.NativeGit
import java.awt.BorderLayout
import java.nio.file.Path
import java.util.concurrent.Future
import javax.swing.*
import javax.swing.table.DefaultTableModel

internal class GitPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val checked=mutableSetOf<Path>()
    private val model = object : DefaultTableModel(arrayOf("", "仓库", "当前分支", "工作目录", "远程同步", "最近提交", ""), 0) {
        override fun isCellEditable(row:Int,column:Int)=column==0
        override fun getColumnClass(column:Int):Class<*> = if(column==0) java.lang.Boolean::class.java else Any::class.java
        override fun setValueAt(value:Any?,row:Int,column:Int) { super.setValueAt(value,row,column);if(column==0) visibleEntries.getOrNull(row)?.let { if(value==true) checked.add(it.root) else checked.remove(it.root) } }
    }
    private val table = JBTable(model)
    private val status = JBLabel("按工作区配置显示仓库；操作范围以当前可见选择为准。")
    private val search = JTextField()
    private val results = JTextArea().apply { isEditable = false; rows = 4 }
    private var entries = emptyList<Entry>()
    private var visibleEntries = emptyList<Entry>()
    private var generation = 0
    private var request: Future<*>? = null
    private var disposed = false
    private val host = HostGit(project)
    private val nativeRetry = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val scope = JComboBox(arrayOf("全部", "有变更", "需关注", "当前需求相关"))
    private var related=emptySet<String>()
    var onScenes: ((Map<String, NativeGit.Snapshot>) -> Unit)? = null
    init {
        background = WorkbenchUi.bg
        WorkbenchUi.table(table)
        table.rowHeight = com.intellij.util.ui.JBUI.scale(50)
        table.columnModel.getColumn(0).apply { minWidth=30;maxWidth=32 }
        table.columnModel.getColumn(1).preferredWidth=230
        table.columnModel.getColumn(2).preferredWidth=210
        table.columnModel.getColumn(6).apply { minWidth=40;maxWidth=50 }
        table.tableHeader.defaultRenderer=object:javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t:javax.swing.JTable,v:Any?,s:Boolean,f:Boolean,r:Int,c:Int):java.awt.Component {
                super.getTableCellRendererComponent(t,v,s,f,r,c);background=WorkbenchUi.surface;foreground=WorkbenchUi.muted;border=com.intellij.util.ui.JBUI.Borders.empty(7,10);font=WorkbenchUi.label("",10).font;return this
            }
        }
        table.columnModel.getColumn(1).cellRenderer=object:javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t:javax.swing.JTable,v:Any?,s:Boolean,f:Boolean,r:Int,c:Int):java.awt.Component {
                val entry=visibleEntries.getOrNull(r)
                val escape:(String)->String = com.intellij.openapi.util.text.StringUtil::escapeXmlEntities
                val faint=WorkbenchUi.faint.let { "%02x%02x%02x".format(it.red,it.green,it.blue) }
                super.getTableCellRendererComponent(t,"<html><b>${escape(entry?.id.orEmpty())}</b><br><font color='#$faint' size='2'>${escape(entry?.description.orEmpty())}</font></html>",s,f,r,c)
                border=javax.swing.BorderFactory.createCompoundBorder(BottomLine(WorkbenchUi.border),com.intellij.util.ui.JBUI.Borders.empty(7,10));background=if(s) WorkbenchUi.selection else WorkbenchUi.bg;foreground=WorkbenchUi.text;font=t.font
                return this
            }
        }
        results.background = WorkbenchUi.bg; results.foreground = WorkbenchUi.muted
        results.rows = 0
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        add(WorkbenchUi.row(WorkbenchUi.label("仓库",14,bold=true),WorkbenchUi.flow(scope,search)).apply { border=com.intellij.util.ui.JBUI.Borders.empty(0,0,12,0) }, BorderLayout.NORTH)
        search.preferredSize=java.awt.Dimension(160,30)
        WorkbenchUi.input(search);WorkbenchUi.combo(scope);status.foreground=WorkbenchUi.muted
        add(WorkbenchUi.scroll(table))
        add(WorkbenchUi.panel().apply {
            add(WorkbenchUi.panel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT,6,8)).apply {
                add(button("接入所选仓库", ::connect))
                for ((title, action) in listOf("Log" to "Vcs.Show.Log", "Diff" to "ChangesView.Diff", "Commit" to "CheckinProject", "Branches" to "Git.Branches", "冲突" to "Git.ResolveConflicts")) {
                    add(button(title) { single()?.let { entry -> connectAndOpen(entry, action) } })
                }
                add(button("Fetch 选中") { fetch(selected()) })
                add(button("Fetch 当前可见全部") { fetch(visibleEntries) })
            }, BorderLayout.NORTH)
            add(WorkbenchUi.column(6,status,results))
        }, BorderLayout.SOUTH)
        search.accessibleContext.accessibleName = "筛选 Git 仓库"
        scope.addActionListener { render() }
        table.addMouseListener(object:java.awt.event.MouseAdapter(){override fun mouseClicked(e:java.awt.event.MouseEvent){val row=table.rowAtPoint(e.point);val col=table.columnAtPoint(e.point);if(col==6&&row>=0) visibleEntries.getOrNull(row)?.let { connectAndOpen(it,"Vcs.Show.Log") }}})
        search.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = render()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = render()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = render()
        })
    }
    fun showRepositories(repositories: Iterable<JsonObject>) {
        request?.cancel(true)
        val current = ++generation
        entries = repositories.mapNotNull { item ->
            val path = item.get("absolutePath")?.asString ?: return@mapNotNull null
            Entry(item.get("id").asString, item.get("role").asString, Path.of(path), item.get("availability")?.asString ?: "unknown",description=item.get("description")?.asString.orEmpty())
        }.sortedBy { it.role=="kit" }
        render()
        val captured = entries
        request = ApplicationManager.getApplication().executeOnPooledThread {
            val git = NativeGit.forProject(project)
            val updated = captured.map { entry ->
                if (Thread.currentThread().isInterrupted) return@executeOnPooledThread
                entry.copy(scene = git.snapshot(entry.root.toFile()), remotes = git.remotes(entry.root.toFile()),lastCommit=git.lastCommitTime(entry.root.toFile()))
            }
            ApplicationManager.getApplication().invokeLater { if (!disposed && current == generation) { entries = updated; render(); onScenes?.invoke(updated.mapNotNull { e -> e.scene?.let { e.id to it } }.toMap()) } }
        }
    }
    private fun render() {
        val selectedPaths = selected().map { it.root }.toSet()
        visibleEntries = entries.filter { entry -> entry.id.contains(search.text.trim(), true) && when(scope.selectedIndex) {
            1 -> (entry.scene as? NativeGit.Snapshot.Available)?.changes?.let { it>0 }==true
            2 -> entry.availability!="present" || entry.scene is NativeGit.Snapshot.Unavailable || (entry.scene as? NativeGit.Snapshot.Available)?.conflicts==true
            3 -> entry.id in related
            else -> true
        } }
        model.rowCount = 0
        visibleEntries.forEachIndexed { index, entry ->
            val scene = entry.scene as? NativeGit.Snapshot.Available
            val work=when { scene==null -> "无法读取";scene.conflicts -> "! 有冲突";scene.changes>0 -> "${scene.changes} 个变更";else -> "✓ 干净" }
            val sync=when { scene==null -> "未知";scene.upstream==null -> "未设置上游";scene.ahead==0&&scene.behind==0 -> "✓ 已同步";else -> "↑ ${scene.ahead?:"?"}   ↓ ${scene.behind?:"?"}" }
            val time=entry.lastCommit?.let { runCatching { java.time.OffsetDateTime.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrNull() } ?: "未记录"
            model.addRow(arrayOf<Any>(entry.root in checked,entry.id,scene?.branch ?: "—",work,sync,time,"›"))
            if (entry.root in selectedPaths) table.addRowSelectionInterval(index, index)
        }
        status.text = "可见 ${visibleEntries.size} 个仓库；选中 ${table.selectedRowCount} 个"
    }
    fun filter(value: String) { scope.selectedIndex=when(value){"changed"->1;"attention"->2;"related"->3;else->0} }
    fun setRelated(ids:Set<String>) { related=ids;if(scope.selectedIndex==3) render() }
    fun focusRepository(id: String) { search.text=id; scope.selectedIndex=0; render(); if(table.rowCount>0) table.setRowSelectionInterval(0,0) }
    fun fetchVisible() = fetch(visibleEntries)
    private fun selected() = visibleEntries.filter { it.root in checked }.ifEmpty { table.selectedRows.toList().mapNotNull { visibleEntries.getOrNull(it) } }
    private fun single(): Entry? {
        val chosen = selected()
        if (chosen.size != 1) { status.text = "此操作需要选择唯一仓库"; return null }
        return chosen.single()
    }
    private fun connect() {
        val roots = selected().filter { it.availability == "present" }.map { it.root }
        if (roots.isEmpty()) return
        if (Messages.showYesNoDialog(project, roots.joinToString("\n") + "\n\n将这些仓库增补到当前宿主的 Git 映射？", "接入仓库", Messages.getQuestionIcon()) != Messages.YES) return
        runCatching { host.registerRoots(roots) }.fold({ status.text = "已新增 ${it.size} 个 Git 根映射" }, { status.text = it.message })
    }
    private fun connectAndOpen(entry: Entry, action: String) {
        if (!host.isRegistered(entry.root)) {
            val answer = Messages.showYesNoDialog(project, "${entry.root}\n\n该仓库尚未接入当前 IDEA 项目，是否接入并继续？", "接入并继续", Messages.getQuestionIcon())
            if (answer != Messages.YES) return
            runCatching { host.registerRoots(listOf(entry.root)) }.onFailure { status.text = it.message; return }
        }
        openWhenReady(entry, action, 0)
    }
    private fun openWhenReady(entry: Entry, action: String, attempt: Int) {
        host.openNative(action, entry.root).onFailure {
            if (it is HostGit.UnrecognizedRepository && attempt < 20) {
                status.text = "正在等待 IDEA 接入 ${entry.id}…"
                nativeRetry.addRequest({ openWhenReady(entry, action, attempt + 1) }, 100)
            } else status.text = it.message
        }
    }
    private fun fetch(candidates: List<Entry>) {
        val targets = mutableListOf<Pair<Entry, String>>()
        val skipped = mutableListOf<String>()
        for (entry in candidates.toList()) {
            if (entry.availability != "present" || entry.scene !is NativeGit.Snapshot.Available || entry.remotes.isEmpty()) { skipped += "${entry.id}：仓库或 remote 不可用"; continue }
            val remote = if (entry.remotes.size == 1) entry.remotes.single() else {
                Messages.showEditableChooseDialog("选择 ${entry.id} 的 remote", "Fetch remote", Messages.getQuestionIcon(), entry.remotes.toTypedArray(), entry.remotes.first(), null) ?: return
            }
            if (remote !in entry.remotes) { status.text = "remote 必须来自该仓现有配置"; return }
            targets += entry to remote
        }
        results.text = skipped.joinToString("\n")
        results.rows = if(skipped.isEmpty()) 0 else 3
        if (targets.isEmpty()) return
        val description = targets.joinToString("\n") { (entry, remote) -> "${entry.id} → $remote\n${entry.root}" }
        if (Messages.showYesNoDialog(project, description, "Fetch ${targets.size} 个仓库", Messages.getQuestionIcon()) != Messages.YES) return
        ApplicationManager.getApplication().executeOnPooledThread {
            for ((entry, remote) in targets) {
                if (disposed) break
                val outcome = runCatching { host.fetchAsync(entry.root, remote).get().getOrThrow() }
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) { results.rows=3;results.append("\n${entry.id}：" + outcome.fold({ "Fetch 成功" }, { it.message ?: "Fetch 失败" }));revalidate() }
                }
            }
        }
    }
    override fun dispose() { disposed = true; generation++; request?.cancel(true) }
    private fun button(label: String, block: () -> Unit) = WorkbenchUi.button(label, action=block)
    private data class Entry(val id: String, val role: String, val root: Path, val availability: String, val scene: NativeGit.Snapshot? = null, val remotes: List<String> = emptyList(),val description:String="",val lastCommit:String?=null)
}

package org.agentworkbench.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SearchTextField
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import org.agentworkbench.intellij.WorkbenchNotifier
import org.agentworkbench.intellij.git.GitScanService
import org.agentworkbench.intellij.git.HostGit
import org.agentworkbench.intellij.git.NativeGit
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.*
import javax.swing.table.DefaultTableModel

/** 仓库视图：数据来自项目级 GitScanService；行级 Git 操作通过右键菜单与双击触发。 */
internal class GitPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val model = object : DefaultTableModel(arrayOf("仓库", "当前分支", "工作目录", "远程同步", "最近提交"), 0) {
        override fun isCellEditable(row:Int,column:Int)=false
    }
    private val table: JBTable = object : JBTable(model) {
        override fun getToolTipText(e: MouseEvent): String? {
            val row = rowAtPoint(e.point).takeIf { it >= 0 } ?: return null
            val col = columnAtPoint(e.point).takeIf { it >= 0 } ?: return null
            val modelRow = convertRowIndexToModel(row).takeIf { it in 0 until visibleEntries.size } ?: return null
            val entry = visibleEntries[modelRow]
            val scene = entry.scene as? NativeGit.Snapshot.Available ?: return null
            return when (col) {
                2 -> {
                    when {
                        scene.conflicts -> "<html><font color='red'><b>存在未解决冲突</b></font><br/>双击或右键「解决冲突」进入三方合并器</html>"
                        scene.changes > 0 && scene.dirtyFiles.isNotEmpty() -> {
                            buildString {
                                append("<html><b>工作目录变更 (${scene.changes} 个文件):</b><br/>")
                                scene.dirtyFiles.take(8).forEach { f ->
                                    val esc = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(f)
                                    append("• ").append(esc).append("<br/>")
                                }
                                if (scene.changes > 8) append("<i>...等共 ${scene.changes} 个文件 (双击查看 Diff)</i>")
                                else append("<i>(双击查看 Diff)</i>")
                                append("</html>")
                            }
                        }
                        else -> "工作目录干净 (双击查看 Log)"
                    }
                }
                3 -> {
                    if ((scene.ahead ?: 0) > 0 || (scene.behind ?: 0) > 0) {
                        "<html><b>远程同步状态:</b><br/>• 超前本地提交: ${scene.ahead ?: 0} 个 (可点击 Push)<br/>• 落后远端提交: ${scene.behind ?: 0} 个 (可点击 Pull)</html>"
                    } else "已与远端完全同步"
                }
                else -> null
            }
        }
    }
    private val status = JBLabel("按工作区配置显示仓库；双击查看 Log，右键执行 Git 操作。")
    private val search = SearchTextField(false)
    private var entries = emptyList<GitScanService.Scan>()
    private var visibleEntries = emptyList<GitScanService.Scan>()
    private var scanning = false
    private var disposed = false
    private val host = HostGit(project)
    private val nativeRetry = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val scope = JComboBox(arrayOf("全部", "有变更", "需关注", "当前需求相关"))
    private var related=emptySet<String>()
    private val logAction = gitAction("Log", AllIcons.Vcs.History) { single()?.let { connectAndOpen(it, "Vcs.Show.Log") } }
    private val diffAction = gitAction("Diff", AllIcons.Actions.Diff) { single()?.let { connectAndOpen(it, "ChangesView.Diff") } }
    private val commitAction = gitAction("Commit", AllIcons.Vcs.CommitNode) { single()?.let { connectAndOpen(it, "CheckinProject") } }
    private val pushAction = gitAction("Push…", AllIcons.Actions.Upload) { single()?.let { connectAndOpen(it, "Vcs.Push") } }
    private val pullAction = gitAction("Pull…", AllIcons.Actions.CheckOut) { single()?.let { connectAndOpen(it, "Git.Pull") } }
    private val branchesAction = gitAction("Branches", AllIcons.Vcs.Branch) { single()?.let { connectAndOpen(it, "Git.Branches") } }
    private val fetchAction = gitAction("Fetch", AllIcons.Actions.Download) { fetch(selected()) }
    init {
        background = WorkbenchUi.bg
        WorkbenchUi.table(table)
        table.rowHeight = com.intellij.util.ui.JBUI.scale(50)
        table.autoCreateRowSorter = true
        TableSpeedSearch.installOn(table)
        table.columnModel.getColumn(0).preferredWidth=230
        table.columnModel.getColumn(1).preferredWidth=210
        table.tableHeader.defaultRenderer=object:javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t:javax.swing.JTable,v:Any?,s:Boolean,f:Boolean,r:Int,c:Int):java.awt.Component {
                super.getTableCellRendererComponent(t,v,s,f,r,c);background=WorkbenchUi.surface;foreground=WorkbenchUi.muted;border=com.intellij.util.ui.JBUI.Borders.empty(7,10);font=WorkbenchUi.label("",10).font;return this
            }
        }
        table.columnModel.getColumn(0).cellRenderer=object:javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t:javax.swing.JTable,v:Any?,s:Boolean,f:Boolean,r:Int,c:Int):java.awt.Component {
                val entry=visibleEntries.getOrNull(t.convertRowIndexToModel(r))
                val escape:(String)->String = com.intellij.openapi.util.text.StringUtil::escapeXmlEntities
                val faint=WorkbenchUi.faint.let { "%02x%02x%02x".format(it.red,it.green,it.blue) }
                super.getTableCellRendererComponent(t,"<html><b>${escape(entry?.id.orEmpty())}</b><br><font color='#$faint' size='2'>${escape(entry?.description.orEmpty())}</font></html>",s,f,r,c)
                border=javax.swing.BorderFactory.createCompoundBorder(BottomLine(WorkbenchUi.border),com.intellij.util.ui.JBUI.Borders.empty(7,10));background=if(s) WorkbenchUi.selection else WorkbenchUi.bg;foreground=WorkbenchUi.text;font=t.font
                return this
            }
        }
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.emptyText.text = "暂无仓库"
        (search.textEditor as? JBTextField)?.emptyText?.text = "搜索仓库"
        search.preferredSize = java.awt.Dimension(com.intellij.util.ui.JBUI.scale(200), com.intellij.util.ui.JBUI.scale(30))
        add(WorkbenchUi.row(WorkbenchUi.label("仓库",14,bold=true),WorkbenchUi.flow(scope,search)).apply { border=com.intellij.util.ui.JBUI.Borders.empty(0,0,12,0) }, BorderLayout.NORTH)
        WorkbenchUi.combo(scope);status.foreground=WorkbenchUi.muted
        add(WorkbenchUi.scroll(table))
        add(WorkbenchUi.column(6,
            WorkbenchUi.flow(logAction,diffAction,commitAction,pushAction,pullAction,branchesAction,fetchAction).apply { border=com.intellij.util.ui.JBUI.Borders.emptyTop(8) },
            WorkbenchUi.padded(status,2,0,0,0)
        ), BorderLayout.SOUTH)
        search.accessibleContext.accessibleName = "筛选 Git 仓库"
        scope.addActionListener { render() }
        table.addMouseListener(object:java.awt.event.MouseAdapter(){
            override fun mouseClicked(e:MouseEvent){
                if(e.clickCount==2&&SwingUtilities.isLeftMouseButton(e)) entryAt(e)?.let { entry ->
                    val scene = entry.scene as? NativeGit.Snapshot.Available
                    when {
                        scene?.conflicts == true -> connectAndOpen(entry, "Git.ResolveConflicts")
                        (scene?.changes ?: 0) > 0 -> connectAndOpen(entry, "ChangesView.Diff")
                        else -> connectAndOpen(entry, "Vcs.Show.Log")
                    }
                }
            }
            override fun mousePressed(e:MouseEvent)=maybePopup(e)
            override fun mouseReleased(e:MouseEvent)=maybePopup(e)
        })
        table.selectionModel.addListSelectionListener { if(!it.valueIsAdjusting&&!scanning) updateStatus() }
        search.textEditor.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = render()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = render()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = render()
        })
        val scanService = GitScanService.getInstance(project)
        scanService.subscribe(this) { scans, active -> if (!disposed) { entries = scans; scanning = active; render() } }
        entries = scanService.scans()
        render()
    }
    private var lastRenderFingerprint: String? = null

    private fun render() {
        val selectedPaths = selected().map { it.root }.toSet()
        table.emptyText.text = when (scope.selectedIndex) {
            1 -> "暂无有变更的仓库"
            2 -> "所有仓库均正常，无冲突或异常"
            3 -> "暂无关联仓库"
            else -> "暂无仓库"
        }
        visibleEntries = entries.filter { entry -> entry.id.contains(search.text.trim(), true) && when(scope.selectedIndex) {
            1 -> (entry.scene as? NativeGit.Snapshot.Available)?.changes?.let { it>0 }==true
            2 -> entry.availability!="present" || entry.scene is NativeGit.Snapshot.Unavailable || (entry.scene as? NativeGit.Snapshot.Available)?.conflicts==true
            3 -> entry.id in related
            else -> true
        } }

        val currentFingerprint = buildString {
            append(scope.selectedIndex).append(';')
            append(search.text.trim()).append(';')
            visibleEntries.forEach { e ->
                val s = e.scene as? NativeGit.Snapshot.Available
                append(e.id).append(':').append(s?.branch).append(':').append(s?.changes).append(':').append(s?.ahead).append(':').append(s?.behind).append(':').append(e.lastCommit).append(';')
            }
        }
        if (currentFingerprint == lastRenderFingerprint && model.rowCount == visibleEntries.size) {
            updateStatus()
            return
        }
        lastRenderFingerprint = currentFingerprint

        val tableScroll = table.parent as? JViewport
        val savedScroll = tableScroll?.viewPosition

        model.rowCount = 0
        visibleEntries.forEachIndexed { index, entry ->
            val scene = entry.scene as? NativeGit.Snapshot.Available
            val reading = entry.scene == null
            val work=when { reading -> "读取中…";scene==null -> "无法读取";scene.conflicts -> "! 有冲突";scene.changes>0 -> "${scene.changes} 个变更";else -> "✓ 干净" }
            val sync=when { reading -> "读取中…";scene==null -> "未知";scene.upstream==null -> "未设置上游";scene.ahead==0&&scene.behind==0 -> "✓ 已同步";else -> "↑ ${scene.ahead?:"?"}   ↓ ${scene.behind?:"?"}" }
            val time=entry.lastCommit?.let { runCatching { java.time.OffsetDateTime.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrNull() } ?: if (reading) "…" else "未记录"
            model.addRow(arrayOf<Any>(entry.id,scene?.branch ?: if (reading) "…" else "—",work,sync,time))
            if (entry.root in selectedPaths) { val view=table.convertRowIndexToView(index); table.addRowSelectionInterval(view, view) }
        }
        if (savedScroll != null) {
            SwingUtilities.invokeLater { tableScroll?.viewPosition = savedScroll }
        }
        updateStatus()
    }
    private fun updateStatus() {
        val selectedCount = table.selectedRowCount
        listOf(logAction, diffAction, commitAction, pushAction, pullAction, branchesAction).forEach { it.isEnabled = !scanning && selectedCount == 1 }
        fetchAction.isEnabled = !scanning && selectedCount > 0
        fetchAction.text = if (selectedCount > 1) "Fetch $selectedCount" else "Fetch"
        val singleEntry = if (selectedCount == 1) selected().singleOrNull() else null
        val scene = singleEntry?.scene as? NativeGit.Snapshot.Available
        val hint = when {
            scene?.conflicts == true -> "检测到冲突：双击或右键「解决冲突」进入合并器。"
            (scene?.changes ?: 0) > 0 -> "双击查看 Diff，右键可 Push/Pull/定位目录。"
            else -> "双击查看 Log，右键可 Push/Pull/定位目录。"
        }
        status.text = if (scanning) {
            "正在读取 ${entries.size} 个仓库的 Git 现场…"
        } else if (scope.selectedIndex == 2 && visibleEntries.isEmpty()) {
            "所有仓库 Git 现场均正常（无冲突或目录不可用）。若全局指标有需关注项，请查看下方「当前关注」列表。"
        } else {
            "可见 ${visibleEntries.size} 个仓库；选中 $selectedCount 个。$hint"
        }
    }
    private fun gitAction(text: String, icon: Icon, action: () -> Unit) = WorkbenchUi.button(text, action = action).apply {
        this.icon = icon
        toolTipText = text
    }
    fun filter(value: String) { scope.selectedIndex=when(value){"changed"->1;"attention"->2;"related"->3;else->0} }
    fun setRelated(ids:Set<String>) { related=ids;if(scope.selectedIndex==3) render() }
    fun focusRepository(id: String) { search.text=id; scope.selectedIndex=0; render(); if(table.rowCount>0) table.setRowSelectionInterval(0,0) }
    fun fetchVisible() = fetch(visibleEntries)
    private fun entryAt(e: MouseEvent): GitScanService.Scan? {
        val row = table.rowAtPoint(e.point)
        if (row < 0) return null
        return visibleEntries.getOrNull(table.convertRowIndexToModel(row))
    }
    private fun maybePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        if (row >= 0 && row !in table.selectedRows) table.setRowSelectionInterval(row, row)
        if (table.selectedRowCount == 0) return
        popupMenu().show(table, e.x, e.y)
    }
    private fun popupMenu(): JPopupMenu {
        val menu = JPopupMenu()
        fun item(label: String, action: () -> Unit) { menu.add(JMenuItem(label).apply { addActionListener { action() } }) }
        item("Log") { single()?.let { connectAndOpen(it, "Vcs.Show.Log") } }
        item("Diff") { single()?.let { connectAndOpen(it, "ChangesView.Diff") } }
        item("Commit…") { single()?.let { connectAndOpen(it, "CheckinProject") } }
        item("Push…") { single()?.let { connectAndOpen(it, "Vcs.Push") } }
        item("Pull…") { single()?.let { connectAndOpen(it, "Git.Pull") } }
        item("Branches…") { single()?.let { connectAndOpen(it, "Git.Branches") } }
        if (selected().any { (it.scene as? NativeGit.Snapshot.Available)?.conflicts == true }) {
            item("解决冲突…") { single()?.let { connectAndOpen(it, "Git.ResolveConflicts") } }
        }
        menu.addSeparator()
        item("在工程树中定位 (Select in Project View)") {
            single()?.let { host.selectInProjectView(it.root).onFailure { err -> status.text = err.message } }
        }
        item("在此处打开终端 (Open in Terminal)") {
            single()?.let { host.openInTerminal(it.root).onFailure { err -> status.text = err.message } }
        }
        menu.addSeparator()
        val chosen = selected()
        item(if (chosen.size > 1) "Fetch ${chosen.size} 个仓库" else "Fetch 此仓库") { fetch(selected()) }
        item("接入到当前项目") { connect() }
        return menu
    }
    private fun selected() = table.selectedRows.map(table::convertRowIndexToModel).mapNotNull { visibleEntries.getOrNull(it) }
    private fun single(): GitScanService.Scan? {
        val chosen = selected()
        if (chosen.size != 1) { status.text = "此操作需要选择唯一仓库"; return null }
        return chosen.single()
    }
    private fun connect() {
        val roots = selected().filter { it.availability == "present" }.map { it.root }
        if (roots.isEmpty()) return
        if (Messages.showYesNoDialog(project, roots.joinToString("\n") + "\n\n将这些仓库增补到当前宿主的 Git 映射？", "接入仓库", Messages.getQuestionIcon()) != Messages.YES) return
        status.text = "正在接入 ${roots.size} 个仓库…"
        registerThenRun(roots) { added -> status.text = "已新增 ${added.size} 个 Git 根映射" }
    }
    private fun connectAndOpen(entry: GitScanService.Scan, action: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val registered = runCatching { host.isRegistered(entry.root) }.getOrDefault(false)
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                if (registered) { openWhenReady(entry, action, 0); return@invokeLater }
                val answer = Messages.showYesNoDialog(project, "${entry.root}\n\n该仓库尚未接入当前 IDEA 项目，是否接入并继续？", "接入并继续", Messages.getQuestionIcon())
                if (answer != Messages.YES) return@invokeLater
                registerThenRun(listOf(entry.root)) { openWhenReady(entry, action, 0) }
            }
        }
    }
    /** 路径解析在后台完成，映射登记与后续操作回到 EDT。 */
    private fun registerThenRun(roots: List<Path>, next: (List<Path>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val canonical = runCatching { host.canonicalGitRoots(roots) }
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                canonical.mapCatching(host::applyMappings).fold(next) { status.text = it.message }
            }
        }
    }
    private fun openWhenReady(entry: GitScanService.Scan, action: String, attempt: Int) {
        host.openNative(action, entry.root).onFailure {
            if (it is HostGit.UnrecognizedRepository && attempt < 20) {
                status.text = "正在等待 IDEA 接入 ${entry.id}…"
                nativeRetry.addRequest({ openWhenReady(entry, action, attempt + 1) }, 100)
            } else status.text = it.message
        }
    }
    private fun fetch(candidates: List<GitScanService.Scan>) {
        val targets = mutableListOf<Pair<GitScanService.Scan, String>>()
        val skipped = mutableListOf<String>()
        for (entry in candidates.toList()) {
            if (entry.availability != "present" || entry.scene !is NativeGit.Snapshot.Available || entry.remotes.isEmpty()) { skipped += "${entry.id}：仓库或 remote 不可用"; continue }
            val remote = if (entry.remotes.size == 1) entry.remotes.single() else {
                Messages.showEditableChooseDialog("选择 ${entry.id} 的 remote", "选择 Remote", Messages.getQuestionIcon(), entry.remotes.toTypedArray(), entry.remotes.first(), null) ?: return
            }
            if (remote !in entry.remotes) { status.text = "remote 必须来自该仓现有配置"; return }
            targets += entry to remote
        }
        if (skipped.isNotEmpty()) status.text = "已跳过：" + skipped.joinToString("；")
        if (targets.isEmpty()) return
        val description = targets.joinToString("\n") { (entry, remote) -> "${entry.id} → $remote\n${entry.root}" }
        if (Messages.showYesNoDialog(project, description, "Fetch ${targets.size} 个仓库", Messages.getQuestionIcon()) != Messages.YES) return
        object : Task.Backgroundable(project, "Fetch ${targets.size} 个仓库", true) {
            override fun run(indicator: ProgressIndicator) {
                var succeeded = 0
                val failures = mutableListOf<String>()
                for ((entry, remote) in targets) {
                    if (disposed) return
                    indicator.checkCanceled()
                    indicator.text = "Fetch ${entry.id} ← $remote"
                    host.fetch(entry.root, remote).fold({ succeeded++ }, { failures += "${entry.id}：${it.message ?: "Fetch 失败"}" })
                }
                if (failures.isEmpty()) WorkbenchNotifier.info(project, "Fetch 完成", "$succeeded 个仓库 Fetch 成功。")
                else WorkbenchNotifier.warn(project, "Fetch 完成，${failures.size} 个失败", (listOf("$succeeded 个成功") + failures + skipped).joinToString("\n"))
            }
        }.queue()
    }
    override fun dispose() { disposed = true }
}

package org.agentworkbench.intellij.ui

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import org.agentworkbench.intellij.git.HostGit
import org.agentworkbench.intellij.git.NativeGit
import java.awt.BorderLayout
import java.awt.GridLayout
import java.nio.file.Path
import java.util.concurrent.Future
import javax.swing.*
import org.agentworkbench.intellij.ui.WorkbenchUi as U

/** 需求分支的已提交变更不取决于实际检出的分支。 */
internal class FeatureChangesPanel(private val project:Project):JPanel(BorderLayout()),Disposable {
    private val repository=JComboBox<String>()
    private val scope=U.column()
    private val message=U.label("选择需求后查看记录分支的已提交变更",12,U.muted)
    private val files=DefaultListModel<String>()
    private val commits=DefaultListModel<String>()
    private val diff=U.button("打开已提交 Diff") { selected?.let { (root,comparison) -> HostGit(project).showCommittedDiff(root,comparison.mergeBase,comparison.work).onFailure { message.text=it.message } } }
    private var bindings=emptyList<JsonObject>()
    private var roots=emptyMap<String,Path>()
    private var selected:Pair<Path,NativeGit.Comparison.Available>?=null
    private var generation=0
    private var future:Future<*>?=null
    private var disposed=false
    init {
        background=U.bg;U.combo(repository)
        add(U.column(18,U.row(U.flow(U.label("关联仓库",11,U.muted),repository),diff),scope,message).apply { border=com.intellij.util.ui.JBUI.Borders.empty(22,0) },BorderLayout.NORTH)
        val fileList=JBList(files).apply { background=U.bg;foreground=U.text;fixedCellHeight=32;border=com.intellij.util.ui.JBUI.Borders.empty(8);font=U.mono("").font }
        val commitList=JBList(commits).apply { background=U.bg;foreground=U.muted;fixedCellHeight=32;border=com.intellij.util.ui.JBUI.Borders.empty(8);font=U.mono("").font }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT,U.panel().apply { add(U.section("变更文件"),BorderLayout.NORTH);add(U.scroll(fileList)) },U.panel().apply { add(U.section("分支提交"),BorderLayout.NORTH);add(U.scroll(commitList)) }).apply { resizeWeight=0.55;border=com.intellij.util.ui.JBUI.Borders.empty();background=U.bg })
        diff.isEnabled=false;repository.addActionListener { load() }
    }
    fun showFeature(feature:JsonObject,repositories:List<JsonObject>) {
        bindings=feature.getAsJsonObject("summary").getAsJsonArray("repositoryBindings").map { it.asJsonObject }
        roots=repositories.associate { it.get("id").asString to Path.of(it.get("absolutePath").asString) }
        repository.removeAllItems();bindings.forEach { repository.addItem(it.str("repository")) }
    }
    private fun load() {
        future?.cancel(true);val request=++generation;selected=null;diff.isEnabled=false;files.clear();commits.clear()
        val binding=bindings.firstOrNull { it.str("repository")==repository.selectedItem }?:return
        val name=binding.str("repository")?:return;val root=roots[name]?:return
        val base=binding.str("baseBranch");val work=binding.str("workBranch")
        if(base==null||work==null) { message.text="需求未记录完整工作分支和基线";return }
        message.text="正在比较 $name：$base → $work"
        future=ApplicationManager.getApplication().executeOnPooledThread {
            val git=NativeGit.forProject(project);val scene=git.snapshot(root.toFile())
            val comparison=runCatching { git.comparison(root.toFile(),base,work) }.getOrElse { NativeGit.Comparison.Unavailable(it.message?:"比较不可用") }
            ApplicationManager.getApplication().invokeLater {
                if(disposed||generation!=request) return@invokeLater
                scope.removeAll();U.append(scope,U.metrics(U.metric("记录基线",base),U.metric("需求分支",work),U.metric("实际检出",(scene as? NativeGit.Snapshot.Available)?.branch?:"不可用")))
                when(comparison) {
                    is NativeGit.Comparison.Unavailable -> { message.text=comparison.reason;message.foreground=U.amber }
                    is NativeGit.Comparison.Available -> {
                        selected=root to comparison;diff.isEnabled=true;message.foreground=U.muted
                        message.text="${comparison.commits.size} 个提交 · ${comparison.files.size} 个文件 · 工作目录修改不包含在此范围"
                        comparison.files.forEach(files::addElement);comparison.commits.forEach(commits::addElement)
                    }
                }
                revalidate();repaint()
            }
        }
    }
    override fun dispose(){disposed=true;generation++;future?.cancel(true)}
}

package org.agentworkbench.intellij.ui

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import org.agentworkbench.intellij.git.HostGit
import org.agentworkbench.intellij.git.NativeGit
import org.agentworkbench.intellij.WorkbenchNotifier
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Future
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import org.agentworkbench.intellij.ui.WorkbenchUi as U

/** 需求分支的已提交变更不取决于实际检出的分支。 */
internal class FeatureChangesPanel(private val project:Project):JPanel(BorderLayout()),Disposable {
    private val repository=JComboBox<String>()
    private val scope=U.column()
    private val message=U.label("选择需求后查看记录分支的已提交变更",12,U.muted)
    private val files=DefaultListModel<String>()
    private val commits=DefaultListModel<String>()
    private val fetchBtn: javax.swing.JButton = U.button("抓取远程 (Fetch)") {
        val repoName = repository.selectedItem as? String ?: return@button
        val root = roots[repoName] ?: return@button
        fetchBtn.isEnabled = false
        fetchBtn.text = "正在 Fetch…"

        HostGit(project).fetchAsync(root, "origin") { result ->
            fetchBtn.isEnabled = true
            fetchBtn.text = "抓取远程 (Fetch)"
            result.fold(
                onSuccess = {
                    WorkbenchNotifier.info(project, "Fetch 完成", "仓库 $repoName 远程分支已更新到最新。")
                    load()
                },
                onFailure = { err ->
                    WorkbenchNotifier.warn(project, "Fetch 失败", err.message ?: "未知错误")
                }
            )
        }
    }.apply {
        icon = AllIcons.Actions.Refresh
        toolTipText = "从远程 origin 抓取最新分支与提交并自动刷新比对结果（无需切换本地检出分支）"
    }
    private val diff: javax.swing.JButton = U.button("打开已提交 Diff") {
        selected?.let { (root,comparison) ->
            val binding = bindings.firstOrNull { it.str("repository") == repository.selectedItem }
            val workBranch = binding?.str("workBranch") ?: comparison.work
            val rawBase = binding?.str("baseBranch") ?: comparison.mergeBase
            val remoteBase = if (rawBase.startsWith("origin/") || rawBase.startsWith("refs/")) rawBase else "origin/$rawBase"
            val hasRemote = runCatching { HostGit(project).repository(root).branches.findRemoteBranch(remoteBase) != null }.getOrDefault(false)
            val targetBase = if (hasRemote) remoteBase else rawBase

            HostGit(project).showCommittedDiff(root, targetBase, workBranch).onFailure {
                HostGit(project).showCommittedDiff(root, rawBase, workBranch).onFailure {
                    HostGit(project).showCommittedDiff(root, comparison.mergeBase, comparison.work).onFailure { err ->
                        message.text = err.message
                    }
                }
            }
        }
    }.apply {
        icon = AllIcons.Actions.Diff
    }
    private var bindings=emptyList<JsonObject>()
    private var roots=emptyMap<String,Path>()
    private var selected:Pair<Path,NativeGit.Comparison.Available>?=null
    private var generation=0
    private var future:Future<*>?=null
    private var disposed=false

    private fun openSelectedFile(fileRelPath: String) {
        val root = selected?.first ?: return
        val comparison = selected?.second as? NativeGit.Comparison.Available ?: return
        val binding = bindings.firstOrNull { it.str("repository") == repository.selectedItem }
        val baseName = binding?.str("baseBranch") ?: "基线版本"
        val workName = binding?.str("workBranch") ?: "需求分支"

        HostGit(project).showBranchDiff(
            root = root,
            baseCommit = comparison.mergeBase,
            workCommit = comparison.work,
            relativePath = fileRelPath,
            baseLabel = baseName,
            workLabel = workName
        ).onFailure {
            HostGit(project).showWorkspaceDiff(root, comparison.mergeBase, fileRelPath).onFailure { error ->
                com.intellij.openapi.ui.Messages.showErrorDialog(project, error.message ?: "无法加载 Diff 视图", "Diff 加载失败")
            }
        }
    }

    private fun openInRawEditor(fileRelPath: String) {
        val root = selected?.first ?: return
        val fullPath = root.resolve(fileRelPath)
        val vf = LocalFileSystem.getInstance().findFileByPath(fullPath.toString()) ?: return
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun selectInProjectView(fileRelPath: String) {
        val root = selected?.first ?: return
        val fullPath = root.resolve(fileRelPath)
        val vf = LocalFileSystem.getInstance().findFileByPath(fullPath.toString()) ?: return
        ProjectView.getInstance(project)?.select(vf, vf, true)
    }

    private fun revealInFileManager(fileRelPath: String) {
        val root = selected?.first ?: return
        val fullPath = root.resolve(fileRelPath)
        if (fullPath.toFile().exists()) {
            RevealFileAction.openFile(fullPath)
        }
    }

    init {
        background=U.bg;U.combo(repository)
        add(U.column(18,U.row(U.flow(U.label("关联仓库",11,U.muted),repository),U.flow(fetchBtn,diff)),scope,message).apply { border=com.intellij.util.ui.JBUI.Borders.empty(22,0) },BorderLayout.NORTH)
        val fileList=JBList(files).apply {
            background=U.bg;foreground=U.text;fixedCellHeight=32;border=com.intellij.util.ui.JBUI.Borders.empty(4);font=U.mono("").font
            emptyText.text = "暂无变更文件"
            cellRenderer = object : ColoredListCellRenderer<String>() {
                override fun customizeCellRenderer(list: javax.swing.JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    if (value == null) return
                    val fileName = File(value).name
                    val parentDir = File(value).parent?.let { " ($it)" } ?: ""
                    val fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(fileName)
                    icon = fileType.icon
                    append(fileName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (parentDir.isNotEmpty()) {
                        append(parentDir, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
            }
        }
        val commitList=JBList(commits).apply {
            background=U.bg;foreground=U.muted;fixedCellHeight=32;border=com.intellij.util.ui.JBUI.Borders.empty(4);font=U.mono("").font
            emptyText.text = "暂无提交记录"
            cellRenderer = object : ColoredListCellRenderer<String>() {
                override fun customizeCellRenderer(list: javax.swing.JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    if (value == null) return
                    icon = AllIcons.Vcs.CommitNode
                    val parts = value.split(" ", limit = 2)
                    if (parts.size == 2) {
                        append(parts[0], SimpleTextAttributes.GRAY_ATTRIBUTES)
                        append("  ")
                        append(parts[1], SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    } else {
                        append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
            }
        }

        ListSpeedSearch.installOn(fileList)
        ListSpeedSearch.installOn(commitList)

        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && fileList.selectedValue != null) {
                    openSelectedFile(fileList.selectedValue)
                }
            }
        })
        fileList.registerKeyboardAction({
            fileList.selectedValue?.let(::openSelectedFile)
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        fileList.registerKeyboardAction({
            fileList.selectedValue?.let(::openSelectedFile)
        }, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), JComponent.WHEN_FOCUSED)
        fileList.registerKeyboardAction({
            fileList.selectedValue?.let(::openSelectedFile)
        }, KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), JComponent.WHEN_FOCUSED)

        fileList.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val index = fileList.locationToIndex(Point(x, y))
                if (index >= 0) {
                    fileList.selectedIndex = index
                    val file = fileList.selectedValue ?: return
                    val menu = JPopupMenu()
                    menu.add(JMenuItem("比对本地差异对比 (F4 / 双击 / 空格)").apply {
                        icon = AllIcons.Actions.Diff
                        addActionListener { openSelectedFile(file) }
                    })
                    menu.add(JMenuItem("在原生编辑器中打开").apply {
                        icon = AllIcons.General.OpenInToolWindow
                        addActionListener { openInRawEditor(file) }
                    })
                    menu.add(JMenuItem("在 Project 视图中定位").apply {
                        icon = AllIcons.General.Locate
                        addActionListener { selectInProjectView(file) }
                    })
                    menu.add(JMenuItem("在系统文件管理器中显示").apply {
                        icon = AllIcons.Actions.MenuOpen
                        addActionListener { revealInFileManager(file) }
                    })
                    menu.add(JMenuItem("复制文件相对路径").apply {
                        icon = AllIcons.Actions.Copy
                        addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(file)) }
                    })
                    menu.show(comp, x, y)
                }
            }
        })

        val splitter = JBSplitter(false, 0.55f).apply {
            firstComponent = U.panel().apply { add(U.section("变更文件"), BorderLayout.NORTH); add(U.scroll(fileList)) }
            secondComponent = U.panel().apply { add(U.section("分支提交"), BorderLayout.NORTH); add(U.scroll(commitList)) }
            border = com.intellij.util.ui.JBUI.Borders.empty()
            background = U.bg
        }
        add(splitter, BorderLayout.CENTER)
        diff.isEnabled=false;repository.addActionListener { load() }
    }
    fun showFeature(feature:JsonObject,repositories:List<JsonObject>) {
        val newBindings = feature.getAsJsonObject("summary")?.getAsJsonArray("repositoryBindings")?.map { it.asJsonObject } ?: emptyList()
        val newRoots = repositories.associate { it.get("id").asString to Path.of(it.get("absolutePath").asString) }
        this.bindings = newBindings
        this.roots = newRoots

        val currentSelected = repository.selectedItem as? String
        val newRepoNames = newBindings.mapNotNull { it.str("repository") }
        val existingItems = (0 until repository.itemCount).map { repository.getItemAt(it) }

        if (existingItems != newRepoNames) {
            repository.removeAllItems()
            newRepoNames.forEach { repository.addItem(it) }
            if (currentSelected != null && newRepoNames.contains(currentSelected)) {
                repository.selectedItem = currentSelected
            }
        } else if (currentSelected != null && repository.selectedItem != currentSelected) {
            repository.selectedItem = currentSelected
        }
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

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
    private val branchSummary=U.label("选择仓库后查看需求分支",11,U.muted)
    private val message=U.label("选择需求后查看记录分支的已提交变更",12,U.muted)
    private val overview=U.column(4)
    private val startField=javax.swing.JTextField(16).apply { toolTipText = "仅输入已记录或您明确选择的接手起点 commit；留空使用分支共同祖先" }
    var onLocationChanged: ((String, String) -> Unit)? = null
    private var restoredRepository: String? = null
    private var restoredFile: String? = null
    private val files=DefaultListModel<String>()
    private lateinit var fileList: JBList<String>
    private val commits=DefaultListModel<String>()
    private var featureIdentity: String? = null
    private var successfulOverviewLines = emptyList<String>()
    private var successfulBranchSummary: String? = null
    private var successfulFiles = emptyList<String>()
    private var successfulCommits = emptyList<String>()
    private var successfulRepository: String? = null
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
            HostGit(project).showCommittedDiff(root, comparison.base, comparison.work).onFailure { err ->
                message.text = "Diff 打开失败：${err.message ?: "未知错误"}"
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
    private var updatingRepository=false
    private var updatingFiles=false

    private fun openSelectedFile(fileRelPath: String) {
        val root = selected?.first ?: return
        val comparison = selected?.second as? NativeGit.Comparison.Available ?: return
        val binding = bindings.firstOrNull { it.str("repository") == repository.selectedItem }
        val baseName = binding?.str("baseBranch") ?: "基线版本"
        val workName = binding?.str("workBranch") ?: "需求分支"

        HostGit(project).showBranchDiff(
            root = root,
            baseCommit = comparison.base,
            workCommit = comparison.work,
            relativePath = fileRelPath,
            baseLabel = baseName,
            workLabel = workName
        ).onFailure { error ->
            com.intellij.openapi.ui.Messages.showErrorDialog(project, error.message ?: "无法加载 Diff 视图", "Diff 加载失败")
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
        add(U.column(8,U.row(U.flow(U.label("关联仓库",11,U.muted),repository,U.label("接手起点",11,U.muted),startField),U.flow(fetchBtn,diff)),overview,branchSummary,message).apply { border=com.intellij.util.ui.JBUI.Borders.empty(12,0,8,0) },BorderLayout.NORTH)
        startField.addActionListener { load() }
        startField.getDocument().addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = invalidateComparison()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = invalidateComparison()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = invalidateComparison()
        })
        fileList=JBList(files).apply {
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
        fileList.addListSelectionListener { if (!it.valueIsAdjusting && !updatingFiles) onLocationChanged?.invoke(repository.selectedItem as? String ?: "", fileList.selectedValue ?: "") }

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
        diff.isEnabled=false;repository.addActionListener { if(!updatingRepository) { onLocationChanged?.invoke(repository.selectedItem as? String ?: "", ""); load() } }
    }
    private fun invalidateComparison() {
        future?.cancel(true)
        generation++
        selected = null
        diff.isEnabled = false
        if (successfulRepository == repository.selectedItem) restoreSuccessfulComparisonAsStale()
        message.text = "接手起点已变更；请按回车重新比较"
        message.foreground = U.amber
    }
    private fun clearVisibleComparison() {
        future?.cancel(true)
        generation++
        selected = null
        diff.isEnabled = false
        overview.removeAll()
        branchSummary.text = "选择仓库后查看需求分支"
        branchSummary.foreground = U.muted
        updatingFiles = true
        try { files.clear(); commits.clear() } finally { updatingFiles = false }
        message.text = "选择需求后查看记录分支的已提交变更"
        message.foreground = U.muted
    }
    private fun clearSuccessfulComparison() {
        successfulOverviewLines = emptyList()
        successfulBranchSummary = null
        successfulFiles = emptyList()
        successfulCommits = emptyList()
        successfulRepository = null
    }
    private fun renderOverview(lines: List<String>, stale: Boolean, failure: Boolean = false) {
        overview.removeAll()
        lines.forEach { line ->
            U.append(overview, U.label(if (stale) "$line · 过期" else line, 11, if (stale || failure) U.amber else U.muted))
        }
    }
    private fun overviewLine(name: String, scene: NativeGit.Snapshot, comparison: NativeGit.Comparison): String {
        val range = when (comparison) {
            is NativeGit.Comparison.Available -> "${comparison.base.take(10)} → ${comparison.work.take(10)} · ${comparison.files.size} 文件"
            is NativeGit.Comparison.Unavailable -> "读取失败：${comparison.reason}"
        }
        val dirty = when (scene) {
            is NativeGit.Snapshot.Available -> "工作目录 ${scene.changes} 项"
            is NativeGit.Snapshot.Unavailable -> "工作目录读取失败：${scene.reason}"
        }
        return "$name · $range · $dirty"
    }
    private fun restoreSuccessfulComparisonAsStale() {
        if (successfulOverviewLines.isNotEmpty()) renderOverview(successfulOverviewLines, stale = true)
        successfulBranchSummary?.let {
            branchSummary.text = "$it · 过期"
            branchSummary.foreground = U.amber
        }
        if (successfulFiles.isNotEmpty() || successfulCommits.isNotEmpty()) {
            updatingFiles = true
            try {
                files.clear(); commits.clear()
                successfulFiles.forEach(files::addElement)
                successfulCommits.forEach(commits::addElement)
            } finally { updatingFiles = false }
        }
    }
    fun restoreLocation(repo: String, file: String, start: String) {
        restoredRepository = repo.takeIf(String::isNotBlank)
        restoredFile = file.takeIf(String::isNotBlank)
        startField.text = start
        clearVisibleComparison()
    }
    fun selectedStartCommit(): String = startField.text.trim()
    fun showFeature(feature:JsonObject,repositories:List<JsonObject>) {
        val summary = feature.getAsJsonObject("summary")
        val identity = summary?.str("slug") ?: summary?.str("path") ?: summary?.toString()
        if (featureIdentity != identity) {
            clearSuccessfulComparison()
            clearVisibleComparison()
        }
        featureIdentity = identity
        val newBindings = summary?.getAsJsonArray("repositoryBindings")?.map { it.asJsonObject } ?: emptyList()
        val newRoots = repositories.associate { it.get("id").asString to Path.of(it.get("absolutePath").asString) }
        this.bindings = newBindings
        this.roots = newRoots

        val currentSelected = restoredRepository ?: repository.selectedItem as? String
        restoredRepository = null
        val newRepoNames = newBindings.mapNotNull { it.str("repository") }
        val existingItems = (0 until repository.itemCount).map { repository.getItemAt(it) }

        updatingRepository=true
        try {
            if (existingItems != newRepoNames) {
                repository.removeAllItems()
                newRepoNames.forEach { repository.addItem(it) }
                if (currentSelected != null && newRepoNames.contains(currentSelected)) {
                    repository.selectedItem = currentSelected
                }
            } else if (currentSelected != null && repository.selectedItem != currentSelected) {
                repository.selectedItem = currentSelected
            }
        } finally { updatingRepository=false }
        load()
    }
    private fun load() {
        future?.cancel(true);val request=++generation
        val chosen = repository.selectedItem as? String
        val start = startField.text.trim().takeIf(String::isNotEmpty)
        message.text = "正在读取仓库比较…"
        diff.isEnabled = false
        selected = null
        future=ApplicationManager.getApplication().executeOnPooledThread {
            val git=NativeGit.forProject(project)
            val results = bindings.mapNotNull { binding ->
                val name = binding.str("repository") ?: return@mapNotNull null
                val root = roots[name]
                val scene = root?.let { git.snapshot(it.toFile()) } ?: NativeGit.Snapshot.Unavailable("未找到关联仓库目录")
                val base = binding.str("baseBranch")
                val work = binding.str("workBranch")
                val recorded = binding.str("startCommit") ?: binding.str("handoffCommit")
                val comparison = if (root == null || base.isNullOrBlank() || work.isNullOrBlank()) NativeGit.Comparison.Unavailable("仓库目录或比较分支未记录")
                    else git.comparison(root.toFile(), base, work, if (name == chosen) start ?: recorded else recorded)
                Triple(name, scene, comparison)
            }
            ApplicationManager.getApplication().invokeLater {
                if(disposed||generation!=request) return@invokeLater
                val overviewLines = results.map { (name, scene, comparison) -> overviewLine(name, scene, comparison) }
                val current = results.firstOrNull { it.first == chosen }
                val comparison = current?.third
                val scene = current?.second
                when (comparison) {
                    is NativeGit.Comparison.Unavailable -> {
                        val hasPrevious = successfulOverviewLines.isNotEmpty() && successfulRepository == chosen
                        if (hasPrevious) restoreSuccessfulComparisonAsStale()
                        else {
                            branchSummary.text = "选择仓库后查看需求分支"
                            branchSummary.foreground = U.muted
                            updatingFiles = true
                            try { files.clear(); commits.clear() } finally { updatingFiles = false }
                            renderOverview(overviewLines, stale = false, failure = true)
                        }
                        message.text = "读取失败：${comparison.reason}" + if (hasPrevious) "；上次成功内容已过期" else ""
                        message.foreground = U.amber
                    }
                    is NativeGit.Comparison.Available -> {
                        val root = roots[chosen]
                        if (root != null) {
                            selected = root to comparison; diff.isEnabled = true
                            val previousFile = restoredFile ?: fileList.selectedValue
                            updatingFiles = true
                            try {
                                files.clear(); commits.clear()
                                comparison.files.forEach(files::addElement);comparison.commits.forEach(commits::addElement)
                                previousFile?.let { file -> fileList.setSelectedValue(file, true) }
                            } finally { updatingFiles = false; restoredFile = null }
                        }
                        branchSummary.text = "实际比较：${comparison.base} → ${comparison.work} · 共同祖先 ${comparison.mergeBase} · 当前检出 ${(scene as? NativeGit.Snapshot.Available)?.branch ?: "未知"}"
                        branchSummary.foreground = U.muted
                        message.foreground=U.muted
                        message.text="${comparison.commits.size} 个提交 · ${comparison.files.size} 个文件 · 工作目录变更单独展示（不自动归因）"
                        renderOverview(overviewLines, stale = false)
                        successfulOverviewLines = overviewLines
                        successfulBranchSummary = branchSummary.text
                        successfulFiles = comparison.files
                        successfulCommits = comparison.commits
                        successfulRepository = chosen
                    }
                    null -> {
                        overview.removeAll()
                        message.text = "无关联仓库"; message.foreground = U.amber
                    }
                }
                revalidate();repaint()
            }
        }
    }
    override fun dispose(){disposed=true;generation++;future?.cancel(true)}
}

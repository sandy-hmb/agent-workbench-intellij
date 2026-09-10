package org.agentworkbench.intellij.git

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.ui.CommitChangeListDialog
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import com.intellij.vcsUtil.VcsUtil
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeRegistry
import git4idea.GitRevisionNumber
import git4idea.GitContentRevision
import git4idea.branch.GitBranchUtil
import git4idea.branch.GitBrancher
import git4idea.fetch.GitFetchSupport
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.Path

/** 所有操作先解析唯一仓库，再向宿主传递明确的根或文件集合。 */
internal class HostGit(private val project: Project) {
    fun isRegistered(root: Path): Boolean {
        val canonical = runCatching { root.toRealPath() }.getOrNull() ?: return false
        return ProjectLevelVcsManager.getInstance(project).directoryMappings.any { mapping ->
            val directory = mapping.directory.ifEmpty { project.basePath.orEmpty() }
            directory.isNotBlank() && runCatching { Path.of(directory).toRealPath() == canonical }.getOrDefault(false)
        }
    }

    fun registerRoots(roots: Collection<Path>): List<Path> = applyMappings(canonicalGitRoots(roots))

    /** 路径解析与 .git 检查是文件系统 IO，可在后台线程执行。 */
    fun canonicalGitRoots(roots: Collection<Path>): List<Path> {
        val canonical = roots.map { it.toRealPath() }.distinct()
        canonical.forEach { require(Files.exists(it.resolve(".git"))) { "目录不是独立 Git 根：" + it } }
        return canonical
    }

    /** 只做映射登记；传入的路径必须已经 canonicalGitRoots 处理过。 */
    fun applyMappings(canonical: List<Path>): List<Path> {
        check(!project.isDisposed) { "项目已关闭" }
        val manager = ProjectLevelVcsManager.getInstance(project)
        val existing = manager.directoryMappings
        val existingPaths = existing.mapNotNull { mapping ->
            val directory = mapping.directory.ifEmpty { project.basePath.orEmpty() }
            if (directory.isBlank()) null else runCatching { Path.of(directory).toRealPath() }.getOrNull()
        }.toSet()
        val added = canonical.filterNot(existingPaths::contains)
        if (added.isNotEmpty()) manager.setDirectoryMappings(existing + added.map { VcsDirectoryMapping(it.toString(), "Git") })
        return added
    }

    fun fetch(root: Path, remoteName: String): Result<Unit> = runCatching {
        check(!project.isDisposed) { "项目已关闭" }
        val repository = repository(root)
        val remote = repository.remotes.singleOrNull { it.name == remoteName }
            ?: error("未找到明确 remote：" + remoteName)
        GitFetchSupport.fetchSupport(project).fetch(repository, remote).throwExceptionIfFailed()
    }

    /** 后台执行 fetch，完成后在 EDT 回调结果；避免调用方再起线程阻塞等待。 */
    fun fetchAsync(root: Path, remoteName: String, onDone: (Result<Unit>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = fetch(root, remoteName)
            ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) onDone(result) }
        }
    }

    fun rootFile(root: Path): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.toRealPath())

    fun openNative(actionId: String, root: Path): Result<Unit> = runCatching {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val repository = repository(root)
        val file = repository.root
        when (actionId) {
            "Vcs.Show.Log" -> {
                val filters = logFilters(file)
                check(VcsProjectLog.getInstance(project).openLogTab(filters) != null) { "宿主日志尚未就绪，请稍后重试" }
            }
            "ChangesView.Diff" -> {
                val changes = changesForRoot(file, ChangeListManager.getInstance(project).allChanges)
                check(changes.isNotEmpty()) { "当前仓库没有已跟踪文件变更" }
                AbstractVcsHelper.getInstance(project).showWhatDiffersBrowser(changes, file.name + "：当前工作目录")
            }
            "CheckinProject" -> {
                val manager = ChangeListManager.getInstance(project)
                val changes = changesForRoot(file, manager.allChanges)
                val unversioned = manager.unversionedFilesPaths.filter {
                    ProjectLevelVcsManager.getInstance(project).getVcsRootFor(it) == file
                }.mapNotNull { it.virtualFile }
                check(changes.isNotEmpty() || unversioned.isNotEmpty()) { "当前仓库没有可提交变更" }
                // included 仅指定初始选区，最终 Commit 仍由用户在宿主确认。
                CommitChangeListDialog.commitVcsChanges(project, changes + unversioned, null, null, null)
            }
            "Git.ResolveConflicts" -> {
                val conflicts = changesForRoot(file, ChangeListManager.getInstance(project).allChanges)
                    .filter(ChangesUtil::isTextConflictingChange)
                    .mapNotNull { ChangesUtil.getFilePath(it).virtualFile }
                check(conflicts.isNotEmpty()) { "当前仓库没有可处理的文件冲突" }
                AbstractVcsHelper.getInstance(project).showMergeDialog(conflicts)
            }
            "Git.Branches" -> {
                val context = branchContext(file)
                check(GitBranchUtil.guessRepositoryForOperation(project, context) == repository) { "宿主无法定位所选仓库" }
                val action = ActionManager.getInstance().getAction(actionId) ?: error("宿主未提供分支管理")
                ActionUtil.performAction(action, AnActionEvent.createEvent(context, null, "AgentWorkbench", ActionUiKind.NONE, null))
            }
            "Vcs.Push", "CheckinProject.Push" -> {
                val context = branchContext(file)
                val action = ActionManager.getInstance().getAction("Vcs.Push")
                    ?: ActionManager.getInstance().getAction("CheckinProject.Push")
                    ?: error("宿主未提供推送管理")
                ActionUtil.performAction(action, AnActionEvent.createEvent(context, null, "AgentWorkbench", ActionUiKind.NONE, null))
            }
            "Git.Pull", "Vcs.UpdateProject" -> {
                val context = branchContext(file)
                val action = ActionManager.getInstance().getAction("Git.Pull")
                    ?: ActionManager.getInstance().getAction("Vcs.UpdateProject")
                    ?: error("宿主未提供拉取管理")
                ActionUtil.performAction(action, AnActionEvent.createEvent(context, null, "AgentWorkbench", ActionUiKind.NONE, null))
            }
            else -> error("不支持的宿主 Git 操作")
        }
        Unit
    }

    fun selectInProjectView(root: Path): Result<Unit> = runCatching {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val file = rootFile(root) ?: error("无法定位仓库目录: $root")
        com.intellij.ide.projectView.ProjectView.getInstance(project).select(null, file, true)
    }

    fun openInTerminal(root: Path): Result<Unit> = runCatching {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val file = rootFile(root) ?: error("无法定位仓库目录: $root")
        val terminalViewClass = runCatching { Class.forName("org.jetbrains.plugins.terminal.TerminalView") }.getOrNull()
        if (terminalViewClass != null) {
            val getInstanceMethod = terminalViewClass.getMethod("getInstance", Project::class.java)
            val instance = getInstanceMethod.invoke(null, project)
            val openMethod = terminalViewClass.getMethod("openTerminalIn", VirtualFile::class.java)
            openMethod.invoke(instance, file)
        } else {
            val action = ActionManager.getInstance().getAction("ActivateTerminalToolWindow")
            if (action != null) {
                val context = branchContext(file)
                ActionUtil.performAction(action, AnActionEvent.createEvent(context, null, "AgentWorkbench", ActionUiKind.NONE, null))
            } else {
                error("宿主未提供内置终端")
            }
        }
    }

    fun showCommittedDiff(root: Path, baseOrWorkRef: String, compareRef: String): Result<Unit> = runCatching {
        GitBrancher.getInstance(project).showDiff(baseOrWorkRef, compareRef, listOf(repository(root)))
    }

    fun showBranchDiff(
        root: Path,
        baseCommit: String,
        workCommit: String,
        relativePath: String,
        baseLabel: String = "基线版本",
        workLabel: String = "需求分支版本"
    ): Result<Unit> = runCatching {
        ApplicationManager.getApplication().assertIsDispatchThread()
        repository(root) // 仅校验 root 是宿主已识别的独立 Git 仓
        val file = root.resolve(relativePath).toFile()
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            ?: VcsUtil.getVirtualFile(file)
        val filePath = virtualFile?.let { VcsUtil.getFilePath(it) } ?: VcsUtil.getFilePath(file, false)

        val baseRevision = GitContentRevision.createRevision(filePath, GitRevisionNumber(baseCommit), project, null)
        val workRevision = GitContentRevision.createRevision(filePath, GitRevisionNumber(workCommit), project, null)

        val factory = DiffContentFactory.getInstance()
        val fileType = virtualFile?.fileType ?: FileTypeRegistry.getInstance().getFileTypeByFileName(file.name)
        val baseContent = factory.create(project, baseRevision.content ?: "", fileType)
        val workContent = factory.create(project, workRevision.content ?: "", fileType)

        val request = SimpleDiffRequest(
            "变更比对：$relativePath ($baseLabel..$workLabel)",
            baseContent,
            workContent,
            "$baseLabel (${baseCommit.take(8)})",
            "$workLabel (${workCommit.take(8)})"
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    fun showWorkspaceDiff(root: Path, baseCommit: String, relativePath: String): Result<Unit> = runCatching {
        ApplicationManager.getApplication().assertIsDispatchThread()
        repository(root) // 仅校验 root 是宿主已识别的独立 Git 仓
        val file = root.resolve(relativePath).toFile()
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            ?: error("在工作区中未找到本地文件：$relativePath")

        val filePath = VcsUtil.getFilePath(virtualFile)
        val revision = GitRevisionNumber(baseCommit)
        val contentRevision = GitContentRevision.createRevision(filePath, revision, project, null)

        val factory = DiffContentFactory.getInstance()
        val historicContent = factory.create(project, contentRevision.content ?: "", virtualFile.fileType)
        val localContent = factory.create(project, virtualFile)

        val request = SimpleDiffRequest(
            "变更比对：$relativePath",
            historicContent,
            localContent,
            "基线版本 ($baseCommit)",
            "工作区当前版本"
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    fun changesForRoot(root: VirtualFile, changes: Collection<Change>): List<Change> {
        val manager = ProjectLevelVcsManager.getInstance(project)
        return changes.filter { change ->
            val paths = listOfNotNull(change.beforeRevision?.file, change.afterRevision?.file)
            paths.isNotEmpty() && paths.all { manager.getVcsRootFor(it) == root }
        }
    }

    fun logFilters(root: VirtualFile) = VcsLogFilterObject.collection(VcsLogFilterObject.fromRoot(root))

    private fun branchContext(root: VirtualFile) = DataContext { key ->
        when (key) {
            CommonDataKeys.PROJECT.name -> project
            CommonDataKeys.VIRTUAL_FILE.name -> root
            CommonDataKeys.VIRTUAL_FILE_ARRAY.name -> arrayOf(root)
            else -> null
        }
    }

    internal fun repository(root: Path) = GitRepositoryManager.getInstance(project).repositories
        .singleOrNull { runCatching { it.root.toNioPath().toRealPath() == root.toRealPath() }.getOrDefault(false) }
        ?: throw UnrecognizedRepository(root)

    internal class UnrecognizedRepository(root: Path) : IllegalStateException("宿主未识别独立 Git 仓库：$root")
}

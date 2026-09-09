package org.agentworkbench.intellij.git

import com.intellij.openapi.actionSystem.ActionManager
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
import git4idea.branch.GitBranchUtil
import git4idea.branch.GitBrancher
import git4idea.fetch.GitFetchSupport
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Future

/** 所有操作先解析唯一仓库，再向宿主传递明确的根或文件集合。 */
internal class HostGit(private val project: Project) {
    fun isRegistered(root: Path): Boolean {
        val canonical = runCatching { root.toRealPath() }.getOrNull() ?: return false
        return ProjectLevelVcsManager.getInstance(project).directoryMappings.any { mapping ->
            val directory = mapping.directory.ifEmpty { project.basePath.orEmpty() }
            directory.isNotBlank() && runCatching { Path.of(directory).toRealPath() == canonical }.getOrDefault(false)
        }
    }

    fun registerRoots(roots: Collection<Path>): List<Path> {
        check(!project.isDisposed) { "项目已关闭" }
        val canonical = roots.map { it.toRealPath() }.distinct()
        canonical.forEach { require(Files.exists(it.resolve(".git"))) { "目录不是独立 Git 根：" + it } }
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

    fun fetchAsync(root: Path, remoteName: String): Future<Result<Unit>> =
        ApplicationManager.getApplication().executeOnPooledThread<Result<Unit>> {
            runCatching {
                check(!project.isDisposed) { "项目已关闭" }
                val repository = repository(root)
                val remote = repository.remotes.singleOrNull { it.name == remoteName }
                    ?: error("未找到明确 remote：" + remoteName)
                GitFetchSupport.fetchSupport(project).fetch(repository, remote).throwExceptionIfFailed()
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
                ActionUtil.invokeAction(action, context, "AgentWorkbench", null, null)
            }
            else -> error("不支持的宿主 Git 操作")
        }
        Unit
    }

    fun showCommittedDiff(root: Path, baseCommit: String, workCommit: String): Result<Unit> = runCatching {
        require(COMMIT_ID.matches(baseCommit) && COMMIT_ID.matches(workCommit)) { "比较必须使用已解析的固定 commit ID" }
        GitBrancher.getInstance(project).showDiff(baseCommit, workCommit, listOf(repository(root)))
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

    private fun repository(root: Path) = GitRepositoryManager.getInstance(project).repositories
        .singleOrNull { runCatching { it.root.toNioPath().toRealPath() == root.toRealPath() }.getOrDefault(false) }
        ?: throw UnrecognizedRepository(root)

    internal class UnrecognizedRepository(root: Path) : IllegalStateException("宿主未识别独立 Git 仓库：$root")

    private companion object {
        val COMMIT_ID = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
    }
}

package org.agentworkbench.intellij

import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcs.log.VcsLogFilterCollection
import com.intellij.vcsUtil.VcsUtil
import org.agentworkbench.intellij.git.HostGit
import java.nio.file.Files
import java.nio.file.Path

class HostGitScopeTest : BasePlatformTestCase() {
    private var diskRoot: Path? = null

    override fun tearDown() {
        try {
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(emptyList())
            super.tearDown()
        } finally { diskRoot?.toFile()?.deleteRecursively() }
    }

    fun testRootFiltersAndChangesExcludeOtherRepositoriesAndPreserveExistingMappings() {
        val disk = Files.createTempDirectory("workbench-host-scope-").toRealPath()
        diskRoot = disk
        VfsRootAccess.allowRootAccess(testRootDisposable, disk.toString())
        val files = listOf("a", "b", "c").map { name ->
            val path = Files.createDirectories(disk.resolve(name)).resolve("file.txt")
            Files.writeString(path, "current")
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        }
        val roots = files.map { it.parent }
        roots.forEach { root ->
            val process = ProcessBuilder("git", "init", "-q", "-b", "main", root.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        }
        val manager = ProjectLevelVcsManager.getInstance(project)
        manager.setDirectoryMappings(listOf(VcsDirectoryMapping(roots[2].path, "Git")))
        val host = HostGit(project)
        assertFalse(host.isRegistered(roots[0].toNioPath()))
        val added = host.registerRoots(roots.take(2).map { it.toNioPath() })
        assertEquals(2, added.size)
        assertEquals(roots.map { it.path }.toSet(), manager.directoryMappings.map { it.directory }.toSet())
        assertTrue(host.registerRoots(roots.map { it.toNioPath() }).isEmpty())
        assertTrue(host.isRegistered(roots[0].toNioPath()))

        val paths = files.map { VcsUtil.getFilePath(it) }
        val changes = paths.map { path -> Change(SimpleContentRevision("before", path, VcsRevisionNumber.NULL), CurrentContentRevision(path)) }
        val acrossRoots = Change(SimpleContentRevision("before", paths[0], VcsRevisionNumber.NULL), CurrentContentRevision(paths[1]))
        assertEquals(listOf(changes[0]), host.changesForRoot(roots[0], changes + acrossRoots))
        assertEquals(listOf(roots[0]), host.logFilters(roots[0]).get(VcsLogFilterCollection.ROOT_FILTER)?.roots?.toList())
        assertTrue(host.showCommittedDiff(roots[0].toNioPath(), "HEAD", "main").isFailure)
    }
}

package org.agentworkbench.intellij

import org.agentworkbench.intellij.git.NativeGit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitComparisonTest {
    @Test
    fun rejectsAChildDirectoryInsteadOfReportingTheParentRepository() {
        val root = Files.createTempDirectory("workbench-git-root-").toFile()
        try {
            git(root, "init", "-b", "main")
            val child = root.resolve("nested").apply { mkdir() }
            assertTrue(NativeGit().snapshot(child) is NativeGit.Snapshot.Unavailable)
            assertTrue(NativeGit().comparison(child, "main", "main") is NativeGit.Comparison.Unavailable)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun comparesRecordedBranchesWithoutCheckingOutTheWorkBranch() {
        val root = Files.createTempDirectory("workbench-git-").toFile()
        git(root, "init", "-b", "main")
        root.resolve("base.txt").writeText("base")
        git(root, "add", ".")
        git(root, "commit", "-m", "base")
        git(root, "branch", "feat/read-only")
        git(root, "switch", "feat/read-only")
        root.resolve("feature.txt").writeText("feature")
        git(root, "add", ".")
        git(root, "commit", "-m", "feature")
        git(root, "switch", "main")

        val result = NativeGit().comparison(root, "main", "feat/read-only") as NativeGit.Comparison.Available

        assertEquals("main", git(root, "branch", "--show-current").trim())
        assertEquals(listOf("feature.txt"), result.files)
        assertEquals(1, result.commits.size)
    }

    @Test
    fun doesNotFallbackToOriginWhenTheRecordedLocalBranchIsMissing() {
        val root = Files.createTempDirectory("workbench-git-").toFile()
        git(root, "init", "-b", "main")
        root.resolve("base.txt").writeText("base")
        git(root, "add", ".")
        git(root, "commit", "-m", "base")

        val result = NativeGit().comparison(root, "main", "feature/missing")

        assertTrue(result is NativeGit.Comparison.Unavailable)
    }

    @Test
    fun refusesComparisonWhenBranchesHaveNoCommonAncestor() {
        val root = Files.createTempDirectory("workbench-git-").toFile()
        git(root, "init", "-b", "main")
        root.resolve("main.txt").writeText("main")
        git(root, "add", ".")
        git(root, "commit", "-m", "main")
        git(root, "checkout", "--orphan", "feature/orphan")
        git(root, "rm", "-rf", ".")
        root.resolve("orphan.txt").writeText("orphan")
        git(root, "add", ".")
        git(root, "commit", "-m", "orphan")

        val result = NativeGit().comparison(root, "main", "feature/orphan")

        assertTrue(result is NativeGit.Comparison.Unavailable)
    }

    private fun git(root: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path, *arguments))
            .redirectErrorStream(true)
            .apply { environment()["GIT_AUTHOR_NAME"] = "Test"; environment()["GIT_AUTHOR_EMAIL"] = "test@example.invalid"; environment()["GIT_COMMITTER_NAME"] = "Test"; environment()["GIT_COMMITTER_EMAIL"] = "test@example.invalid" }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { output }
        return output
    }
}

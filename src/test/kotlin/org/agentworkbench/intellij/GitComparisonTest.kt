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

    @Test fun doesNotGuessOriginWhenLocalBranchDiffers() {
        val root = Files.createTempDirectory("workbench-explicit-branch-").toFile()
        try {
            git(root, "init", "-b", "main")
            root.resolve("base.txt").writeText("base")
            git(root, "add", ".")
            git(root, "commit", "-m", "base")
            git(root, "remote", "add", "origin", root.path)
            git(root, "fetch", "origin", "main")
            git(root, "switch", "-c", "feature")
            root.resolve("local.txt").writeText("local")
            git(root, "add", ".")
            git(root, "commit", "-m", "local")
            assertEquals(listOf("local.txt"), (NativeGit().comparison(root, "main", "feature") as NativeGit.Comparison.Available).files)
            assertTrue(NativeGit().comparison(root, "main", "missing") is NativeGit.Comparison.Unavailable)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun resolvesReviewRemoteFromRecordedBranchWithoutCheckingOutThatBranch() {
        val root = Files.createTempDirectory("workbench-review-remote-").toFile()
        git(root, "init", "-b", "main")
        root.resolve("base.txt").writeText("base")
        git(root, "add", ".")
        git(root, "commit", "-m", "base")
        git(root, "remote", "add", "origin", "git@github.com:acme/service.git")
        git(root, "remote", "add", "personal", "git@github-personal:acme/service.git")
        git(root, "config", "branch.feature/review.pushRemote", "personal")

        val result = NativeGit().reviewRemote(root, "feature/review") as NativeGit.ReviewRemote.Resolved

        assertEquals("personal", result.name)
        assertEquals("git@github-personal:acme/service.git", result.url)
        assertEquals("main", git(root, "branch", "--show-current").trim())
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

    @Test fun takeoverUsesExplicitCommitAndRejectsInvalidStart() {
        val root = Files.createTempDirectory("workbench-takeover-").toFile()
        try {
            git(root, "init", "-b", "main")
            root.resolve("base.txt").writeText("base")
            git(root, "add", ".")
            git(root, "commit", "-m", "base")
            val start = git(root, "rev-parse", "HEAD").trim()
            git(root, "switch", "-c", "feature")
            root.resolve("after.txt").writeText("after")
            git(root, "add", ".")
            git(root, "commit", "-m", "after")
            val result = NativeGit().comparison(root, "main", "feature", start) as NativeGit.Comparison.Available
            assertEquals(start, result.base)
            assertEquals(listOf("after.txt"), result.files)
            assertTrue(NativeGit().comparison(root, "main", "feature", "not-a-commit") is NativeGit.Comparison.Unavailable)
        } finally { root.deleteRecursively() }
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

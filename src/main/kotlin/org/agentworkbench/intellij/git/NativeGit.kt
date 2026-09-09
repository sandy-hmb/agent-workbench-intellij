package org.agentworkbench.intellij.git

import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.intellij.openapi.project.Project
import git4idea.config.GitExecutableManager

internal class NativeGit(private val executable: String = "git") {
    fun snapshot(root: File): Snapshot = runCatching {
        requireRepositoryRoot(root)
        val branch = read(root, "branch", "--show-current").trim().ifEmpty { "detached HEAD" }
        val status = read(root, "status", "--porcelain=v1").lines().filter(String::isNotBlank)
        val upstream = runCatching { read(root, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}").trim() }.getOrNull()
        val divergence = upstream?.let { read(root, "rev-list", "--left-right", "--count", "HEAD...$it").trim().split(Regex("\\s+")) }
        val ahead = divergence?.getOrNull(0)?.toIntOrNull()
        val behind = divergence?.getOrNull(1)?.toIntOrNull()
        Snapshot.Available(branch, status.size, status.any { it.take(2) in CONFLICT_CODES }, upstream, ahead, behind)
    }.getOrElse { Snapshot.Unavailable(it.message ?: "Git 现场不可读取") }
    fun remotes(root: File): List<String> = runCatching { requireRepositoryRoot(root); read(root, "remote").lines().filter(String::isNotBlank) }.getOrDefault(emptyList())
    fun lastCommitTime(root:File):String? = runCatching { requireRepositoryRoot(root);read(root,"log","-1","--format=%cI").trim().takeIf(String::isNotEmpty) }.getOrNull()
    fun comparison(root: File, baseBranch: String, workBranch: String): Comparison {
        runCatching { requireRepositoryRoot(root) }.onFailure { return Comparison.Unavailable(it.message ?: "不是独立 Git 根") }
        val base = localBranch(root, baseBranch) ?: return Comparison.Unavailable("未找到基线分支 $baseBranch")
        val work = localBranch(root, workBranch) ?: return Comparison.Unavailable("未找到需求分支 $workBranch")
        val mergeBases = runCatching { read(root, "merge-base", "--all", base, work) }
            .getOrElse { return Comparison.Unavailable("比较没有共同祖先") }
            .lines().filter(String::isNotBlank)
        if (mergeBases.size != 1) return Comparison.Unavailable("比较需要唯一共同祖先")
        val mergeBase = mergeBases.single()
        val commits = read(root, "rev-list", "--reverse", "$base..$work").lines().filter(String::isNotBlank)
        val files = read(root, "diff", "--no-ext-diff", "--no-textconv", "--name-only", "$mergeBase..$work", "--").lines().filter(String::isNotBlank)
        return Comparison.Available(base, work, mergeBase, commits, files)
    }

    private fun localBranch(root: File, branch: String): String? {
        if (branch.isBlank() || branch.startsWith('-') || branch.contains('\u0000')) return null
        val ref = if (branch.startsWith("refs/")) branch else "refs/heads/$branch"
        return runCatching { read(root, "rev-parse", "--verify", "--quiet", "--end-of-options", "${ref}^{commit}") }
            .getOrNull()?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun requireRepositoryRoot(root: File) {
        val top = File(read(root, "rev-parse", "--show-toplevel").trim()).canonicalFile
        require(top == root.canonicalFile) { "目录不是独立 Git 根：${root.path}" }
    }

    private fun read(root: File, vararg arguments: String): String {
        if (Thread.currentThread().isInterrupted) throw IOException("Git 查询已取消")
        val process = ProcessBuilder(listOf(executable, "-C", root.canonicalPath, *arguments))
            .redirectErrorStream(true)
            .apply { environment()["GIT_OPTIONAL_LOCKS"] = "0" }
            .start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val output = executor.submit<String> {
                process.inputStream.bufferedReader().use { reader ->
                    val collected = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (collected.length + count > MAX_OUTPUT) throw IOException("Git 输出超过限额")
                        collected.append(buffer, 0, count)
                    }
                    collected.toString()
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("Git 只读查询超时")
            }
            val text = output.get(1, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { "Git 只读查询失败" }
            return text
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Git 查询已取消", interrupted)
        } finally {
            executor.shutdownNow()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    sealed interface Comparison {
        data class Available(
            val base: String,
            val work: String,
            val mergeBase: String,
            val commits: List<String>,
            val files: List<String>,
        ) : Comparison

        data class Unavailable(val reason: String) : Comparison
    }

    sealed interface Snapshot {
        data class Available(val branch: String, val changes: Int, val conflicts: Boolean, val upstream: String?, val ahead: Int?, val behind: Int?) : Snapshot
        data class Unavailable(val reason: String) : Snapshot
    }

    companion object {
        const val TIMEOUT_SECONDS = 10L
        const val MAX_OUTPUT = 1024 * 1024
        val CONFLICT_CODES = setOf("UU", "AA", "DD", "DU", "UD", "AU", "UA")
        fun forProject(project: Project): NativeGit = NativeGit(GitExecutableManager.getInstance().getPathToGit(project))
    }
}

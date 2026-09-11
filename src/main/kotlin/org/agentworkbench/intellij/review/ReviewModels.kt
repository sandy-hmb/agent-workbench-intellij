package org.agentworkbench.intellij.review

import java.net.URI
import java.time.Instant
import java.util.UUID

internal enum class ReviewPlatform { GITHUB, GITLAB }

/** 非敏感的代码托管服务配置；Token 仅保存在 PasswordSafe。 */
internal data class ReviewHost(
    var id: String = UUID.randomUUID().toString(),
    var platform: String = ReviewPlatform.GITHUB.name,
    var origin: String = "https://github.com",
    var sshAliases: String = "",
    var tokenConfigured: Boolean = false,
) {
    fun type(): ReviewPlatform? = runCatching { ReviewPlatform.valueOf(platform) }.getOrNull()

    fun normalizedOrigin(): String? = runCatching {
        val raw = if (type() == ReviewPlatform.GITHUB) "https://github.com" else origin.trim()
        val uri = URI(raw)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
        require((uri.path.isNullOrEmpty() || uri.path == "/") && uri.query == null && uri.fragment == null)
        URI("https", null, uri.host.lowercase(), uri.port, null, null, null).toString().removeSuffix("/")
    }.getOrNull()

    fun hosts(): Set<String> = buildSet {
        normalizedOrigin()?.let { add(URI(it).host.lowercase()) }
        sshAliases.split(',').map(String::trim).filter(String::isNotBlank).forEach { add(it.lowercase()) }
    }
}

internal data class RemoteProject(val host: String, val port: Int?, val path: String) {
    val segments: List<String> get() = path.split('/').filter(String::isNotBlank)
}

internal object RemoteProjectParser {
    private val scp = Regex("^([^@/:\\s]+)@([^:/\\s]+):(.+)$")

    fun parse(value: String): Result<RemoteProject> = runCatching {
        require(value.none { it.isISOControl() || it.isWhitespace() }) { "远程地址格式无效" }
        val raw = value.trim()
        val project = when {
            raw.startsWith("https://") || raw.startsWith("http://") || raw.startsWith("ssh://") -> fromUri(URI(raw))
            else -> scp.matchEntire(raw)?.let { match ->
                RemoteProject(match.groupValues[2].lowercase(), null, normalizePath(match.groupValues[3]))
            } ?: error("不支持的远程地址格式")
        }
        require(project.host.isNotBlank() && project.path.isNotBlank()) { "远程地址缺少项目路径" }
        project
    }

    private fun fromUri(uri: URI): RemoteProject {
        require(uri.scheme in setOf("https", "http", "ssh")) { "不支持的远程协议" }
        require(uri.host != null && (uri.scheme == "ssh" || uri.userInfo == null) && uri.query == null && uri.fragment == null) { "远程地址格式无效" }
        return RemoteProject(uri.host.lowercase(), uri.port.takeIf { it >= 0 }, normalizePath(uri.path.orEmpty()))
    }

    private fun normalizePath(path: String): String {
        val value = path.removePrefix("/").removeSuffix(".git")
        require(value.isNotBlank() && value.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "远程项目路径无效" }
        return value
    }
}

internal data class ReviewCandidate(
    val id: String,
    val number: String,
    val title: String,
    val url: String,
    val state: String,
    val sourceBranch: String,
    val targetBranch: String,
)

internal data class ReviewBinding(val repository: String, val root: java.nio.file.Path?, val workBranch: String?)

internal sealed interface ReviewRow {
    val repository: String
    data class Loading(override val repository: String) : ReviewRow
    data class Setup(override val repository: String, val message: String) : ReviewRow
    data class Missing(override val repository: String) : ReviewRow
    data class Link(override val repository: String, val review: ReviewCandidate, val stale: Boolean = false, val incomplete: String? = null, val observedAt: Instant = Instant.now()) : ReviewRow
    data class Multiple(override val repository: String, val candidates: List<ReviewCandidate>) : ReviewRow
    data class RemoteChoice(override val repository: String, val remotes: List<String>) : ReviewRow
    data class Error(override val repository: String, val message: String, val stale: ReviewCandidate? = null) : ReviewRow
}

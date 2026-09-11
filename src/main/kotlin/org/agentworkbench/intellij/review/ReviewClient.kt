package org.agentworkbench.intellij.review

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** GitHub/GitLab 评审的最小只读 REST 客户端。 */
internal class ReviewClient(
    private val transport: (String, ReviewPlatform, String) -> ReviewApiResponse = { url, platform, token -> request(url, platform, token) },
) {
    fun find(host: ReviewHost, project: RemoteProject, branch: String, token: String): ReviewLookup {
        val platform = host.type() ?: return ReviewLookup.Failure("代码托管平台配置无效")
        val origin = host.normalizedOrigin() ?: return ReviewLookup.Failure("服务地址配置无效")
        if (project.host !in host.hosts()) return ReviewLookup.Failure("remote 未匹配已配置的代码托管服务")
        if (branch.isBlank() || branch.any { it.isISOControl() }) return ReviewLookup.Failure("需求工作分支无效")
        return when (platform) {
            ReviewPlatform.GITHUB -> github(origin, project, branch, token)
            ReviewPlatform.GITLAB -> gitlab(origin, project, branch, token)
        }
    }

    private fun github(origin: String, project: RemoteProject, branch: String, token: String): ReviewLookup {
        if (project.segments.size != 2) return ReviewLookup.Failure("GitHub remote 缺少 owner/repository")
        val owner = project.segments[0]
        val repository = project.segments[1]
        val apiOrigin = "https://api.github.com"
        return pages(
            platform = ReviewPlatform.GITHUB,
            origin = origin,
            token = token,
            pageUrl = { page -> "$apiOrigin/repos/${part(owner)}/${part(repository)}/pulls?head=${query("$owner:$branch")}&state=all&per_page=100&page=$page" },
        ) { body -> parseGithub(body, "$owner/$repository", branch, origin) }
    }

    private fun gitlab(origin: String, project: RemoteProject, branch: String, token: String): ReviewLookup = pages(
        platform = ReviewPlatform.GITLAB,
        origin = origin,
        token = token,
        pageUrl = { page -> "$origin/api/v4/projects/${part(project.path)}/merge_requests?source_branch=${query(branch)}&state=all&scope=all&per_page=100&page=$page" },
    ) { body -> parseGitlab(body, branch, origin) }

    private fun pages(
        platform: ReviewPlatform,
        origin: String,
        token: String,
        pageUrl: (Int) -> String,
        parse: (String) -> ParsedPage,
    ): ReviewLookup {
        val candidates = linkedMapOf<String, ReviewCandidate>()
        var incomplete: String? = null
        for (page in 1..MAX_PAGES) {
            if (Thread.currentThread().isInterrupted) return ReviewLookup.Failure("评审查询已取消")
            val response = transport(pageUrl(page), platform, token)
            if (response.status !in 200..299) return ReviewLookup.Failure(httpMessage(response))
            val parsed = runCatching { parse(response.body) }.getOrElse { return ReviewLookup.Failure("评审响应格式无效") }
            parsed.items.forEach { candidates.putIfAbsent(it.id, it) }
            incomplete = incomplete ?: parsed.incomplete
            val hasMore = response.nextPage != null
            if (!hasMore) return ReviewLookup.Matches(candidates.values.toList(), incomplete)
            if (page == MAX_PAGES) return ReviewLookup.Matches(candidates.values.toList(), "评审分页超过 ${MAX_PAGES} 页限制")
        }
        return ReviewLookup.Matches(candidates.values.toList(), incomplete)
    }

    companion object {
        internal fun request(url: String, platform: ReviewPlatform, token: String): ReviewApiResponse = try {
        HttpRequests.request(url)
            .connectTimeout(CONNECT_TIMEOUT)
            .readTimeout(READ_TIMEOUT)
            // HttpRequests 要求正数；1 只执行初始请求，收到 3xx 后不会访问跳转目标。
            .redirectLimit(1)
            .gzip(false)
            .useProxy(true)
            .isReadResponseOnError(true)
            .throwStatusCodeException(false)
            .tuner { connection ->
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Agent-Workbench-IntelliJ")
                if (platform == ReviewPlatform.GITHUB) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                } else {
                    connection.setRequestProperty("PRIVATE-TOKEN", token)
                }
                (connection as? HttpURLConnection)?.instanceFollowRedirects = false
            }
            .connect { request ->
                val connection = request.connection as? HttpURLConnection
                    ?: throw IOException("代码托管服务未返回 HTTP 连接")
                val status = connection.responseCode
                val input = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = input?.use(::readBounded).orEmpty()
                val next = connection.getHeaderField("X-Next-Page")?.takeIf(String::isNotBlank)
                    ?: connection.getHeaderField("Link")?.takeIf { it.contains("rel=\"next\"") }
                ReviewApiResponse(status, body, next, connection.getHeaderField("Retry-After"))
            }
    } catch (error: IOException) {
        ReviewApiResponse(0, "", null, null, error.message ?: "网络请求失败")
    }

        private fun readBounded(input: java.io.InputStream): String {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("评审查询已取消")
                val count = input.read(buffer)
                if (count < 0) return output.toString(StandardCharsets.UTF_8)
                if (output.size() + count > MAX_RESPONSE) throw IOException("评审响应超过大小限制")
                output.write(buffer, 0, count)
            }
        }

        private const val CONNECT_TIMEOUT = 5_000
        private const val READ_TIMEOUT = 10_000
        private const val MAX_RESPONSE = 2 * 1024 * 1024
        private const val MAX_PAGES = 5
    }

    private fun parseGithub(body: String, project: String, branch: String, origin: String): ParsedPage {
        val values = JsonParser.parseString(body).asArray()
        var incomplete: String? = null
        val matches = values.mapNotNull { value ->
            val item = value.obj() ?: return@mapNotNull null.also { incomplete = "评审响应含无效记录" }
            val head = item.obj("head")
            val sourceBranch = head?.str("ref")
            val sourceRepo = head?.obj("repo")?.str("full_name")
            if (sourceBranch == null || sourceRepo == null) {
                incomplete = "部分评审缺少来源信息"
                return@mapNotNull null
            }
            if (sourceBranch != branch || !sourceRepo.equals(project, ignoreCase = true)) return@mapNotNull null
            candidate(item, sourceBranch, item.obj("base")?.str("ref"), origin, "number")
        }
        return ParsedPage(matches, incomplete)
    }

    private fun parseGitlab(body: String, branch: String, origin: String): ParsedPage {
        val values = JsonParser.parseString(body).asArray()
        var incomplete: String? = null
        val matches = values.mapNotNull { value ->
            val item = value.obj() ?: return@mapNotNull null.also { incomplete = "评审响应含无效记录" }
            val sourceBranch = item.str("source_branch")
            val sourceProject = item.int("source_project_id")
            val targetProject = item.int("target_project_id")
            if (sourceBranch == null || sourceProject == null || targetProject == null) {
                incomplete = "部分评审缺少来源信息"
                return@mapNotNull null
            }
            if (sourceBranch != branch || sourceProject != targetProject) return@mapNotNull null
            candidate(item, sourceBranch, item.str("target_branch"), origin, "iid")
        }
        return ParsedPage(matches, incomplete)
    }

    private fun candidate(
        item: JsonObject,
        sourceBranch: String,
        targetBranch: String?,
        origin: String,
        numberField: String,
    ): ReviewCandidate? {
        val number = item.get(numberField)?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val url = (item.str("html_url") ?: item.str("web_url"))?.takeIf { safeBrowserUrl(it, origin) } ?: return null
        val merged = item.get("merged_at")?.takeUnless { it.isJsonNull } != null
        val state = if (merged) "merged" else item.str("state") ?: "unknown"
        return ReviewCandidate("$number:$url", number, item.str("title") ?: "未命名评审", url, state, sourceBranch, targetBranch ?: "—")
    }

    private fun httpMessage(response: ReviewApiResponse): String = when (response.status) {
        0 -> response.error ?: "无法连接代码托管服务"
        401 -> "Token 无效或已过期"
        403 -> "Token 权限不足或访问受限"
        404 -> "项目不存在或当前 Token 无权访问"
        429 -> response.retryAfter?.let { "请求过于频繁，请在 $it 秒后重试" } ?: "请求过于频繁，请稍后重试"
        in 500..599 -> "代码托管服务暂时不可用（HTTP ${response.status}）"
        in 300..399 -> "代码托管服务返回了不允许的重定向"
        else -> "评审查询失败（HTTP ${response.status}）"
    }

    private fun safeBrowserUrl(value: String, origin: String): Boolean = runCatching {
        val url = URI(value)
        val expected = URI(origin)
        url.scheme == "https" && url.host.equals(expected.host, ignoreCase = true) && url.port == expected.port && url.userInfo == null
    }.getOrDefault(false)

    private fun part(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    private fun query(value: String): String = part(value)

    private data class ParsedPage(val items: List<ReviewCandidate>, val incomplete: String?)
}

internal data class ReviewApiResponse(val status: Int, val body: String, val nextPage: String?, val retryAfter: String?, val error: String? = null)

internal sealed interface ReviewLookup {
    data class Matches(val candidates: List<ReviewCandidate>, val incomplete: String?) : ReviewLookup
    data class Failure(val message: String) : ReviewLookup
}

private fun JsonElement.asArray(): JsonArray = takeIf { isJsonArray }?.asJsonArray ?: error("响应不是列表")
private fun JsonElement.obj(): JsonObject? = takeIf { isJsonObject }?.asJsonObject
private fun JsonObject.obj(name: String): JsonObject? = get(name)?.obj()
private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

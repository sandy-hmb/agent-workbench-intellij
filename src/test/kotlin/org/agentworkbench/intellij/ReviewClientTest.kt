package org.agentworkbench.intellij

import org.agentworkbench.intellij.review.RemoteProject
import org.agentworkbench.intellij.review.ReviewApiResponse
import org.agentworkbench.intellij.review.ReviewClient
import org.agentworkbench.intellij.review.ReviewHost
import org.agentworkbench.intellij.review.ReviewLookup
import org.agentworkbench.intellij.review.ReviewPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ReviewClientTest {
    @Test
    fun realTransportPreservesHttpErrorStatusForFriendlyMessage() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val responder = thread(isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                val body = "{\"message\":\"Not Found\"}"
                socket.getOutputStream().bufferedWriter().use { writer ->
                    writer.write("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
                }
            }
        }
        try {
            val response = ReviewClient.request(
                "http://${server.inetAddress.hostAddress}:${server.localPort}/repos/acme/service/pulls",
                ReviewPlatform.GITHUB,
                "test-token",
            )

            assertEquals(response.toString(), 404, response.status)
        } finally {
            server.close()
            responder.join(1_000)
        }
    }

    @Test
    fun githubNotFoundIsRenderedAsPermissionOrRepositoryError() {
        val client = ReviewClient { _, _, _ -> ReviewApiResponse(404, "{\"message\":\"Not Found\"}", null, null) }

        val result = client.find(github(), RemoteProject("github.com", null, "acme/service"), "feature/new", "test-token") as ReviewLookup.Failure

        assertEquals("项目不存在或当前 Token 无权访问", result.message)
    }

    @Test
    fun realTransportAcceptsNoRedirectConfiguration() {
        val host = ReviewHost(
            platform = ReviewPlatform.GITLAB.name,
            origin = "https://127.0.0.1:1",
        )

        val result = ReviewClient().find(
            host,
            RemoteProject("127.0.0.1", 1, "team/service"),
            "feature/new",
            "test-token",
        )

        assertTrue(result is ReviewLookup.Failure)
    }

    @Test
    fun noPullRequestReturnsAnEmptyCompletedResult() {
        val client = ReviewClient { _, _, _ -> ReviewApiResponse(200, "[]", null, null) }

        val result = client.find(github(), RemoteProject("github.com", null, "acme/service"), "feature/new", "test-token") as ReviewLookup.Matches

        assertTrue(result.candidates.isEmpty())
        assertEquals(null, result.incomplete)
    }

    @Test
    fun findsOnlyTheSameRepositoryAndBranchOnGitHubAcrossPages() {
        val calls = mutableListOf<String>()
        val client = ReviewClient { url, _, _ ->
            calls += url
            if (calls.size == 1) ReviewApiResponse(200, """[
              {"number":7,"title":"wrong branch","html_url":"https://github.com/acme/service/pull/7","state":"open","head":{"ref":"other","repo":{"full_name":"acme/service"}},"base":{"ref":"main"}},
              {"number":8,"title":"wallet","html_url":"https://github.com/acme/service/pull/8","state":"closed","merged_at":"2026-09-11T00:00:00Z","head":{"ref":"feature/wallet","repo":{"full_name":"acme/service"}},"base":{"ref":"develop"}}
            ]""", "next", null)
            else ReviewApiResponse(200, """[
              {"number":9,"title":"fork","html_url":"https://github.com/acme/service/pull/9","state":"open","head":{"ref":"feature/wallet","repo":{"full_name":"fork/service"}},"base":{"ref":"develop"}}
            ]""", null, null)
        }

        val result = client.find(github(), RemoteProject("github.com", null, "acme/service"), "feature/wallet", "test-token") as ReviewLookup.Matches

        assertEquals(2, calls.size)
        assertTrue(calls.first().contains("head=acme%3Afeature%2Fwallet"))
        assertEquals(1, result.candidates.size)
        assertEquals("8", result.candidates.single().number)
        assertEquals("merged", result.candidates.single().state)
    }

    @Test
    fun rejectsGitLabForkAndPreservesSameProjectMergeRequest() {
        val client = ReviewClient { _, _, _ -> ReviewApiResponse(200, """[
          {"iid":11,"title":"fork","web_url":"https://gitlab.example.com/team/service/-/merge_requests/11","state":"opened","source_branch":"feature/wallet","target_branch":"develop","source_project_id":8,"target_project_id":9},
          {"iid":12,"title":"same project","web_url":"https://gitlab.example.com/team/service/-/merge_requests/12","state":"merged","source_branch":"feature/wallet","target_branch":"develop","source_project_id":9,"target_project_id":9}
        ]""", null, null) }

        val result = client.find(gitlab(), RemoteProject("gitlab.example.com", null, "team/service"), "feature/wallet", "test-token") as ReviewLookup.Matches

        assertEquals(listOf("12"), result.candidates.map { it.number })
    }

    @Test
    fun reportsMissingSourceFieldsAsIncompleteInsteadOfNoReview() {
        val client = ReviewClient { _, _, _ -> ReviewApiResponse(200, """[{"iid":12,"title":"unknown","web_url":"https://gitlab.example.com/team/service/-/merge_requests/12","state":"opened"}]""", null, null) }

        val result = client.find(gitlab(), RemoteProject("gitlab.example.com", null, "team/service"), "feature/wallet", "test-token") as ReviewLookup.Matches

        assertTrue(result.candidates.isEmpty())
        assertTrue(result.incomplete!!.contains("来源"))
    }

    private fun github() = ReviewHost(platform = ReviewPlatform.GITHUB.name)
    private fun gitlab() = ReviewHost(platform = ReviewPlatform.GITLAB.name, origin = "https://gitlab.example.com")
}

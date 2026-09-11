package org.agentworkbench.intellij

import org.agentworkbench.intellij.review.RemoteProjectParser
import org.agentworkbench.intellij.review.ReviewHost
import org.agentworkbench.intellij.review.ReviewPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewModelsTest {
    @Test
    fun parsesHttpsSshAndScpRemoteFormsWithoutLeakingCredentials() {
        assertEquals("group/service", RemoteProjectParser.parse("https://gitlab.example.com/group/service.git").getOrThrow().path)
        assertEquals("group/nested/service", RemoteProjectParser.parse("ssh://git@gitlab.example.com:2222/group/nested/service.git").getOrThrow().path)
        assertEquals("acme/service", RemoteProjectParser.parse("git@github.com:acme/service.git").getOrThrow().path)
        assertTrue(RemoteProjectParser.parse("https://token:secret@gitlab.example.com/team/service.git").isFailure)
    }

    @Test
    fun mapsExplicitSshAliasToConfiguredServiceWithoutPersistingToken() {
        val host = ReviewHost(platform = ReviewPlatform.GITLAB.name, origin = "https://gitlab.example.com", sshAliases = "gitlab-work, gitlab-alt", tokenConfigured = true)

        assertEquals("https://gitlab.example.com", host.normalizedOrigin())
        assertTrue("gitlab-work" in host.hosts())
        assertTrue(host.tokenConfigured)
        assertFalse(host.toString().contains("secret", ignoreCase = true))
    }
}

package org.agentworkbench.intellij

import org.agentworkbench.intellij.review.ReviewRow
import org.agentworkbench.intellij.review.runReviewLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureReviewServiceTest {
    @Test
    fun unexpectedWorkerFailureBecomesVisibleTerminalState() {
        val (repository, row) = runReviewLookup("service") { error("synthetic failure") }

        assertEquals("service", repository)
        assertTrue(row is ReviewRow.Error)
        assertEquals("评审查询失败，请重试", (row as ReviewRow.Error).message)
    }
}

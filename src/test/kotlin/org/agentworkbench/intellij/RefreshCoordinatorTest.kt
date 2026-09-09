package org.agentworkbench.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RefreshCoordinatorTest {
    @Test fun evictionNeverReusesAnOldRequestGeneration() {
        val coordinator = RefreshCoordinator<Unit>()
        val old = coordinator.begin("document:a")
        coordinator.trim(0)
        val current = coordinator.begin("document:a")
        assertFalse(old == current)
        assertFalse(coordinator.succeed("document:a", old, Unit, Instant.EPOCH))
        assertTrue(coordinator.succeed("document:a", current, Unit, Instant.EPOCH))
    }

    @Test fun dropsLateResultsAndKeepsSuccessfulSnapshotOnFailure() {
        val coordinator = RefreshCoordinator<String>()
        val first = coordinator.begin("feature:a")
        assertTrue(coordinator.succeed("feature:a", first, "first", Instant.EPOCH))
        val second = coordinator.begin("feature:a")
        assertFalse(coordinator.succeed("feature:a", first, "late", Instant.now()))
        assertTrue(coordinator.fail("feature:a", second, "temporary failure"))
        val resource = coordinator.current("feature:a")!!
        assertEquals("first", resource.value)
        assertEquals(Instant.EPOCH, resource.observedAt)
        assertEquals("temporary failure", resource.error)
    }
}

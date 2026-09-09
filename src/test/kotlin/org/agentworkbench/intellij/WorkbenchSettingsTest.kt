package org.agentworkbench.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import org.agentworkbench.intellij.ui.WorkbenchToolWindowFactory

class WorkbenchSettingsTest {
    @Test fun isolatesRootsAndBoundsReadingPositions() {
        val settings = WorkbenchSettings()
        val first = Files.createTempDirectory("workbench-one")
        val second = Files.createTempDirectory("workbench-two")
        settings.preference(first.toString()).query = "one"
        settings.preference(second.toString()).query = "two"
        repeat(101) { settings.rememberPosition(first.toString(), "document-$it", it) }
        assertEquals("one", settings.preference(first.toString()).query)
        assertEquals("two", settings.preference(second.toString()).query)
        assertEquals(100, settings.preference(first.toString()).positions.size)
        assertTrue(settings.preference(first.toString()).positions.none { it.key == "document-0" })
    }

    @Test fun discoversKitInCurrentOrDirectAgentWorkbenchDirectory() {
        val parent = Files.createTempDirectory("workbench-parent")
        val child = Files.createDirectories(parent.resolve("agent-workbench/scripts"))
        Files.writeString(child.resolve("kit.py"), "")

        assertEquals(parent.resolve("agent-workbench").toRealPath(), WorkbenchToolWindowFactory.suggestKitRoot(parent, null))
        assertEquals(parent.resolve("agent-workbench").toRealPath(), WorkbenchToolWindowFactory.suggestKitRoot(parent, parent.resolve("missing").toString()))

        Files.createDirectories(parent.resolve("scripts"))
        Files.writeString(parent.resolve("scripts/kit.py"), "")
        assertEquals(parent.toRealPath(), WorkbenchToolWindowFactory.suggestKitRoot(parent, null))
    }
}

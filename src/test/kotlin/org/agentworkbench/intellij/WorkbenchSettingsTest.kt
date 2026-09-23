package org.agentworkbench.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import org.agentworkbench.intellij.ui.WorkbenchToolWindowFactory

class WorkbenchSettingsTest {
    @Test fun defaultsFeatureListToIncomplete() {
        val root = Files.createTempDirectory("workbench-default-filter")
        val settings = WorkbenchSettings()
        assertEquals("未完成", settings.featureStatus(root.toString()))
        settings.preference(root.toString()).status = "全部"
        assertEquals("全部", settings.featureStatus(root.toString()))

        val explicit = Files.createTempDirectory("workbench-explicit-filter")
        settings.preference(explicit.toString()).status = "testing"
        assertEquals("testing", settings.featureStatus(explicit.toString()))
    }

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

    @Test fun featureViewMigratesGlobalTabOnlyOnceAndIsolatesSelections() {
        val settings = WorkbenchSettings()
        val root = Files.createTempDirectory("workbench-view").toString()
        settings.preference(root).tab = 2
        val first = settings.featureView(root, "first")
        assertEquals(2, first.tab)
        first.tab = 1
        first.task = "T06"
        first.repository = "service"
        first.file = "src/A.kt"
        first.filter = "待验证"
        first.offset = 42
        settings.preference(root).tab = 0
        val second = settings.featureView(root, "second")
        assertEquals(0, second.tab)
        assertEquals("", second.task)
        assertEquals(1, settings.featureView(root, "first").tab)
        assertEquals("src/A.kt", settings.featureView(root, "first").file)
        assertEquals(42, settings.featureView(root, "first").offset)
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

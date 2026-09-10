package org.agentworkbench.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.agentworkbench.intellij.ui.WorkbenchNavigation
import org.agentworkbench.intellij.ui.WorkbenchStatusBarWidgetFactory
import org.junit.Test
import java.nio.file.Files

class WorkbenchNavigationTest : BasePlatformTestCase() {

    @Test
    fun testNavigationFindVirtualFile() {
        val tempDir = Files.createTempDirectory("nav-test")
        val subFile = Files.createFile(tempDir.resolve("README.md"))
        val vf = WorkbenchNavigation.findVirtualFile(tempDir.toString(), "README.md")
        assertNotNull("Should find virtual file", vf)
        assertEquals("README.md", vf?.name)
    }

    @Test
    fun testStatusBarWidgetFactory() {
        val factory = WorkbenchStatusBarWidgetFactory()
        assertEquals("AgentWorkbenchStatus", factory.id)
        assertEquals("Agent Workbench", factory.displayName)
        assertTrue(factory.isAvailable(project))
        val widget = factory.createWidget(project)
        assertEquals("AgentWorkbenchStatus", widget.ID())
        widget.dispose()
    }
}

package org.agentworkbench.intellij

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.agentworkbench.intellij.ui.WorkbenchVirtualFile

class WorkbenchLifecycleTest : BasePlatformTestCase() {
    fun testReopeningUsesOneMemoryFileAndClosingReleasesItsEditor() {
        val file = WorkbenchService.getInstance(project).file()
        val manager = FileEditorManager.getInstance(project)

        assertFalse(file.isWritable)
        assertTrue(file.path.contains(WorkbenchVirtualFile.FILE_NAME))
        assertFalse(file.path.startsWith(project.basePath.orEmpty()))

        manager.openFile(file, true, true)
        manager.openFile(WorkbenchService.getInstance(project).file(), true, true)
        assertEquals(1, manager.getEditors(file).size)

        manager.closeFile(file)
        assertTrue(manager.getEditors(file).isEmpty())

        manager.openFile(file, true, true)
        assertEquals(1, manager.getEditors(file).size)
    }
}

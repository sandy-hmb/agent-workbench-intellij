package org.agentworkbench.intellij.ui

import com.intellij.testFramework.LightVirtualFile

internal class WorkbenchVirtualFile : LightVirtualFile(FILE_NAME, "") {
    init {
        isWritable = false
    }

    companion object {
        const val FILE_NAME = "Agent Workbench"
    }
}

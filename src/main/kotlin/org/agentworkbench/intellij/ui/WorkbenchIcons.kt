package org.agentworkbench.intellij.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

internal object WorkbenchIcons {
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/workbench.svg", WorkbenchIcons::class.java)
}

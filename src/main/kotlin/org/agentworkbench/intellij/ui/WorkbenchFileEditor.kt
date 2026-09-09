package org.agentworkbench.intellij.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

internal class WorkbenchFileEditor(private val project: Project, private val file: WorkbenchVirtualFile) : UserDataHolderBase(), FileEditor {
    private val panel = WorkbenchPanel(project).apply { refresh() }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName(): String = WorkbenchVirtualFile.FILE_NAME
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !project.isDisposed
    override fun selectNotify() = panel.setActive(true)
    override fun deselectNotify() = panel.setActive(false)
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun dispose() = com.intellij.openapi.util.Disposer.dispose(panel)
}

internal class WorkbenchFileEditorProvider : FileEditorProvider, com.intellij.openapi.project.DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is WorkbenchVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = WorkbenchFileEditor(project, file as WorkbenchVirtualFile)
    override fun getEditorTypeId(): String = "agent-workbench-editor"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

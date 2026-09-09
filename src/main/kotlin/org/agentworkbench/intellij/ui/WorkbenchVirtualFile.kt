package org.agentworkbench.intellij.ui

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/** 工作台伪文件：只作为编辑器 Tab 的载体，没有磁盘内容。 */
internal class WorkbenchVirtualFile : VirtualFile() {
    override fun getName(): String = FILE_NAME
    override fun getFileSystem(): VirtualFileSystem = WorkbenchFileSystem
    override fun getPath(): String = "/$FILE_NAME"
    override fun getFileType(): FileType = FileTypes.PLAIN_TEXT
    override fun isWritable(): Boolean = false
    override fun isDirectory(): Boolean = false
    override fun isValid(): Boolean = true
    override fun getParent(): VirtualFile? = null
    override fun getChildren(): Array<VirtualFile> = EMPTY_ARRAY
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
        throw UnsupportedOperationException("工作台文件不可写")
    override fun contentsToByteArray(): ByteArray = ByteArray(0)
    override fun getTimeStamp(): Long = 0L
    override fun getModificationStamp(): Long = 0L
    override fun getLength(): Long = 0L
    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) { postRunnable?.run() }
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    companion object {
        const val FILE_NAME = "Agent Workbench"
    }
}

private object WorkbenchFileSystem : VirtualFileSystem() {
    override fun getProtocol(): String = "agent-workbench"
    override fun findFileByPath(path: String): VirtualFile? = null
    override fun refresh(asynchronous: Boolean) = Unit
    override fun refreshAndFindFileByPath(path: String): VirtualFile? = null
    override fun addVirtualFileListener(listener: VirtualFileListener) = Unit
    override fun removeVirtualFileListener(listener: VirtualFileListener) = Unit
    override fun deleteFile(requestor: Any?, vFile: VirtualFile) = throw UnsupportedOperationException()
    override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) = throw UnsupportedOperationException()
    override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) = throw UnsupportedOperationException()
    override fun createChildFile(requestor: Any?, vDir: VirtualFile, fileName: String): VirtualFile = throw UnsupportedOperationException()
    override fun createChildDirectory(requestor: Any?, vDir: VirtualFile, dirName: String): VirtualFile = throw UnsupportedOperationException()
    override fun copyFile(requestor: Any?, virtualFile: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile = throw UnsupportedOperationException()
    override fun isReadOnly(): Boolean = true
}

package org.agentworkbench.intellij.ui

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.agentworkbench.intellij.WorkbenchService
import java.nio.file.Files
import java.nio.file.Path

/**
 * 负责与 IntelliJ 宿主编辑器协作：
 * 优先在主编辑器 Tab 打开物理文档或定位到指定代码行，支持 Jump to Source (F4) 与源码定位。
 */
internal object WorkbenchNavigation {

    fun findVirtualFile(kitRoot: String?, relativeOrAbsolutePath: String?): VirtualFile? {
        if (relativeOrAbsolutePath.isNullOrBlank()) return null
        val targetPath = if (Path.of(relativeOrAbsolutePath).isAbsolute) {
            Path.of(relativeOrAbsolutePath)
        } else {
            val root = kitRoot ?: return null
            Path.of(root, relativeOrAbsolutePath)
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetPath.normalize())
    }

    fun openInEditor(project: Project, file: VirtualFile, line: Int? = null, focusEditor: Boolean = true): Boolean {
        val descriptor = if (line != null && line > 0) {
            OpenFileDescriptor(project, file, line - 1, 0)
        } else {
            OpenFileDescriptor(project, file)
        }
        descriptor.navigate(focusEditor)
        return true
    }

    fun openInEditor(project: Project, kitRoot: String?, path: String, line: Int? = null, focusEditor: Boolean = true): Boolean {
        val vf = findVirtualFile(kitRoot, path) ?: return false
        return openInEditor(project, vf, line, focusEditor)
    }

    fun resolveFeatureDir(project: Project, slug: String): Path? {
        val kitRootStr = WorkbenchService.getInstance(project).snapshot().kitRoot ?: return null
        val rootPath = Path.of(kitRootStr)
        val candidate = rootPath.resolve("docs/development/features").resolve(slug)
        if (Files.exists(candidate)) return candidate
        val fallback = rootPath.resolve(".workspace/docs/features").resolve(slug)
        if (Files.exists(fallback)) return fallback
        return candidate
    }

    fun openFeatureInEditor(project: Project, slug: String, subPath: String = "README.md", line: Int? = null): Boolean {
        val dir = resolveFeatureDir(project, slug) ?: return false
        val targetFile = dir.resolve(subPath).normalize()
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetFile) ?: return false
        return openInEditor(project, vf, line, true)
    }

    fun locateFeatureInProjectView(project: Project, slug: String): Boolean {
        val dir = resolveFeatureDir(project, slug) ?: return false
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir) ?: return false
        ProjectView.getInstance(project).select(null, vf, true)
        return true
    }

    fun selectInProjectView(project: Project, file: VirtualFile) {
        ProjectView.getInstance(project).select(null, file, true)
    }
}

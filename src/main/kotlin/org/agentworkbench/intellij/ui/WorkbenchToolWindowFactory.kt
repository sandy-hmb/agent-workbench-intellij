package org.agentworkbench.intellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import org.agentworkbench.intellij.WorkbenchService
import org.agentworkbench.intellij.WorkbenchSettings
import java.awt.BorderLayout
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class WorkbenchToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val settings = WorkbenchSettings.getInstance()
        val projectRoot = project.basePath.orEmpty()
        val kitRoot = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("选择 Kit 根目录"))
            text = projectRoot
        }
        val python = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("选择 Python 解释器"))
            text = "python3"
        }
        val status = JBLabel("正在探测工作流 Kit…")
        val bind = {
            if (!TrustedProjects.isProjectTrusted(project)) {
                status.text = "项目未受信任，不能启动 Kit 查询。"
            } else {
                status.text = "正在读取工作区…"
                WorkbenchService.getInstance(project).bind(kitRoot.text, python.text) { state ->
                    if (state.workspace != null && state.kitRoot != null) runCatching {
                        settings.rememberBinding(projectRoot, state.kitRoot)
                        settings.preference(state.kitRoot).python = state.python ?: python.text
                    }
                    status.text = state.error ?: "已读取工作区；打开工作台查看。"
                }
            }
        }
        val content = JPanel(BorderLayout(0, 8)).apply {
            accessibleContext.accessibleName = "Agent Workbench 导航"
            border = JBUI.Borders.empty(8)
            add(JBLabel("Agent Workbench", WorkbenchIcons.ToolWindow, JBLabel.LEADING), BorderLayout.NORTH)
            add(JPanel(GridLayout(2, 1, 0, 6)).apply {
                add(row("Kit 根目录", kitRoot))
                add(row("Python", python))
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(8, 0)).apply {
                add(JButton("绑定并刷新").apply {
                    addActionListener { bind() }
                }, BorderLayout.WEST)
                add(JButton("打开工作台").apply { addActionListener { openWorkbench(project) } }, BorderLayout.CENTER)
                add(status, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(content, "", false))
        // 保存的绑定与自动探测都要做文件系统 IO，放到后台线程完成后再回填表单。
        ApplicationManager.getApplication().executeOnPooledThread {
            val savedKit = runCatching { settings.kitForProject(projectRoot) }.getOrNull()
            val suggestedKit = projectRoot.takeIf(String::isNotBlank)?.let { suggestKitRoot(Path.of(it), savedKit) }
            val suggestedPython = suggestedKit?.let { settings.preference(it.toString()).python }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (suggestedKit != null) kitRoot.text = suggestedKit.toString() else if (savedKit != null) kitRoot.text = savedKit
                if (suggestedPython != null) python.text = suggestedPython
                if (suggestedKit != null) bind() else status.text = "绑定受信任项目中的 Kit 后读取工作区。"
            }
        }
    }

    private fun row(label: String, field: JComponent) = JPanel(BorderLayout(8, 0)).apply {
        add(JBLabel(label), BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
    }

    companion object {
        internal fun suggestKitRoot(projectRoot: Path, savedKit: String?): Path? =
            sequenceOf(savedKit?.let(Path::of), projectRoot, projectRoot.resolve("agent-workbench"))
                .filterNotNull()
                .mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
                .firstOrNull { Files.isRegularFile(it.resolve("scripts/kit.py")) }

        fun openWorkbench(project: Project) = FileEditorManager.getInstance(project).openFile(WorkbenchService.getInstance(project).file(), true, true)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

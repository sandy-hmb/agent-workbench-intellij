package org.agentworkbench.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.DumbAwareAction
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
        
        // 1. 挂载专注侧边栏形态的导航树面板 (WorkbenchToolWindowPanel)
        val panel = WorkbenchToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // 标题栏操作：支持一键在主编辑区打开全屏视图
        val openInEditorAction = object : DumbAwareAction(
            "在主编辑区打开",
            "在主编辑区打开 Agent Workbench 全屏视图",
            AllIcons.Actions.OpenNewTab
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                openWorkbench(project)
            }
        }
        toolWindow.setTitleActions(listOf(openInEditorAction))

        // 绑定 ToolWindow 的可见/激活状态，确保侧边栏展开时实时刷新
        project.messageBus.connect(panel).subscribe(
            com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
            object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: com.intellij.openapi.wm.ToolWindowManager,
                    tw: ToolWindow,
                    changeType: com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
                ) {
                    if (tw.id == toolWindow.id && tw.isVisible) {
                        panel.refresh()
                    }
                }
            }
        )

        // 2. 异步尝试自动探测并绑定
        ApplicationManager.getApplication().executeOnPooledThread {
            val savedKit = runCatching { settings.kitForProject(projectRoot) }.getOrNull()
            val suggestedKit = projectRoot.takeIf(String::isNotBlank)?.let { suggestKitRoot(Path.of(it), savedKit) }
            val suggestedPython = suggestedKit?.let { settings.preference(it.toString()).python } ?: "python3"
            
            if (suggestedKit != null && TrustedProjects.isProjectTrusted(project)) {
                WorkbenchService.getInstance(project).bind(suggestedKit.toString(), suggestedPython) { state ->
                    if (state.workspace != null && state.kitRoot != null) runCatching {
                        settings.rememberBinding(projectRoot, state.kitRoot)
                        settings.preference(state.kitRoot).python = state.python ?: suggestedPython
                    }
                }
            }
        }
    }

    companion object {
        internal fun suggestKitRoot(projectRoot: Path, savedKit: String?): Path? =
            sequenceOf(
                savedKit?.let(Path::of),
                projectRoot,
                projectRoot.resolve("agent-workbench"),
                projectRoot.parent?.resolve("agent-workbench"),
                Path.of(System.getProperty("user.home"), "workspace/code/agent-workbench")
            )
                .filterNotNull()
                .mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
                .firstOrNull { Files.isRegularFile(it.resolve("scripts/kit.py")) }

        fun openWorkbench(project: Project) = FileEditorManager.getInstance(project).openFile(WorkbenchService.getInstance(project).file(), true, true)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

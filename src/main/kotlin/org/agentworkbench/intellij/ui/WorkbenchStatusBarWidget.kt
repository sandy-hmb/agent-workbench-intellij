package org.agentworkbench.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ClickListener
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.agentworkbench.intellij.WorkbenchService
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.JComponent

internal class WorkbenchStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Agent Workbench"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = WorkbenchStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "AgentWorkbenchStatus"
    }
}

internal class WorkbenchStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val label = JBLabel("Workbench").apply {
        icon = WorkbenchIcons.ToolWindow
        border = JBUI.Borders.empty(0, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Agent Workbench：点击打开工作台"
    }

    private var statusBar: StatusBar? = null

    init {
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                WorkbenchToolWindowFactory.openWorkbench(project)
                return true
            }
        }.installOn(label)

        val service = WorkbenchService.getInstance(project)
        service.subscribe(this) {
            update()
        }
        update()
    }

    private fun update() {
        UIUtil.invokeLaterIfNeeded {
            if (project.isDisposed) return@invokeLaterIfNeeded
            val snapshot = WorkbenchService.getInstance(project).snapshot()
            val featureSlug = snapshot.selectedDetail
            val plan = snapshot.detail?.data?.asJsonObject?.getAsJsonObject("summary")?.getAsJsonObject("planSummary")
            val text = when {
                featureSlug != null && plan != null -> {
                    val comp = plan.get("completed")?.asInt ?: 0
                    val total = plan.get("total")?.asInt ?: 0
                    "$featureSlug ($comp/$total)"
                }
                featureSlug != null -> featureSlug
                snapshot.kitRoot != null -> "Workbench 活跃"
                else -> "Workbench"
            }
            label.text = text
            label.toolTipText = when {
                featureSlug != null -> "Agent Workbench 当前需求：$featureSlug (点击切换或查看)"
                snapshot.kitRoot != null -> "Agent Workbench 活跃工作区：${snapshot.kitRoot}"
                else -> "Agent Workbench：点击打开工作台"
            }
            statusBar?.updateWidget(ID())
        }
    }

    override fun ID(): String = WorkbenchStatusBarWidgetFactory.ID
    override fun getComponent(): JComponent = label
    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        update()
    }

    override fun dispose() {
        statusBar = null
    }
}

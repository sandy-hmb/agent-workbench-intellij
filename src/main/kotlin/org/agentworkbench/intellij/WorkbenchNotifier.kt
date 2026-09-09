package org.agentworkbench.intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/** 后台结果统一走宿主通知；当前上下文错误仍由各页面内联展示。 */
internal object WorkbenchNotifier {
    private const val GROUP = "Agent Workbench"
    private val LOG = Logger.getInstance(WorkbenchNotifier::class.java)

    fun info(project: Project, title: String, content: String) = notify(project, title, content, NotificationType.INFORMATION)

    fun warn(project: Project, title: String, content: String) {
        LOG.warn("$title：$content")
        notify(project, title, content, NotificationType.WARNING)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(title, content, type).notify(project)
    }
}

package org.agentworkbench.intellij.ui

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.agentworkbench.intellij.WorkbenchService
import org.agentworkbench.intellij.WorkbenchSettings
import org.agentworkbench.intellij.review.FeatureReviewService
import org.agentworkbench.intellij.review.ReviewCredentials
import org.agentworkbench.intellij.review.ReviewHost
import org.agentworkbench.intellij.review.ReviewPlatform
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.SwingConstants

/** Settings | Tools | Agent Workbench：与 ToolWindow 表单等效的绑定入口。 */
internal class WorkbenchConfigurable(private val project: Project) : Configurable {
    private val kitField = TextFieldWithBrowseButton()
    private val pythonField = TextFieldWithBrowseButton()
    private val reviewEditors = mutableListOf<ReviewHostEditor>()
    private val removedReviewHosts = mutableListOf<ReviewHost>()
    private val reviewHostsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Agent Workbench"

    override fun createComponent(): JComponent {
        kitField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("选择 Kit 根目录"))
        pythonField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("选择 Python 解释器"))
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Kit 根目录：", kitField)
            .addLabeledComponent("Python 解释器：", pythonField)
            .addComponent(JBLabel("Kit 根目录需包含 scripts/kit.py；应用后自动重新绑定并读取工作区。").apply { border = JBUI.Borders.emptyTop(8) })
            .addComponent(reviewSettings())
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = form
        reset()
        return form
    }

    override fun isModified(): Boolean {
        val settings = WorkbenchSettings.getInstance()
        val savedKit = settings.kitForProject(project.basePath.orEmpty()).orEmpty()
        val savedPython = savedKit.takeIf(String::isNotBlank)?.let { settings.preference(it).python } ?: "python3"
        return kitField.text.trim() != savedKit || pythonField.text.trim() != savedPython || reviewEditors.any(ReviewHostEditor::modified) || removedReviewHosts.isNotEmpty()
    }

    override fun reset() {
        val settings = WorkbenchSettings.getInstance()
        val savedKit = settings.kitForProject(project.basePath.orEmpty()).orEmpty()
        kitField.text = savedKit
        pythonField.text = savedKit.takeIf(String::isNotBlank)?.let { settings.preference(it).python } ?: "python3"
        replaceReviewEditors(savedKit.takeIf(String::isNotBlank)?.let(settings::reviewHosts).orEmpty())
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val kitRoot = kitField.text.trim()
        val python = pythonField.text.trim()
        if (kitRoot.isBlank()) throw ConfigurationException("需要填写 Kit 根目录。")
        if (python.isBlank()) throw ConfigurationException("需要填写 Python 解释器。")
        val entry = runCatching { Path.of(kitRoot).resolve("scripts/kit.py") }.getOrNull()
        if (entry == null || !Files.isRegularFile(entry)) throw ConfigurationException("Kit 根目录中未找到 scripts/kit.py：$kitRoot")
        val settings = WorkbenchSettings.getInstance()
        val hosts = reviewEditors.map(ReviewHostEditor::host)
        validateHosts(hosts)
        val tokenChanges = buildList {
            removedReviewHosts.forEach { add(it to null) }
            reviewEditors.forEach { editor ->
                if (editor.previousTokenMustBeCleared()) add(editor.savedHost() to null)
                when (val change = editor.tokenChange()) {
                    TokenChange.Clear -> add(editor.host() to null)
                    is TokenChange.Replace -> add(editor.host() to change.value)
                    TokenChange.Keep -> Unit
                }
            }
        }
        try {
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                tokenChanges.forEach { (host, token) -> ReviewCredentials.store(kitRoot, host, token) }
            }.get()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ConfigurationException("保存代码托管凭据被取消。")
        } catch (failure: ExecutionException) {
            throw ConfigurationException("无法保存代码托管凭据：${failure.cause?.message ?: "未知错误"}")
        }
        settings.rememberBinding(project.basePath.orEmpty(), kitRoot)
        settings.preference(kitRoot).python = python
        settings.replaceReviewHosts(kitRoot, hosts)
        removedReviewHosts.clear()
        reviewEditors.forEach(ReviewHostEditor::markSaved)
        FeatureReviewService.getInstance(project).invalidate()
        if (TrustedProjects.isProjectTrusted(project)) WorkbenchService.getInstance(project).bind(kitRoot, python) { }
    }

    override fun disposeUIResources() { panel = null }

    private fun reviewSettings(): JComponent = JPanel(java.awt.BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, com.intellij.ui.JBColor.border()), JBUI.Borders.emptyTop(14))
        val header = JPanel(java.awt.BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("代码托管服务").apply { horizontalAlignment = SwingConstants.LEFT }, java.awt.BorderLayout.WEST)
            add(JButton("添加服务").apply { addActionListener { addReviewEditor(ReviewHost()) } }, java.awt.BorderLayout.EAST)
        }
        add(header, java.awt.BorderLayout.NORTH)
        add(reviewHostsPanel, java.awt.BorderLayout.CENTER)
        add(JBLabel("Token 使用 IDE 密码库保存。GitHub 需 Pull requests: Read；GitLab 需 read_api。"), java.awt.BorderLayout.SOUTH)
    }

    private fun replaceReviewEditors(hosts: List<ReviewHost>) {
        reviewEditors.clear()
        removedReviewHosts.clear()
        reviewHostsPanel.removeAll()
        hosts.forEach(::addReviewEditor)
        reviewHostsPanel.revalidate()
        reviewHostsPanel.repaint()
    }

    private fun addReviewEditor(host: ReviewHost) {
        lateinit var editor: ReviewHostEditor
        editor = ReviewHostEditor(host) {
            reviewEditors.removeIf { it === editor }
            removedReviewHosts += host
            reviewHostsPanel.remove(editor.panel)
            reviewHostsPanel.revalidate()
            reviewHostsPanel.repaint()
        }
        reviewEditors += editor
        reviewHostsPanel.add(editor.panel)
        reviewHostsPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        reviewHostsPanel.revalidate()
        reviewHostsPanel.repaint()
    }

    private fun validateHosts(hosts: List<ReviewHost>) {
        if (hosts.map { it.id }.distinct().size != hosts.size) throw ConfigurationException("代码托管服务配置重复。")
        hosts.forEach { host ->
            if (host.type() == null || host.normalizedOrigin() == null) throw ConfigurationException("代码托管服务地址无效。")
            val aliases = host.sshAliases.split(',').map(String::trim).filter(String::isNotBlank)
            if (aliases.any { !it.matches(Regex("[A-Za-z0-9.-]+")) }) throw ConfigurationException("SSH 主机别名只能包含字母、数字、点或连字符。")
        }
    }

    private sealed interface TokenChange {
        data object Keep : TokenChange
        data object Clear : TokenChange
        data class Replace(val value: String) : TokenChange
    }

    private class ReviewHostEditor(original: ReviewHost, private val onRemove: () -> Unit) {
        private var saved = original.copy()
        private val platform = JComboBox(arrayOf(ReviewPlatform.GITHUB.name, ReviewPlatform.GITLAB.name)).apply { selectedItem = original.platform }
        private val origin = JBTextField(original.origin)
        private val aliases = JBTextField(original.sshAliases)
        private val token = JPasswordField()
        private var clearToken = false
        val panel = JPanel(java.awt.GridBagLayout()).apply { isOpaque = false; border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(8)) }

        init {
            fun add(label: String, component: JComponent, x: Int, y: Int, weight: Double = 1.0) {
                panel.add(JBLabel(label), java.awt.GridBagConstraints().apply { gridx = x; gridy = y; anchor = java.awt.GridBagConstraints.WEST; insets = java.awt.Insets(2, 2, 2, 6) })
                panel.add(component, java.awt.GridBagConstraints().apply { gridx = x + 1; gridy = y; weightx = weight; fill = java.awt.GridBagConstraints.HORIZONTAL; insets = java.awt.Insets(2, 0, 2, 8) })
            }
            add("平台", platform, 0, 0, 0.4)
            add("服务地址", origin, 2, 0)
            add("SSH 别名", aliases, 0, 1, 0.4)
            add("Token", token, 2, 1)
            val controls = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(JButton(if (original.tokenConfigured) "移除已保存 Token" else "移除 Token").apply { addActionListener { clearToken = true; token.text = "" } })
                add(JButton("移除服务").apply { addActionListener { onRemove() } })
            }
            panel.add(controls, java.awt.GridBagConstraints().apply { gridx = 0; gridy = 2; gridwidth = 4; anchor = java.awt.GridBagConstraints.EAST; weightx = 1.0; fill = java.awt.GridBagConstraints.HORIZONTAL; insets = java.awt.Insets(4, 0, 0, 0) })
            platform.addActionListener { updatePlatform() }
            updatePlatform()
        }

        fun host(): ReviewHost = saved.copy(
            platform = platform.selectedItem.toString(),
            origin = if (platform.selectedItem == ReviewPlatform.GITHUB.name) "https://github.com" else origin.text.trim(),
            sshAliases = aliases.text.trim(),
            tokenConfigured = when (tokenChange()) {
                TokenChange.Clear -> false
                is TokenChange.Replace -> true
                TokenChange.Keep -> saved.tokenConfigured && !previousTokenMustBeCleared()
            },
        )

        fun tokenChange(): TokenChange {
            if (clearToken) return TokenChange.Clear
            val chars = token.password
            return try { if (chars.isEmpty()) TokenChange.Keep else TokenChange.Replace(String(chars)) } finally { chars.fill('\u0000') }
        }

        fun modified(): Boolean = host() != saved || hasTokenInput() || clearToken
        fun markSaved() { saved = host(); clearToken = false; token.text = "" }
        fun savedHost(): ReviewHost = saved.copy()
        fun previousTokenMustBeCleared(): Boolean = saved.tokenConfigured && (
            saved.platform != platform.selectedItem.toString() || saved.normalizedOrigin() != currentOrigin()
        )
        private fun hasTokenInput(): Boolean {
            val chars = token.password
            return try { chars.isNotEmpty() } finally { chars.fill('\u0000') }
        }
        private fun updatePlatform() {
            val github = platform.selectedItem == ReviewPlatform.GITHUB.name
            origin.isEnabled = !github
            if (github) origin.text = "https://github.com"
        }
        private fun currentOrigin(): String? = hostForOrigin().normalizedOrigin()
        private fun hostForOrigin() = saved.copy(
            platform = platform.selectedItem.toString(),
            origin = if (platform.selectedItem == ReviewPlatform.GITHUB.name) "https://github.com" else origin.text.trim(),
        )
    }
}

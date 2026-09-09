package org.agentworkbench.intellij.ui

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.agentworkbench.intellij.WorkbenchService
import org.agentworkbench.intellij.WorkbenchSettings
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings | Tools | Agent Workbench：与 ToolWindow 表单等效的绑定入口。 */
internal class WorkbenchConfigurable(private val project: Project) : Configurable {
    private val kitField = TextFieldWithBrowseButton()
    private val pythonField = TextFieldWithBrowseButton()
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Agent Workbench"

    override fun createComponent(): JComponent {
        kitField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("选择 Kit 根目录"))
        pythonField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("选择 Python 解释器"))
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Kit 根目录：", kitField)
            .addLabeledComponent("Python 解释器：", pythonField)
            .addComponent(JBLabel("Kit 根目录需包含 scripts/kit.py；应用后自动重新绑定并读取工作区。").apply { border = JBUI.Borders.emptyTop(8) })
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
        return kitField.text.trim() != savedKit || pythonField.text.trim() != savedPython
    }

    override fun reset() {
        val settings = WorkbenchSettings.getInstance()
        val savedKit = settings.kitForProject(project.basePath.orEmpty()).orEmpty()
        kitField.text = savedKit
        pythonField.text = savedKit.takeIf(String::isNotBlank)?.let { settings.preference(it).python } ?: "python3"
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
        settings.rememberBinding(project.basePath.orEmpty(), kitRoot)
        settings.preference(kitRoot).python = python
        if (TrustedProjects.isProjectTrusted(project)) WorkbenchService.getInstance(project).bind(kitRoot, python) { }
    }

    override fun disposeUIResources() { panel = null }
}

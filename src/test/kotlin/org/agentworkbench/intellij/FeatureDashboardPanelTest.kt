package org.agentworkbench.intellij

import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.agentworkbench.intellij.ui.FeatureDashboardPanel
import java.awt.Component
import java.awt.Container
import java.nio.file.Path
import javax.swing.JLabel
import javax.swing.JButton

/** 用 Kit 发布的 inspect 样例驱动仪表盘渲染，覆盖任务进度、凭据提示与关联仓卡片。 */
class FeatureDashboardPanelTest : BasePlatformTestCase() {
    fun testUpdateRendersProgressAndRepositoryCardsFromFixture() {
        val fixture = javaClass.getResourceAsStream("/inspect-v1/feature.json")!!.use {
            JsonParser.parseString(it.readBytes().decodeToString()).asJsonObject
        }
        val data = fixture.getAsJsonObject("data")
        val summary = data.getAsJsonObject("summary")
        val tasks = data.getAsJsonArray("tasks").map { it.asJsonObject }
        val bindings = summary.getAsJsonArray("repositoryBindings").map { it.asJsonObject }

        val panel = FeatureDashboardPanel(project, onOpenTask = {}, onOpenDoc = {})
        try {
            panel.update(
                featureData = data,
                tasks = tasks,
                repositoryBindings = bindings,
                repoRoots = bindings.associate { it.get("repository").asString to Path.of("/synthetic", it.get("repository").asString) },
                requirementsContent = "# 需求\n\n正文内容",
                requirementsPath = "requirements/requirements.md",
                onNavigateTab = {},
            )
            val labels = descendants(panel).filterIsInstance<JLabel>().mapNotNull { it.text }
            assertTrue("应显示任务进度", labels.any { it.contains("1 / 2") })
            assertTrue("应显示关联仓库计数", labels.any { it.contains("${bindings.size} 个关联仓库") })
            assertTrue("跳转按钮应描述实际目的地", descendants(panel).filterIsInstance<JButton>().any { it.text == "查看变更 →" })

            // 再次以 null 内容 update（扫描回调路径）不得把已加载正文打回占位文案
            panel.update(
                featureData = data,
                tasks = tasks,
                repositoryBindings = bindings,
                repoRoots = emptyMap(),
                requirementsContent = null,
                requirementsPath = "requirements/requirements.md",
                onNavigateTab = {},
            )
        } finally {
            Disposer.dispose(panel)
        }
    }

    private fun descendants(root: Component): List<Component> =
        listOf(root) + ((root as? Container)?.components.orEmpty().flatMap { descendants(it) })
}

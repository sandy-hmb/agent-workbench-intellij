package org.agentworkbench.intellij

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.agentworkbench.intellij.ui.obj
import org.agentworkbench.intellij.ui.objects
import org.agentworkbench.intellij.ui.remediationText
import org.agentworkbench.intellij.ui.repositoryRoots
import org.agentworkbench.intellij.ui.verificationIssuesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * 用 Kit 发布的 inspect 样例（tests/fixtures/inspect-v1 同步件）逐字段校验插件的消费逻辑，
 * 防止再次出现"照 UI 想象读字段"导致的静默失效（repository.name/root、defaultBranch、
 * repositoryBindings[].id/path、verification.summary.issues、doctor remediation 等）。
 */
class KitContractRegressionTest {
    private fun fixture(name: String): JsonObject =
        javaClass.getResourceAsStream("/inspect-v1/$name.json")!!.use {
            JsonParser.parseString(it.readBytes().decodeToString()).asJsonObject
        }

    @Test
    fun repositoryRootsMapsSchemaIdToAbsolutePath() {
        val repositories = fixture("workspace").obj("data")!!.objects("repositories")
        assertTrue("样例应至少包含一个仓库", repositories.isNotEmpty())
        val roots = repositoryRoots(repositories)
        assertEquals("每个仓库都应映射成功（id/absolutePath 均为 schema 必填）", repositories.size, roots.size)
        roots.forEach { (id, path) ->
            assertTrue("id 不应为空", id.isNotBlank())
            assertTrue("路径应为绝对路径：$path", path.isAbsolute)
        }
    }

    @Test
    fun branchSlugMatchesBindingsByRepositoryIdAndAbsolutePath() {
        val features = fixture("features").obj("data")!!.objects("items")
        val feature = features.first { it.objects("repositoryBindings").isNotEmpty() }
        val slug = feature.get("slug").asString
        val binding = feature.objects("repositoryBindings").first()
        val repoId = binding.get("repository").asString
        val workBranch = binding.get("workBranch")?.takeIf { it.isJsonPrimitive }?.asString
            ?: "feat/synthetic-branch"

        val absolutePath = "/synthetic/$repoId"
        val repoPathById = mapOf(repoId to absolutePath)

        // 按绝对路径命中（IDE 打开的目录名与 Kit 仓库 id 不同的情况）
        assertEquals(
            slug,
            WorkbenchService.matchBranchSlug(
                features, repoPathById, listOf(Triple(absolutePath, "checkout-dir", workBranch))
            ),
        )
        // 按仓库 id（目录名）命中
        assertEquals(
            slug,
            WorkbenchService.matchBranchSlug(
                features, emptyMap(), listOf(Triple("/elsewhere/$repoId", repoId, workBranch))
            ),
        )
        // 分支不一致时不得命中
        assertNull(
            WorkbenchService.matchBranchSlug(
                features, repoPathById, listOf(Triple(absolutePath, repoId, "unrelated-branch"))
            ),
        )
    }

    @Test
    fun verificationIssuesComeFromSelectedBatchAndTargetTheFeatureDocument() {
        val verification = JsonParser.parseString(
            """{
              "slug":"demo-feature",
              "selectedBatch":{"issues":[
                {"code":"VERIFICATION_CHECK_INCOMPLETE","message":"检查记录缺少字段","path":"testing/verification.md","line":12},
                {"code":"OTHER","message":"其他文档问题","path":"plans/implementation.md","line":3}
              ]}
            }"""
        ).asJsonObject

        val hits = verificationIssuesFor(
            ".workspace/docs/features/demo-feature/testing/verification.md", verification
        )
        assertEquals(1, hits.size)
        assertEquals(12, hits[0].line)
        assertTrue(hits[0].message.contains("VERIFICATION_CHECK_INCOMPLETE"))

        // 其他需求目录下的同名文件不应被标注
        assertTrue(
            verificationIssuesFor(
                ".workspace/docs/features/another-feature/testing/verification.md", verification
            ).isEmpty()
        )
    }

    @Test
    fun verificationFixtureIssuesParseWithoutError() {
        val data = fixture("verification").obj("data")!!
        val slug = data.get("slug").asString
        // 样例中 issues 可能为空；关键是解析路径存在且不抛异常
        verificationIssuesFor(".workspace/docs/features/$slug/testing/verification.md", data)
    }

    @Test
    fun doctorRemediationIsAnObjectWithKindAndDetail() {
        val finding = JsonParser.parseString(
            """{"level":"ERROR","code":"WORKSPACE_TRACKED","message":"...",
                "remediation":{"kind":"command","detail":"git rm -r --cached .workspace"}}"""
        ).asJsonObject
        assertEquals("修复命令：git rm -r --cached .workspace", remediationText(finding))

        val manual = JsonParser.parseString(
            """{"remediation":{"kind":"manual","detail":"把 AGENTS.md 中的非核心内容迁移到 docs/。"}}"""
        ).asJsonObject
        assertEquals("修复建议：把 AGENTS.md 中的非核心内容迁移到 docs/。", remediationText(manual))

        val none = JsonParser.parseString("""{"remediation":null}""").asJsonObject
        assertNull(remediationText(none))
    }

    @Test
    fun repositoryFixtureCarriesBranchPolicyInsteadOfDefaultBranch() {
        val repositories = fixture("workspace").obj("data")!!.objects("repositories")
        repositories.forEach { repo ->
            assertNull("schema 无 defaultBranch 字段，读取它必然失效", repo.get("defaultBranch"))
            assertTrue("分支信息应来自 effectiveBranchPolicy", repo.has("effectiveBranchPolicy"))
        }
    }
}

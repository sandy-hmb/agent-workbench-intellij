package org.agentworkbench.intellij

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
import java.nio.file.Path

/** 经过真实 Python 进程和 Project service 验证刷新行为。 */
class WorkbenchRequestTest : BasePlatformTestCase() {
    private lateinit var kit: Path
    private lateinit var service: WorkbenchService

    override fun setUp() {
        super.setUp()
        kit = Files.createTempDirectory("workbench-client-").toRealPath()
        Files.createDirectories(kit.resolve("scripts"))
        for (operation in listOf("workspace", "features", "feature", "handoff", "search")) {
            val bytes = javaClass.getResourceAsStream("/inspect-v1/$operation.json")!!.use { it.readBytes() }
            Files.write(kit.resolve("$operation.json"), bytes)
        }
        Files.writeString(kit.resolve("scripts/kit.py"), """
            import json,pathlib,sys,time
            if sys.argv[1]=='brief': print('legacy brief'); sys.exit(0)
            root=pathlib.Path(sys.argv[sys.argv.index('--root')+1])
            op=sys.argv[sys.argv.index('--json')+1]
            if (root/'fail').exists(): sys.exit(1)
            data=json.loads((root/(op+'.json')).read_text())
            data['root']=str(root)
            if op=='features': data['data']['page']['hasMore']=False
            if op=='feature':
                slug=sys.argv[sys.argv.index('--json')+2]
                if slug=='slow': time.sleep(0.8)
                data['data']['summary']['slug']=slug
            if op=='handoff': data['data']['slug']=sys.argv[sys.argv.index('--json')+2]
            if op=='search':
                query=sys.argv[sys.argv.index('--query')+1]
                if query.startswith('slow'): time.sleep(0.8)
                if query=='slow-fail': sys.exit(1)
                data['data']['query']=query
                if query=='partial': data['status']='partial'
            print(json.dumps(data))
        """.trimIndent())
        service = WorkbenchService(project)
    }

    override fun tearDown() {
        val retained = kit
        try {
            Disposer.dispose(service)
            super.tearDown()
        } finally { retained.toFile().deleteRecursively() }
    }

    private fun ready() {
        service.bind(kit.toString(), "/usr/bin/python3") { }
        PlatformTestUtil.waitWithEventsDispatching("首次绑定", { service.snapshot().workspace != null && service.snapshot().features.isNotEmpty() }, 5)
    }

    fun testFailedRefreshOfSameRootPreservesPreviousWorkspaceAndFeatures() {
        ready()
        val previous = service.snapshot()
        Files.writeString(kit.resolve("fail"), "fail next read")
        service.bind(kit.toString(), "/usr/bin/python3") { }
        PlatformTestUtil.waitWithEventsDispatching("读取失败", { service.snapshot().error != null }, 5)
        assertEquals(previous.workspace, service.snapshot().workspace)
        assertEquals(previous.features, service.snapshot().features)
    }

    fun testSlowPreviouslySelectedFeatureCannotReplaceCurrentSelection() {
        ready()
        service.loadFeature("slow") { }
        service.loadFeature("latest") { }
        PlatformTestUtil.waitWithEventsDispatching("当前需求返回", { service.snapshot().detail?.data?.asJsonObject?.getAsJsonObject("summary")?.get("slug")?.asString == "latest" }, 5)
        val until = System.nanoTime() + 1_200_000_000L
        PlatformTestUtil.waitWithEventsDispatching("迟到响应窗口", { System.nanoTime() >= until }, 3)
        assertEquals("latest", service.snapshot().detail?.data?.asJsonObject?.getAsJsonObject("summary")?.get("slug")?.asString)
    }

    fun testSlowSearchCannotReplaceCurrentQuery() {
        ready()
        service.searchHistory("slow", null, null) { }
        service.searchHistory("latest", null, null) { }
        PlatformTestUtil.waitWithEventsDispatching("当前搜索返回", { service.snapshot().search?.query == "latest" }, 5)
        val until = System.nanoTime() + 1_200_000_000L
        PlatformTestUtil.waitWithEventsDispatching("迟到搜索窗口", { System.nanoTime() >= until }, 3)
        assertEquals("latest", service.snapshot().search?.query)
    }

    fun testSlowOldSearchFailureCannotReplaceCurrentSuccess() {
        ready()
        service.searchHistory("slow-fail", null, null) { }
        service.searchHistory("latest", null, null) { }
        PlatformTestUtil.waitWithEventsDispatching("当前搜索返回", { service.snapshot().search?.query == "latest" }, 5)
        val until = System.nanoTime() + 1_200_000_000L
        PlatformTestUtil.waitWithEventsDispatching("迟到失败窗口", { System.nanoTime() >= until }, 3)
        assertEquals("latest", service.snapshot().search?.query)
        assertNull(service.snapshot().error)
    }

    fun testPartialSearchKeepsResultsAndMarksThemIncomplete() {
        ready()
        service.searchHistory("partial", null, null) { }
        PlatformTestUtil.waitWithEventsDispatching("部分搜索返回", { service.snapshot().search?.query == "partial" }, 5)
        assertTrue(service.snapshot().search!!.incomplete)
        assertNull(service.snapshot().error)
    }

    fun testSameRootCanSwitchPythonBinding() {
        ready()
        val alternate = Files.createTempFile("workbench-python-", "").toRealPath()
        try {
            Files.writeString(alternate, "#!/bin/sh\nexec /usr/bin/python3 \"\u0024@\"\n")
            alternate.toFile().setExecutable(true)
            service.bind(kit.toString(), alternate.toString()) { }
            PlatformTestUtil.waitWithEventsDispatching("解释器切换", { service.snapshot().python == alternate.toString() && service.snapshot().workspace != null }, 5)
            assertEquals(alternate.toString(), service.snapshot().python)
        } finally { Files.deleteIfExists(alternate) }
    }

    fun testOldKitCopiesLegacyBriefWhenHandoffIsUnavailable() {
        val workspace = kit.resolve("workspace.json")
        val text = Files.readString(workspace).replace("\"handoff\",", "")
        Files.writeString(workspace, text)
        ready()
        assertFalse(service.supports("handoff"))
        var copied: Result<String>? = null
        service.copyPrompt("demo") { copied = it }
        PlatformTestUtil.waitWithEventsDispatching("兼容 brief 返回", { copied != null }, 5)
        assertEquals("legacy brief\n", copied!!.getOrThrow())
    }
}

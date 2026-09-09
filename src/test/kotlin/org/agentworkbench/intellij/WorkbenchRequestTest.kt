package org.agentworkbench.intellij

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
        for (operation in listOf("workspace", "features", "feature")) {
            val bytes = javaClass.getResourceAsStream("/inspect-v1/$operation.json")!!.use { it.readBytes() }
            Files.write(kit.resolve("$operation.json"), bytes)
        }
        Files.writeString(kit.resolve("scripts/kit.py"), """
            import json,pathlib,sys,time
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
            print(json.dumps(data))
        """.trimIndent())
        service = WorkbenchService.getInstance(project)
    }

    override fun tearDown() {
        val retained = kit
        try { super.tearDown() } finally { retained.toFile().deleteRecursively() }
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
}

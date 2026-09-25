package org.agentworkbench.intellij

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.agentworkbench.intellij.ui.classifyWorkItemChange
import java.nio.file.Files
import java.nio.file.Path

/** Actual subprocess tests for pagination and out-of-order responses. */
class WorkbenchQueryReliabilityTest : BasePlatformTestCase() {
    private fun createKit(): Path {
        val root = Files.createTempDirectory("workbench-query-").toRealPath()
        Files.createDirectories(root.resolve("scripts"))
        for (name in listOf("workspace", "task", "document", "verification")) {
            val text = javaClass.getResourceAsStream("/inspect-v2/" + name + ".json")!!.use { it.readBytes().decodeToString() }
            Files.writeString(root.resolve(name + ".json"), text)
        }
        return root
    }

    private fun bind(root: Path): WorkbenchService {
        val service = WorkbenchService.getInstance(project)
        service.bind(root.toString(), "/usr/bin/python3") { }
        PlatformTestUtil.waitWithEventsDispatching("binding", { service.snapshot().workspace?.root == root.toString() }, 10)
        return service
    }

    private fun cleanup(root: Path) {
        WorkbenchSettings.getInstance().state.bindings.removeIf { it.kitRoot == root.toString() }
        root.toFile().deleteRecursively()
    }

    fun testStableCollectionLoadsMoreThanTwoHundredItems() {
        val root = createKit()
        Files.writeString(root.resolve("scripts/kit.py"), """
            import json,pathlib,sys
            root=pathlib.Path(sys.argv[sys.argv.index('--root')+1])
            op=sys.argv[sys.argv.index('--json')+1]
            data=json.loads((root/'workspace.json').read_text())
            data.update(root=str(root),operation=op)
            if op=='items':
                offset=int(sys.argv[sys.argv.index('--offset')+1])
                data['responseRevision']='page-'+str(offset)
                rows=[{'slug':'item-'+str(i),'title':'Item','status':'active','repositoryBindings':[]} for i in range(201)]
                data['data']={'collectionRevision':'stable','items':rows[offset:offset+200],
                    'counts':{'total':201},'page':{'offset':offset,'limit':200,'total':201,'hasMore':offset==0}}
            print(json.dumps(data))
        """.trimIndent())
        try {
            val service = bind(root)
            PlatformTestUtil.waitWithEventsDispatching("all pages", { service.snapshot().items.size == 201 }, 10)
            assertNull(service.snapshot().error)
        } finally { cleanup(root) }
    }

    fun testLateDocumentFailureCannotReplaceNewItemsSuccess() = lateRequest("document", true)
    fun testLateVerificationSuccessCannotClearNewItemsFailure() = lateRequest("verification", false)

    private fun lateRequest(operation: String, oldFails: Boolean) {
        val root = createKit()
        val gate = Files.createTempDirectory("query-gate-").toRealPath()
        val script = """
            import json,pathlib,sys,time
            root=pathlib.Path(sys.argv[sys.argv.index('--root')+1])
            gate=pathlib.Path(GATE_PATH)
            op=sys.argv[sys.argv.index('--json')+1]
            slug=sys.argv[sys.argv.index('--json')+2] if op in ('projection','document','verification') else None
            source='task' if op=='projection' else op
            data=json.loads((root/('workspace.json' if op=='items' else source+'.json')).read_text())
            data.update(root=str(root),operation=op)
            if op=='items': data['data']={'collectionRevision':'empty','items':[],'counts':{},'page':{'offset':0,'limit':200,'total':0,'hasMore':False}}
            elif op=='projection': data['data']['summary']['slug']=slug; data['data']['slug']=slug
            elif op in ('document','verification'):
                data['data']['slug']=slug
                if slug=='a':
                    (gate/'started').write_text('yes')
                    deadline=time.monotonic()+5
                    while not (gate/'release').exists() and time.monotonic()<deadline: time.sleep(.01)
                fail=(slug=='a')==OLD_FAILS
                if fail: data.update(status='error',data=None,diagnostics=[{'code':'OLD_FAILURE' if slug=='a' else 'CURRENT_FAILURE','message':'read failed'}])
                if slug=='a': (gate/'returned').write_text('yes')
            print(json.dumps(data))
        """.trimIndent().replace("GATE_PATH", com.google.gson.Gson().toJson(gate.toString()))
            .replace("OLD_FAILS", if (oldFails) "True" else "False")
        Files.writeString(root.resolve("scripts/kit.py"), script)
        try {
            val service = bind(root)
            service.loadWorkItem("a") { }
            PlatformTestUtil.waitWithEventsDispatching("selected a", { service.snapshot().detail?.data?.asJsonObject?.getAsJsonObject("summary")?.get("slug")?.asString == "a" }, 10)
            fun request(slug: String) {
                if (operation == "document") service.loadDocument(slug, "change.md", null) { }
                else service.loadVerification(slug) { }
            }
            request("a")
            PlatformTestUtil.waitWithEventsDispatching("old started", { Files.exists(gate.resolve("started")) }, 10)
            service.loadWorkItem("b") { }
            PlatformTestUtil.waitWithEventsDispatching("selected b", { service.snapshot().detail?.data?.asJsonObject?.getAsJsonObject("summary")?.get("slug")?.asString == "b" }, 10)
            request("b")
            PlatformTestUtil.waitWithEventsDispatching("current result", {
                if (oldFails) service.snapshot().document?.data?.asJsonObject?.get("slug")?.asString == "b"
                else service.snapshot().error?.contains("CURRENT_FAILURE") == true
            }, 10)
            Files.writeString(gate.resolve("release"), "go")
            PlatformTestUtil.waitWithEventsDispatching("old returned", { Files.exists(gate.resolve("returned")) }, 10)
            PlatformTestUtil.waitWithEventsDispatching("callbacks settled", { Files.getLastModifiedTime(gate.resolve("returned")).toMillis() + 500 < System.currentTimeMillis() }, 3)
            if (oldFails) assertNull(service.snapshot().error)
            else assertTrue(service.snapshot().error?.contains("CURRENT_FAILURE") == true)
        } finally { cleanup(root); gate.toFile().deleteRecursively() }
    }

    fun testCurrentAndOtherItemPathsRefreshOnlyRequiredViews() {
        assertEquals(setOf("items", "item"), classifyWorkItemChange("/kit", "/kit/.workspace/items/demo/state.json", "demo"))
        assertEquals(setOf("items", "item"), classifyWorkItemChange("/kit", "/kit/.workspace/items/demo/plan.md", "demo"))
        assertEquals(setOf("items"), classifyWorkItemChange("/kit", "/kit/.workspace/items/other/state.json", "demo"))
        assertEquals(setOf("items", "item"), classifyWorkItemChange("/kit", "/kit/docs/development/items/demo/change.md", "demo"))
        assertEquals(emptySet<String>(), classifyWorkItemChange("/kit", "/kit-other/file.md", "demo"))
    }
}

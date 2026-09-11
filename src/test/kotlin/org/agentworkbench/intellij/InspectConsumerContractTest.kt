package org.agentworkbench.intellij

import com.google.gson.JsonParser
import org.agentworkbench.intellij.kit.InspectProtocol
import org.agentworkbench.intellij.kit.HandoffData
import org.agentworkbench.intellij.kit.SearchData
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不可信的子进程输出必须通过类型、身份和资源形状检查。 */
class InspectConsumerContractTest {
    private val valid = """{
      "apiVersion":{"major":1,"minor":0},"operation":"workspace","status":"ok",
      "root":"/kit","revision":"sha256:${"a".repeat(64)}","observedAt":"2026-09-08T10:00:00Z",
      "data":{"mode":"maintenance","identity":{"name":"Kit"},"repositories":[],
        "localContext":{"activeFeature":null,"branchOwner":null,"primaryRole":null,"sources":{}},
        "configuration":{},"protocol":{"apiVersion":{"major":1,"minor":0},
          "kitVersion":"1.0","operations":["workspace","features"],"limits":{}}},
      "diagnostics":[]
    }"""

    @Test
    fun acceptsCompatibleMinorAndUnknownOptionalFields() {
        val json = JsonParser.parseString(valid).asJsonObject
        json.getAsJsonObject("apiVersion").addProperty("minor", 5)
        json.addProperty("futureOptionalField", "allowed")
        assertTrue(InspectProtocol.parse(json.toString(), "workspace", "/kit").isSuccess)
    }

    @Test
    fun rejectsSuccessfulResponsesWithoutIdentityOrObservation() {
        val invalid = listOf(
            valid.replace("\"root\":\"/kit\"", "\"root\":null"),
            valid.replace("\"observedAt\":\"2026-09-08T10:00:00Z\",", ""),
            valid.replace("\"major\":1", "\"major\":\"1\""),
            valid.replace("\"minor\":0", "\"minor\":null"),
            valid.replace("\"revision\":\"sha256:${"a".repeat(64)}\"", "\"revision\":null"),
        )
        invalid.forEachIndexed { index, text ->
            assertTrue("Invalid identity case $index was accepted", InspectProtocol.parse(text, "workspace", "/kit").isFailure)
        }
    }

    @Test
    fun rejectsMalformedPayloadAndDiagnosticsInsteadOfShowingAnEmptyWorkspace() {
        val mutations: List<(com.google.gson.JsonObject) -> Unit> = listOf(
            { it.add("data", com.google.gson.JsonNull.INSTANCE) },
            { it.getAsJsonObject("data").addProperty("repositories", "broken") },
            { it.getAsJsonObject("data").remove("mode") },
            { it.addProperty("diagnostics", "broken") },
            { it.remove("diagnostics") },
        )
        mutations.forEachIndexed { index, mutate ->
            val json = JsonParser.parseString(valid).asJsonObject
            mutate(json)
            assertTrue("Malformed payload case $index was accepted", InspectProtocol.parse(json.toString(), "workspace", "/kit").isFailure)
        }
    }

    @Test
    fun parsesHandoffAndSearchIntoSmallTypedModels() {
        val handoff = javaClass.getResourceAsStream("/inspect-v1/handoff.json")!!.use {
            JsonParser.parseString(it.readBytes().decodeToString()).asJsonObject
        }
        val handoffResponse = InspectProtocol.parse(handoff.toString(), "handoff", "/synthetic/kit").getOrThrow()
        assertTrue(HandoffData.parse(handoffResponse.data).content.isNotBlank())
        assertTrue(HandoffData.parse(handoffResponse.data).sources.isNotEmpty())

        val search = javaClass.getResourceAsStream("/inspect-v1/search.json")!!.use {
            JsonParser.parseString(it.readBytes().decodeToString()).asJsonObject
        }
        val searchResponse = InspectProtocol.parse(search.toString(), "search", "/synthetic/kit").getOrThrow()
        assertTrue(SearchData.parse(searchResponse.data).query.isNotBlank())
        assertTrue(!SearchData.parse(searchResponse.data).incomplete)
    }
}

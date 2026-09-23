package org.agentworkbench.intellij

import org.agentworkbench.intellij.kit.InspectProtocol
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectClientTest {
    @Test
    fun rejectsOperationVersionAndRootMismatches() {
        val valid = """{"apiVersion":{"major":1,"minor":0},"operation":"workspace","status":"ok","observedAt":"2026-09-08T10:00:00Z","root":"/kit","revision":"sha256:${"a".repeat(64)}","data":{"mode":"maintenance","identity":{},"repositories":[],"localContext":{},"configuration":{},"protocol":{}},"diagnostics":[]}"""
        assertTrue(InspectProtocol.parse(valid, "workspace", "/kit").isSuccess)
        assertTrue(InspectProtocol.parse(valid.replace("\"workspace\"", "\"features\""), "workspace", "/kit").isFailure)
        assertTrue(InspectProtocol.parse(valid.replace("\"major\":1", "\"major\":2"), "workspace", "/kit").isFailure)
        assertTrue(InspectProtocol.parse(valid.replace("\"/kit\"", "\"/other\""), "workspace", "/kit").isFailure)
    }

    @Test
    fun projectionRequiresViewSpecificPayloadAndPreservesPartialStatus() {
        fun envelope(data: String, status: String = "ok") = """{"apiVersion":{"major":1,"minor":0},"operation":"projection","status":"$status","observedAt":"2026-09-08T10:00:00Z","root":"/kit","revision":"rev","data":$data,"diagnostics":[]}"""
        val task = """{"view":"task","featureRevision":"rev","summary":{},"tasks":[],"progression":{}}"""
        assertTrue(InspectProtocol.parse(envelope(task), "projection", "/kit").isSuccess)
        assertTrue(InspectProtocol.parse(envelope(task, "partial"), "projection", "/kit").getOrThrow().status == "partial")
        assertTrue(InspectProtocol.parse(envelope("""{"view":"task","featureRevision":"rev","summary":{}}"""), "projection", "/kit").isFailure)
        assertTrue(InspectProtocol.parse(envelope("""{"view":"change","featureRevision":"rev","repositories":[],"comparison":{}}"""), "projection", "/kit").isSuccess)
        assertTrue(InspectProtocol.parse(envelope("""{"view":"flow","featureRevision":"rev","status":"development","workflow":{},"delivery":{}}"""), "projection", "/kit").isSuccess)
    }
}

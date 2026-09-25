package org.agentworkbench.intellij

import org.agentworkbench.intellij.kit.KitClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals

class KitClientErrorTest {
    @Test fun processFailureAndMalformedSuccessRemainDistinctWithoutEchoingStderr() {
        val root = Files.createTempDirectory("workbench-invalid-output-")
        try {
            Files.createDirectories(root.resolve("scripts"))
            val entry = root.resolve("scripts/kit.py")
            Files.writeString(entry, "import sys; sys.stderr.write('synthetic-secret-do-not-echo'); sys.exit(1)")
            val failure = KitClient(Path.of("/usr/bin/python3"), root).inspect("workspace").exceptionOrNull()
            assertTrue(failure?.message, failure?.message?.contains("KIT_PROCESS_FAILED") == true)
            assertFalse(failure?.message?.contains("synthetic-secret") == true)
            Files.writeString(entry, "print('unexpected output')")
            val malformed = KitClient(Path.of("/usr/bin/python3"), root).inspect("workspace").exceptionOrNull()
            assertTrue(malformed?.message, malformed?.message?.contains("KIT_INVALID_RESPONSE") == true)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun structuredKitFailureIsShownWithoutOtherStderr() {
        val root = Files.createTempDirectory("workbench-structured-error-")
        try {
            Files.createDirectories(root.resolve("scripts"))
            Files.writeString(root.resolve("scripts/kit.py"), "import sys; sys.stderr.write('KIT_PYTHON_UNSUPPORTED: 需要 Python >= 3.10\\nsecret=value'); sys.exit(2)")
            val failure = KitClient(Path.of("/usr/bin/python3"), root).inspect("workspace").exceptionOrNull()
            assertTrue(failure?.message, failure?.message?.contains("KIT_PYTHON_UNSUPPORTED") == true)
            assertFalse(failure?.message?.contains("secret=value") == true)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun errorEnvelopeIsAReadFailure() {
        val root = Files.createTempDirectory("workbench-error-")
        Files.createDirectories(root.resolve("scripts"))
        Files.writeString(root.resolve("scripts/kit.py"), """import json; print(json.dumps({'apiVersion':{'major':2,'minor':0},'operation':'workspace','status':'error','observedAt':'2026-09-08T10:00:00Z','root':None,'revision':None,'data':None,'diagnostics':[{'code':'INSPECT_NOT_FOUND','message':'missing'}]}))""")
        val result = KitClient(java.nio.file.Path.of("/usr/bin/python3"), root).inspect("workspace")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("INSPECT_NOT_FOUND") == true)
    }

    @Test fun completeWorkItemUsesTheFixedDoneTransition() {
        val root = Files.createTempDirectory("workbench-complete-item-")
        try {
            Files.createDirectories(root.resolve("scripts"))
            Files.writeString(root.resolve("scripts/kit.py"), """
                import json, pathlib, sys
                pathlib.Path('arguments.json').write_text(json.dumps(sys.argv[1:]))
                print('updated')
            """.trimIndent())
            val result = KitClient(Path.of("/usr/bin/python3"), root).completeWorkItem("demo-item", "sha256:fixture")
            assertTrue(result.isSuccess)
            assertEquals(
                listOf("item", "complete", "demo-item", "--state-revision", "sha256:fixture"),
                com.google.gson.JsonParser.parseString(Files.readString(root.resolve("arguments.json"))).asJsonArray.map { it.asString },
            )
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun completeWorkItemDoesNotTreatWorkItemErrorsAsSuccess() {
        val root = Files.createTempDirectory("workbench-complete-item-error-")
        try {
            Files.createDirectories(root.resolve("scripts"))
            Files.writeString(root.resolve("scripts/kit.py"), "import sys; print('invalid item'); sys.exit(1)")
            assertTrue(KitClient(Path.of("/usr/bin/python3"), root).completeWorkItem("demo-item", "sha256:fixture").isFailure)
        } finally { root.toFile().deleteRecursively() }
    }
}

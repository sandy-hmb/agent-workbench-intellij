package org.agentworkbench.intellij

import org.agentworkbench.intellij.ui.DocumentView
import org.junit.Assert.assertFalse
import org.junit.Test

class DocumentResourceTest {
    @Test
    fun markdownImagesNeverTriggerAutomaticResourceLoading() {
        val markdown = """
            ![remote](https://example.invalid/tracker.png)
            ![local](file:///private/example.png)
            ![relative](../../outside.png)
            <img src="https://example.invalid/raw.png">
        """.trimIndent()
        val html = DocumentView.render(markdown)
        assertFalse("Swing must not load image resources while reading a document", html.contains("<img", ignoreCase = true))
    }
}

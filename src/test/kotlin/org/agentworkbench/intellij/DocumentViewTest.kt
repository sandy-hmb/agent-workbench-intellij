package org.agentworkbench.intellij

import org.agentworkbench.intellij.ui.DocumentView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentViewTest {
    @Test fun escapesHtmlAndUnsafeLinks() {
        val html = DocumentView.render("<script>alert(1)</script> [bad](javascript:alert(1))\n\n- [x] task")
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("javascript:alert"))
        assertTrue(html.contains("task"))
    }
}

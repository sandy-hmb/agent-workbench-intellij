package org.agentworkbench.intellij.ui

import com.google.gson.JsonObject
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.agentworkbench.intellij.WorkbenchService

internal data class VerificationIssue(val line: Int, val message: String, val level: String)

/**
 * 从 inspect verification 载荷中提取属于 [relativePath]（相对 Kit 根）的验证记录问题。
 * selectedBatch.issues[].path 相对于需求目录（如 testing/verification.md），因此要求
 * relativePath 位于当前需求目录（含 slug 段）内，避免误标其他需求的同名文件。
 */
internal fun verificationIssuesFor(relativePath: String, verification: JsonObject): List<VerificationIssue> {
    val slug = verification.str("slug") ?: return emptyList()
    if (!relativePath.contains("/$slug/")) return emptyList()
    val issues = verification.obj("selectedBatch")?.objects("issues") ?: return emptyList()
    return issues.mapNotNull { issueObj ->
        val issuePath = issueObj.str("path") ?: return@mapNotNull null
        if (!relativePath.endsWith("/$issuePath")) return@mapNotNull null
        val line = issueObj.get("line")?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull() ?: 1
        val message = issueObj.str("message") ?: "验证记录存在问题"
        val code = issueObj.str("code")
        VerificationIssue(line, if (code != null) "[$code] $message" else message, "WARNING")
    }
}

internal class AgentVerificationAnnotator : ExternalAnnotator<PsiFile, List<VerificationIssue>>() {
    override fun collectInformation(file: PsiFile): PsiFile = file

    override fun doAnnotate(file: PsiFile?): List<VerificationIssue>? {
        if (file == null || file.virtualFile == null) return null
        val project = file.project
        val service = WorkbenchService.getInstance(project)
        val snapshot = service.snapshot()

        // Ensure kitRoot is valid and the file resides within it
        val root = snapshot.kitRoot ?: return null
        val filePath = file.virtualFile.path
        if (!filePath.startsWith(root)) return null

        val relativePath = filePath.removePrefix(root).removePrefix("/")
        val verification = snapshot.verification?.data.obj() ?: return null
        return verificationIssuesFor(relativePath, verification).ifEmpty { null }
    }

    override fun apply(file: PsiFile, annotationResult: List<VerificationIssue>?, holder: AnnotationHolder) {
        if (annotationResult == null || annotationResult.isEmpty()) return
        val document = com.intellij.psi.PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        
        for (issue in annotationResult) {
            val severity = if (issue.level.equals("WARNING", true)) HighlightSeverity.WARNING else HighlightSeverity.ERROR
            // Convert to 0-indexed line number safely
            val lineIndex = maxOf(0, issue.line - 1)
            
            if (lineIndex < document.lineCount) {
                val startOffset = document.getLineStartOffset(lineIndex)
                val endOffset = document.getLineEndOffset(lineIndex)
                val range = TextRange(startOffset, maxOf(startOffset, endOffset))
                
                holder.newAnnotation(severity, "[Workbench] ${issue.message}")
                    .range(range)
                    .tooltip("Agent Workbench 验证记录问题：<br/>${issue.message}")
                    .create()
            }
        }
    }
}

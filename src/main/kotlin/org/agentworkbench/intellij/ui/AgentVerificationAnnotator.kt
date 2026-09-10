package org.agentworkbench.intellij.ui

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.agentworkbench.intellij.WorkbenchService

internal data class VerificationIssue(val line: Int, val message: String, val level: String)

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
        
        // Convert to relative path matching the agent's schema e.g., "src/main/..."
        val relativePath = filePath.removePrefix(root).removePrefix("/")
        
        val verificationData = snapshot.verification?.data?.asJsonObject ?: return null
        val summary = verificationData.getAsJsonObject("summary") ?: return null
        val issuesArray = summary.getAsJsonArray("issues") ?: return null
        
        val result = mutableListOf<VerificationIssue>()
        for (item in issuesArray) {
            val issueObj = item.asJsonObject
            val issuePath = issueObj.get("path")?.asString
            if (issuePath == relativePath) {
                // Safely get line (1-indexed based on schema, fall back to 1)
                val lineStr = issueObj.get("line")?.asString?.toIntOrNull() ?: 1
                val message = issueObj.get("message")?.asString ?: "Unknown Verification Failure"
                val level = issueObj.get("level")?.asString ?: "ERROR"
                result.add(VerificationIssue(lineStr, message, level))
            }
        }
        return result
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
                
                holder.newAnnotation(severity, "[Workbench Agent] ${issue.message}")
                    .range(range)
                    .tooltip("Agent Workbench 自动代码验证拦截：<br/>${issue.message}")
                    .create()
            }
        }
    }
}

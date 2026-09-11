package org.agentworkbench.intellij.kit

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile

internal class KitClient(private val python: Path, private val kitRoot: Path) {
    fun inspect(operation: String, arguments: List<String> = emptyList()): Result<InspectResponse> = runCatching {
        require(operation in OPERATIONS) { "不支持的 Inspect operation" }
        val root = kitRoot.toRealPath()
        val entry = root.resolve("scripts/kit.py")
        require(entry.isRegularFile()) { "未找到 Kit 入口" }
        val command = GeneralCommandLine(python.absolutePathString()).apply {
            withWorkDirectory(root.toFile())
            addParameters("-B", entry.toString(), "inspect", "--root", root.toString(), "--api-major", "1", "--json", operation, *arguments.toTypedArray())
        }
        val process = command.createProcess()
        val stdout = STREAM_POOL.submit<String> { readBounded(process.inputStream, MAX_STDOUT, process) }
        val stderr = STREAM_POOL.submit<String> { readBounded(process.errorStream, MAX_STDERR, process) }
        try {
            val timeout = if (operation == "verification" && arguments.contains("--check-code")) VERIFY_TIMEOUT_MILLIS else TIMEOUT_MILLIS
            var waited = 0
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted) error("Inspect 查询已取消")
                waited += 200
                if (waited >= timeout) error("Inspect 查询超时")
            }
            val text = stdout.get(1, TimeUnit.SECONDS)
            val processError = stderr.get(1, TimeUnit.SECONDS)
            val exitCode = process.exitValue()
            if (exitCode == 2 && (text.lineSequence() + processError.lineSequence()).any {
                    it.trim() == "未知子命令：inspect" || it.trim().startsWith("kit.py: error: argument") && it.contains("invalid choice: 'inspect'")
                }) {
                throw InspectReadException(listOf(InspectDiagnostic("KIT_INSPECT_UNAVAILABLE",
                    "当前绑定的 Kit 尚不支持工作台查询，请升级此工作流仓的 Inspect 配套脚本后重新绑定。Kit：$root")))
            }
            val response = InspectProtocol.parse(text, operation, root.toString()).getOrElse { failure ->
                LOG.warn("Inspect $operation 响应不可解析（退出码 $exitCode）：${failure.message}；stderr=${processError.take(2000)}")
                val diagnostic = if (exitCode != 0) InspectDiagnostic("KIT_PROCESS_FAILED",
                    "Kit 查询进程失败（退出码 $exitCode），请检查所绑定的 Kit 与 Python 解释器。")
                else InspectDiagnostic("KIT_INVALID_RESPONSE",
                    "Kit 没有返回兼容的工作台查询结果，请确认绑定目录及 Inspect 版本。")
                throw InspectReadException(listOf(diagnostic))
            }
            if (response.status == "error") throw InspectReadException(response.diagnostics)
            if (exitCode != 0) {
                LOG.warn("Inspect $operation 进程退出码 $exitCode；stderr=${processError.take(2000)}")
                throw InspectReadException(listOf(InspectDiagnostic("KIT_PROCESS_FAILED", "Kit 查询进程失败（退出码 $exitCode）。")))
            }
            response
        } finally {
            if (process.isAlive) process.destroyForcibly()
            stdout.cancel(true)
            stderr.cancel(true)
        }
    }

    /** 除 inspect 外的只读辅助命令（doctor/describe），返回原始 stdout；doctor 有 ERROR 时退出码为 1，仍属正常输出。 */
    fun tool(subcommand: String, arguments: List<String> = emptyList()): Result<String> = runCatching {
        require(subcommand in TOOLS) { "不支持的 Kit 命令" }
        val root = kitRoot.toRealPath()
        val entry = root.resolve("scripts/kit.py")
        require(entry.isRegularFile()) { "未找到 Kit 入口" }
        val command = GeneralCommandLine(python.absolutePathString()).apply {
            withWorkDirectory(root.toFile())
            addParameters("-B", entry.toString(), subcommand, *arguments.toTypedArray())
        }
        val process = command.createProcess()
        val stdout = STREAM_POOL.submit<String> { readBounded(process.inputStream, MAX_STDOUT, process) }
        val stderr = STREAM_POOL.submit<String> { readBounded(process.errorStream, MAX_STDERR, process) }
        try {
            var waited = 0
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted) error("Kit 命令已取消")
                waited += 200
                if (waited >= TIMEOUT_MILLIS) error("Kit 命令超时")
            }
            val text = stdout.get(10, TimeUnit.SECONDS)
            val processError = stderr.get(10, TimeUnit.SECONDS)
            if (process.exitValue() !in 0..1 || text.isBlank()) {
                LOG.warn("kit $subcommand 退出码 ${process.exitValue()}；stderr=${processError.take(2000)}")
                val reason = processError.trim().take(300).ifBlank { "退出码 ${process.exitValue()}" }
                error("Kit 命令失败：$reason")
            }
            text
        } finally {
            if (process.isAlive) process.destroyForcibly()
            stdout.cancel(true)
            stderr.cancel(true)
        }
    }

    private companion object {
        val LOG = Logger.getInstance(KitClient::class.java)
        val OPERATIONS = setOf("workspace", "features", "feature", "document", "verification", "handoff", "search", "workflow", "runs", "run")
        val TOOLS = setOf("doctor", "describe", "brief")
        const val TIMEOUT_MILLIS = 12_000
        const val VERIFY_TIMEOUT_MILLIS = 32_000
        const val MAX_STDOUT = 8 * 1024 * 1024
        const val MAX_STDERR = 64 * 1024

        /** 所有 Kit 子进程共享的流读取线程池；每次调用新建线程池会造成大量一次性线程。 */
        val STREAM_POOL: java.util.concurrent.ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "agent-workbench-kit-io").apply { isDaemon = true }
        }

        fun readBounded(stream: InputStream, limit: Int, process: Process): String {
            stream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) return bytes.toString(Charsets.UTF_8)
                    if (bytes.size() + count > limit) {
                        process.destroyForcibly()
                        error("Inspect 输出超过限额")
                    }
                    bytes.write(buffer, 0, count)
                }
            }
        }
    }
}

internal class InspectReadException(diagnostics: List<InspectDiagnostic>) : IllegalStateException(
    diagnostics.joinToString("; ") { "${it.code}: ${it.message}" }.ifBlank { "Inspect 查询失败" },
)

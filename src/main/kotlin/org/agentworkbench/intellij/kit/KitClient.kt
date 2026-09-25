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
            addParameters("-B", entry.toString(), "inspect", "--root", root.toString(), "--api-major", "2", "--json", operation, *arguments.toTypedArray())
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
            val response = InspectProtocol.parse(text, operation, root.toString()).getOrElse { failure ->
                LOG.warn("Inspect $operation 响应不可解析（退出码 $exitCode）：${failure.message}；stderr=${processError.take(2000)}")
                val diagnostic = if (exitCode != 0) InspectDiagnostic("KIT_PROCESS_FAILED",
                    processFailureMessage(exitCode, processError))
                else InspectDiagnostic("KIT_INVALID_RESPONSE",
                    "Kit 没有返回兼容的工作台查询结果，请确认绑定目录及 Inspect 版本。")
                throw InspectReadException(listOf(diagnostic))
            }
            if (response.status == "error") throw InspectReadException(response.diagnostics)
            if (exitCode != 0) {
                LOG.warn("Inspect $operation 进程退出码 $exitCode；stderr=${processError.take(2000)}")
                throw InspectReadException(listOf(InspectDiagnostic("KIT_PROCESS_FAILED", processFailureMessage(exitCode, processError))))
            }
            response
        } finally {
            if (process.isAlive) process.destroyForcibly()
            stdout.cancel(true)
            stderr.cancel(true)
        }
    }

    /** 除 inspect 外的受控 Kit 命令，返回原始 stdout；doctor 有 ERROR 时退出码为 1，仍属正常输出。 */
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
            val exitCode = process.exitValue()
            if ((exitCode != 0 && !(subcommand == "doctor" && exitCode == 1)) || text.isBlank()) {
                LOG.warn("kit $subcommand 退出码 ${process.exitValue()}；stderr=${processError.take(2000)}")
                val reason = processError.trim().take(300).ifBlank { "退出码 $exitCode" }
                error("Kit 命令失败：$reason")
            }
            text
        } finally {
            if (process.isAlive) process.destroyForcibly()
            stdout.cancel(true)
            stderr.cancel(true)
        }
    }

    fun completeWorkItem(slug: String, revision: String): Result<Unit> =
        tool("item", listOf("complete", slug, "--state-revision", revision)).map { }

    companion object {
        val LOG = Logger.getInstance(KitClient::class.java)
        val OPERATIONS = setOf("workspace", "items", "projection", "document", "artifacts", "verification", "handoff", "search", "workflow", "runs", "run")
        val TOOLS = setOf("doctor", "describe", "brief", "item")
        const val TIMEOUT_MILLIS = 12_000
        const val VERIFY_TIMEOUT_MILLIS = 32_000
        const val MAX_STDOUT = 8 * 1024 * 1024
        const val MAX_STDERR = 64 * 1024

        internal fun validateInterpreter(python: Path): Result<Unit> = runCatching {
            val process = ProcessBuilder(
                python.absolutePathString(), "-c",
                "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}')",
            ).redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("KIT_PYTHON_CHECK_TIMEOUT: Python 版本检查超时")
            }
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            require(process.exitValue() == 0) { "KIT_PYTHON_CHECK_FAILED: Python 版本检查失败" }
            val parts = output.split('.').mapNotNull(String::toIntOrNull)
            require(parts.size == 3) { "KIT_PYTHON_CHECK_FAILED: Python 未返回可识别版本" }
            require(parts[0] > 3 || parts[0] == 3 && parts[1] >= 10) {
                "KIT_PYTHON_UNSUPPORTED: 需要 Python >= 3.10，当前为 Python $output"
            }
        }

        private fun processFailureMessage(exitCode: Int, stderr: String): String {
            val structured = stderr.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("KIT_") }
                ?.take(300)
            return "Kit 查询进程失败（退出码 $exitCode）。" +
                (structured?.let { " $it" } ?: " 请检查所绑定的 Kit 与 Python 解释器。")
        }

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

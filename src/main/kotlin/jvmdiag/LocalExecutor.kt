package jvmdiag

import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 本机执行。ProcessBuilder 直接传 argv,无 shell 解释。每路输出最多捕获 maxCaptureBytes,超出部分丢弃。 */
class LocalExecutor(private val maxCaptureBytes: Int = 1_000_000) : Executor {

    override fun run(command: List<String>, timeoutMs: Long): ExecResult {
        val process = ProcessBuilder(command).start()
        process.outputStream.close()
        val out = CompletableFuture.supplyAsync { capture(process.inputStream) }
        val err = CompletableFuture.supplyAsync { capture(process.errorStream) }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly().waitFor()
        return ExecResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = out.get(),
            stderr = err.get(),
            timedOut = !finished,
        )
    }

    private fun capture(stream: InputStream): String {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        stream.use {
            while (true) {
                val n = it.read(chunk)
                if (n < 0) break
                val room = maxCaptureBytes - buf.size()
                if (room > 0) buf.write(chunk, 0, minOf(n, room))
            }
        }
        return buf.toString(Charsets.UTF_8)
    }
}

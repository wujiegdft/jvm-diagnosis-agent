package jvmdiag

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

enum class Approval { AUTO, APPROVED, DENIED }

/** 一次工具调用的审计记录。被拒绝/校验失败的调用同样记录。 */
data class TraceEntry(
    val tsMs: Long,
    val tool: String,
    val args: Map<String, Any?>,
    val risk: Risk?,
    val approval: Approval,
    val ok: Boolean,
    val summary: String,
    val durationMs: Long,
    val truncated: Boolean,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "ts" to java.time.Instant.ofEpochMilli(tsMs).toString(),
        "tool" to tool,
        "args" to args,
        "risk" to risk?.name,
        "approval" to approval.name,
        "ok" to ok,
        "summary" to summary,
        "durationMs" to durationMs,
        "truncated" to truncated,
    )
}

fun interface Trace {
    fun record(entry: TraceEntry)
}

/** 追加写 JSONL,每行一次调用。 */
class JsonlTrace(private val file: Path) : Trace {
    private val mapper = ObjectMapper()

    @Synchronized
    override fun record(entry: TraceEntry) {
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            file,
            mapper.writeValueAsString(entry.toMap()) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }
}

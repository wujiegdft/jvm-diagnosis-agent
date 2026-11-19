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

/** 一次诊断会话的汇总记录,供评估统计步数/token/耗时/终止原因。 */
class JsonlSessionTrace(private val file: Path) {
    private val mapper = ObjectMapper()

    @Synchronized
    fun record(prompt: String, model: String, result: AgentResult, tsMs: Long = System.currentTimeMillis()) {
        file.parent?.let { Files.createDirectories(it) }
        val row = mapOf(
            "ts" to java.time.Instant.ofEpochMilli(tsMs).toString(),
            "model" to model,
            "prompt" to prompt,
            "stopReason" to result.stopReason.name,
            "steps" to result.steps,
            "toolCalls" to result.toolCalls,
            "duplicateBlocked" to result.duplicateBlocked,
            "promptTokens" to result.promptTokens,
            "completionTokens" to result.completionTokens,
            "reasoningTokens" to result.reasoningTokens,
            "totalTokens" to result.totalTokens,
            "durationMs" to result.durationMs,
            "answerSummary" to result.answer.take(300),
        )
        Files.writeString(
            file,
            mapper.writeValueAsString(row) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }
}

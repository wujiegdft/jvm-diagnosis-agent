package jvmdiag

data class WorkflowResult(
    val answer: String,
    val ok: Boolean,
    val toolCalls: Int,
    val l2Triggered: Boolean,
    val promptTokens: Int,
    val completionTokens: Int,
    val reasoningTokens: Int,
    val durationMs: Long,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

private val WORKFLOW_SYSTEM_PROMPT = """
    你是资深 JVM 故障诊断专家。下面是一次固定流水线采集到的全部 JVM 诊断数据。
    请基于这些数据输出结构化诊断报告:
    - **根因**:一句话说清本质原因
    - **关键证据**:列出支撑结论的原始线索(线程名、锁地址、堆栈、CPU/nid、GC 指标、泄漏对象类等)
    - **修复建议**:给出可落地的排查或代码修复方案
""".trimIndent()

/**
 * 固定 Workflow 对照基线:
 * 不管症状是什么,按固定顺序依次取证,拼接后一次性交给 LLM 分析(1 次 LLM 调用)。
 * 用于与 AgentLoop 对比:准确率、步数、token、耗时、以及是否误触 L1/L2 重操作。
 */
class WorkflowBaseline(
    private val llm: LlmClient,
    private val tools: DiagnosticTools,
    private val includeL2: Boolean = true,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun run(pid: Int, symptom: String): WorkflowResult {
        val startMs = nowMs()
        val steps = buildList<Pair<String, Map<String, Any>>> {
            add("top_threads" to mapOf("pid" to pid, "top_n" to 10))
            add("jstat_gc" to mapOf("pid" to pid, "interval_ms" to 1000, "samples" to 3))
            add("jcmd_vm_flags" to mapOf("pid" to pid))
            add("jstack" to mapOf("pid" to pid))
            if (includeL2) add("heap_histo" to mapOf("pid" to pid, "top_n" to 20))
        }

        val evidence = StringBuilder()
        evidence.appendLine("问题描述: $symptom (PID=$pid)")
        var l2Triggered = false
        for ((name, args) in steps) {
            if (name == "heap_histo") l2Triggered = true
            val r = tools.call(name, args)
            evidence.appendLine("\n=== 工具 $name($args) [${if (r.ok) "OK" else "ERR"}] ===")
            evidence.appendLine(r.output)
        }

        val resp = try {
            llm.chat(
                LlmRequest(
                    messages = listOf(
                        SystemMessage(WORKFLOW_SYSTEM_PROMPT),
                        UserMessage(evidence.toString()),
                    ),
                    tools = emptyList(),
                )
            )
        } catch (e: LlmException) {
            return WorkflowResult(
                answer = "模型调用失败: ${e.message}",
                ok = false,
                toolCalls = steps.size,
                l2Triggered = l2Triggered,
                promptTokens = 0,
                completionTokens = 0,
                reasoningTokens = 0,
                durationMs = nowMs() - startMs,
            )
        }

        val content = resp.message.content.orEmpty()
        return WorkflowResult(
            answer = content.ifBlank { "模型返回空内容(finish_reason=${resp.finishReason})" },
            ok = content.isNotBlank(),
            toolCalls = steps.size,
            l2Triggered = l2Triggered,
            promptTokens = resp.usage.promptTokens,
            completionTokens = resp.usage.completionTokens,
            reasoningTokens = resp.usage.reasoningTokens,
            durationMs = nowMs() - startMs,
        )
    }
}

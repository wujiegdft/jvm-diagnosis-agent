package jvmdiag

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature

data class AgentBudget(
    val maxSteps: Int = 10,
    val maxTotalTokens: Int = 50_000,
    val maxDurationMs: Long = 120_000,
    /** 同名且同参数的工具在单次会话中最多实际执行几次,超出后拦截以防模型死循环。 */
    val maxDuplicateCalls: Int = 2,
)

enum class StopReason {
    COMPLETED,
    MAX_STEPS,
    MAX_TOKENS,
    MAX_DURATION,
    OUTPUT_TRUNCATED,
    EMPTY_RESPONSE,
    LLM_ERROR,
}

data class AgentResult(
    val answer: String,
    val stopReason: StopReason,
    val steps: Int,
    val toolCalls: Int,
    val duplicateBlocked: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val reasoningTokens: Int,
    val durationMs: Long,
    val transcript: List<Message>,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

sealed interface AgentEvent {
    data class LlmStep(
        val step: Int,
        val finishReason: String,
        val toolCallCount: Int,
        val usage: Usage,
        val reasoningPreview: String?,
    ) : AgentEvent

    data class ToolExecuted(
        val step: Int,
        val tool: String,
        val args: Map<String, Any?>,
        val ok: Boolean,
        val outputPreview: String,
    ) : AgentEvent

    data class DuplicateBlocked(
        val step: Int,
        val tool: String,
        val args: Map<String, Any?>,
        val previousCount: Int,
    ) : AgentEvent
}

val DEFAULT_SYSTEM_PROMPT = """
    你是资深 JVM 故障诊断专家。你会通过只读/受控诊断工具逐步收集证据,定位 JVM 故障根因。

    规则:
    1. 不猜测未经工具证实的细节,所有结论必须有具体证据(线程名、锁地址、堆栈、CPU/nid、GC 指标等)。
    2. 优先使用低开销 L0 工具(top_threads、jstat_gc、jcmd_vm_flags、gc_log_tail),按需调用 L1(jstack)。不要猜测用户未提供的文件路径(如 GC 日志路径)。
    3. L2 工具(如 heap_histo)的安全边界由人工审批钩子把关:不要因为它是 L2 就回避调用——当 L0 证据(如 jstat_gc 老年代持续上涨)指向堆内存泄漏且需要定位具体泄漏对象类型时,应主动发起 heap_histo 调用申请;若被人工拒绝再改用其他手段。
    4. 遇到工具报错(参数错误、限频、人工拒绝)时,认真阅读报错信息,修正参数或改用其他工具。
    5. 不要重复调用完全相同的工具和参数。拿到充分证据后立即停止调用工具,输出结构化诊断报告:
       - **根因**:一句话说清本质原因
       - **关键证据**:列出支撑结论的原始线索
       - **修复建议**:给出可落地的排查或代码修复方案
""".trimIndent()

/**
 * 最小 Agent Harness 主循环:
 * UserPrompt → LLM → [tool_calls → 参数解析/重复检测/DiagnosticTools.call → ToolMessage]* → 最终结论。
 * 所有预算(步数/token/耗时/重复调用)均在此处强制执行,不信任模型自发收敛。
 */
class AgentLoop(
    private val llm: LlmClient,
    private val tools: DiagnosticTools,
    private val budget: AgentBudget = AgentBudget(),
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onEvent: (AgentEvent) -> Unit = {},
) {
    private val mapper = ObjectMapper().apply {
        configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
    }

    fun run(userPrompt: String): AgentResult {
        val startMs = nowMs()
        val messages = mutableListOf<Message>(
            SystemMessage(systemPrompt),
            UserMessage(userPrompt),
        )
        val specs = tools.specs()
        val callCounts = mutableMapOf<String, Int>()

        var steps = 0
        var toolCalls = 0
        var duplicateBlocked = 0
        var promptTokens = 0
        var completionTokens = 0
        var reasoningTokens = 0

        fun finish(reason: StopReason, answer: String) = AgentResult(
            answer = answer,
            stopReason = reason,
            steps = steps,
            toolCalls = toolCalls,
            duplicateBlocked = duplicateBlocked,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            reasoningTokens = reasoningTokens,
            durationMs = nowMs() - startMs,
            transcript = messages.toList(),
        )

        while (true) {
            if (nowMs() - startMs >= budget.maxDurationMs) {
                return finish(StopReason.MAX_DURATION, "超出耗时预算(${budget.maxDurationMs}ms),终止诊断。")
            }
            if (steps >= budget.maxSteps) {
                return finish(StopReason.MAX_STEPS, "超出最大步数(${budget.maxSteps}),终止诊断。")
            }

            steps++
            val resp = try {
                llm.chat(LlmRequest(messages = messages.toList(), tools = specs))
            } catch (e: LlmException) {
                return finish(StopReason.LLM_ERROR, "模型调用失败: ${e.message}")
            }

            promptTokens += resp.usage.promptTokens
            completionTokens += resp.usage.completionTokens
            reasoningTokens += resp.usage.reasoningTokens
            messages += resp.message

            onEvent(
                AgentEvent.LlmStep(
                    step = steps,
                    finishReason = resp.finishReason,
                    toolCallCount = resp.message.toolCalls.size,
                    usage = resp.usage,
                    reasoningPreview = resp.message.reasoning?.replace('\n', ' ')?.take(120),
                )
            )

            if (resp.message.toolCalls.isEmpty()) {
                val content = resp.message.content
                if (!content.isNullOrBlank()) {
                    return finish(StopReason.COMPLETED, content)
                }
                return if (resp.finishReason == "length") {
                    finish(
                        StopReason.OUTPUT_TRUNCATED,
                        "模型输出被截断且无正文(finish_reason=length, reasoningTokens=${resp.usage.reasoningTokens}),请增大 max_tokens。",
                    )
                } else {
                    finish(
                        StopReason.EMPTY_RESPONSE,
                        "模型返回空内容且未调用工具(finish_reason=${resp.finishReason})。",
                    )
                }
            }

            if (promptTokens + completionTokens >= budget.maxTotalTokens) {
                return finish(StopReason.MAX_TOKENS, "超出 token 预算(${budget.maxTotalTokens}),终止诊断。")
            }
            if (nowMs() - startMs >= budget.maxDurationMs) {
                return finish(StopReason.MAX_DURATION, "超出耗时预算(${budget.maxDurationMs}ms),终止诊断。")
            }

            for (tc in resp.message.toolCalls) {
                val parsed = parseToolArgs(tc.rawArguments)
                if (parsed.error != null) {
                    toolCalls++
                    val errText = "ERROR: 参数 JSON 解析失败: ${parsed.error}。请提供合法的 JSON 对象。"
                    messages += ToolMessage(tc.id, tc.name, errText)
                    onEvent(AgentEvent.ToolExecuted(steps, tc.name, emptyMap(), ok = false, outputPreview = errText.take(160)))
                    continue
                }

                val args = parsed.args
                val sig = "${tc.name}:${mapper.writeValueAsString(args)}"
                val prev = callCounts.getOrDefault(sig, 0)
                if (prev >= budget.maxDuplicateCalls) {
                    duplicateBlocked++
                    val msg = "ERROR: 重复调用拦截: ${tc.name} 相同参数已执行 $prev 次。请基于已有输出换用其他工具,或直接给出诊断结论。"
                    messages += ToolMessage(tc.id, tc.name, msg)
                    onEvent(AgentEvent.DuplicateBlocked(steps, tc.name, args, prev))
                    continue
                }

                callCounts[sig] = prev + 1
                toolCalls++
                val result = tools.call(tc.name, args)
                val body = if (result.ok) result.output else "ERROR: ${result.output}"
                messages += ToolMessage(tc.id, tc.name, body)
                onEvent(
                    AgentEvent.ToolExecuted(
                        step = steps,
                        tool = tc.name,
                        args = args,
                        ok = result.ok,
                        outputPreview = body.replace('\n', ' ').take(160),
                    )
                )
            }
        }
    }

    private data class ParsedArgs(val args: Map<String, Any?>, val error: String? = null)

    @Suppress("UNCHECKED_CAST")
    private fun parseToolArgs(raw: String): ParsedArgs {
        if (raw.isBlank()) return ParsedArgs(emptyMap())
        return try {
            val node = mapper.readTree(raw)
            if (!node.isObject) {
                ParsedArgs(emptyMap(), "期望 JSON 对象,收到: ${raw.take(80)}")
            } else {
                ParsedArgs(mapper.convertValue(node, Map::class.java) as Map<String, Any?>)
            }
        } catch (e: Exception) {
            ParsedArgs(emptyMap(), e.message ?: "JSON 格式错误")
        }
    }
}

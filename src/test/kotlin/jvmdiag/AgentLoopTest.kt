package jvmdiag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 假模型客户端:按预设队列依次返回响应,并记录每次收到的请求。 */
class FakeLlmClient(private val scripted: ArrayDeque<() -> LlmResponse>) : LlmClient {
    constructor(vararg responses: LlmResponse) : this(ArrayDeque(responses.map { r -> { r } }))

    val requests = mutableListOf<LlmRequest>()

    override fun chat(request: LlmRequest): LlmResponse {
        requests += request
        return scripted.removeFirst().invoke()
    }
}

class AgentLoopTest {
    private val exec = FakeExecutor()
    private val traceEntries = mutableListOf<TraceEntry>()
    private var now = 1_000_000L
    private var approveAnswer = true

    private fun makeTools() = DiagnosticTools(
        tools = standardTools(listOf("/tmp/jvm-diag/logs")),
        executor = exec,
        approver = { approveAnswer },
        trace = { traceEntries += it },
        limits = Limits(maxOutputChars = 4000, l1MinIntervalMs = 0),
        nowMs = { now },
    )

    private fun toolCallResp(id: String, name: String, rawArgs: String, tokens: Int = 100) = LlmResponse(
        message = AssistantMessage(
            content = null,
            toolCalls = listOf(ToolCall(id, name, rawArgs)),
        ),
        finishReason = "tool_calls",
        usage = Usage(promptTokens = tokens, completionTokens = 20, reasoningTokens = 10),
    )

    private fun textResp(text: String, tokens: Int = 150) = LlmResponse(
        message = AssistantMessage(content = text),
        finishReason = "stop",
        usage = Usage(promptTokens = tokens, completionTokens = 40, reasoningTokens = 15),
    )

    @Test fun `多步自主排查最终产出结论并累计统计`() {
        exec.result = {
            when (exec.commands.last().first()) {
                "top" -> ExecResult(0, "  PID USER PR NI VIRT RES %CPU %MEM COMMAND\n 101 u 20 0 1 1 0.0 1.0 java", "")
                "jstack" -> ExecResult(0, "Found one Java-level deadlock:\n\"transfer-worker\" waits \"audit-worker\"", "")
                else -> ExecResult(0, "ok", "")
            }
        }
        val llm = FakeLlmClient(
            toolCallResp("c1", "top_threads", """{"pid":1234,"top_n":5}""", tokens = 100),
            toolCallResp("c2", "jstack", """{"pid":1234}""", tokens = 200),
            textResp("根因: transfer-worker 与 audit-worker 交叉加锁导致死锁。", tokens = 300),
        )

        val result = AgentLoop(llm, makeTools(), nowMs = { now }).run("PID 1234 卡死")

        assertEquals(StopReason.COMPLETED, result.stopReason)
        assertEquals(3, result.steps)
        assertEquals(2, result.toolCalls)
        assertEquals(0, result.duplicateBlocked)
        assertEquals(600, result.promptTokens)
        assertEquals(80, result.completionTokens)
        assertEquals(35, result.reasoningTokens)
        assertTrue("死锁" in result.answer)
        assertEquals(2, exec.commands.size)
    }

    @Test fun `工具报错与非法 JSON 回传给模型后模型自我纠正`() {
        val llm = FakeLlmClient(
            toolCallResp("c1", "jstack", """{not-json"""),
            toolCallResp("c2", "jstack", """{"pid":"bad"}"""),
            toolCallResp("c3", "jstack", """{"pid":1234}"""),
            textResp("纠正参数后确认正常。"),
        )

        val result = AgentLoop(llm, makeTools(), nowMs = { now }).run("查一下 1234")

        assertEquals(StopReason.COMPLETED, result.stopReason)
        val toolMsgs = result.transcript.filterIsInstance<ToolMessage>()
        assertEquals(3, toolMsgs.size)
        assertTrue("参数 JSON 解析失败" in toolMsgs[0].content)
        assertTrue("参数错误" in toolMsgs[1].content)
        assertEquals("ok", toolMsgs[2].content)
        assertEquals(1, exec.commands.size)
    }

    @Test fun `L2 人工拒绝后明确回传 模型改用其他只读工具`() {
        approveAnswer = false
        val llm = FakeLlmClient(
            toolCallResp("c1", "heap_histo", """{"pid":1234}"""),
            toolCallResp("c2", "jstat_gc", """{"pid":1234}"""),
            textResp("人工拒绝 heap_histo,改用 jstat_gc 完成排查。"),
        )

        val result = AgentLoop(llm, makeTools(), nowMs = { now }).run("查内存")

        assertEquals(StopReason.COMPLETED, result.stopReason)
        val toolMsgs = result.transcript.filterIsInstance<ToolMessage>()
        assertTrue("人工拒绝" in toolMsgs[0].content)
        assertEquals("ok", toolMsgs[1].content)
        assertEquals(listOf("jstat", "-gc", "1234", "1000", "3"), exec.commands.single())
    }

    @Test fun `重复调用拦截忽略 JSON 键顺序并阻止死循环`() {
        val llm = FakeLlmClient(
            toolCallResp("c1", "jstat_gc", """{"pid":1234,"samples":2}"""),
            toolCallResp("c2", "jstat_gc", """{"samples":2,"pid":1234}"""),
            toolCallResp("c3", "jstat_gc", """{"pid":1234,"samples":2}"""),
            textResp("基于已有采样得出结论。"),
        )

        val result = AgentLoop(
            llm,
            makeTools(),
            budget = AgentBudget(maxDuplicateCalls = 2),
            nowMs = { now },
        ).run("查 GC")

        assertEquals(StopReason.COMPLETED, result.stopReason)
        assertEquals(2, exec.commands.size)
        assertEquals(1, result.duplicateBlocked)
        val blockedMsg = result.transcript.filterIsInstance<ToolMessage>()[2]
        assertTrue("重复调用拦截" in blockedMsg.content)
    }

    @Test fun `步数预算上限终止循环`() {
        val llm = FakeLlmClient(
            toolCallResp("c1", "jstat_gc", """{"pid":1}"""),
            toolCallResp("c2", "jstat_gc", """{"pid":2}"""),
            toolCallResp("c3", "jstat_gc", """{"pid":3}"""),
        )
        val result = AgentLoop(llm, makeTools(), budget = AgentBudget(maxSteps = 2), nowMs = { now }).run("test")
        assertEquals(StopReason.MAX_STEPS, result.stopReason)
        assertEquals(2, result.steps)
    }

    @Test fun `token 预算上限终止循环`() {
        val llm = FakeLlmClient(
            toolCallResp("c1", "jstat_gc", """{"pid":1}""", tokens = 400),
            toolCallResp("c2", "jstat_gc", """{"pid":2}""", tokens = 400),
        )
        val result = AgentLoop(llm, makeTools(), budget = AgentBudget(maxTotalTokens = 400), nowMs = { now }).run("test")
        assertEquals(StopReason.MAX_TOKENS, result.stopReason)
        assertEquals(1, result.steps)
        assertEquals(0, exec.commands.size)
    }

    @Test fun `耗时预算上限终止循环`() {
        val llm = FakeLlmClient(ArrayDeque(listOf(
            {
                now += 5000
                toolCallResp("c1", "jstat_gc", """{"pid":1}""")
            }
        )))
        val result = AgentLoop(llm, makeTools(), budget = AgentBudget(maxDurationMs = 3000), nowMs = { now }).run("test")
        assertEquals(StopReason.MAX_DURATION, result.stopReason)
        assertEquals(0, exec.commands.size)
    }

    @Test fun `reasoning 占满 max_tokens 导致正文为空时返回 OUTPUT_TRUNCATED`() {
        val llm = FakeLlmClient(
            LlmResponse(
                message = AssistantMessage(content = "", reasoning = "thinking..."),
                finishReason = "length",
                usage = Usage(promptTokens = 50, completionTokens = 32, reasoningTokens = 32),
            )
        )
        val result = AgentLoop(llm, makeTools(), nowMs = { now }).run("hi")
        assertEquals(StopReason.OUTPUT_TRUNCATED, result.stopReason)
        assertTrue("max_tokens" in result.answer)
    }

    @Test fun `模型异常返回 LLM_ERROR 不崩溃`() {
        val llm = LlmClient { throw LlmException("HTTP 503: upstream down") }
        val result = AgentLoop(llm, makeTools(), nowMs = { now }).run("hi")
        assertEquals(StopReason.LLM_ERROR, result.stopReason)
        assertTrue("HTTP 503" in result.answer)
    }
}

package jvmdiag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvalTest {
    @Test fun `WorkflowBaseline 固定取证一次调模型 includeL2 开关生效`() {
        val exec = FakeExecutor { ExecResult(0, "mock-out", "") }
        val tools = DiagnosticTools(
            tools = standardTools(listOf("/tmp/jvm-diag/logs")),
            executor = exec,
            approver = { true },
            trace = {},
            limits = Limits(l1MinIntervalMs = 0),
        )
        val llm = FakeLlmClient(
            LlmResponse(AssistantMessage("根因: 死锁"), "stop", Usage(500, 100, 20)),
            LlmResponse(AssistantMessage("根因: 未知"), "stop", Usage(400, 80, 10)),
        )

        val r1 = WorkflowBaseline(llm, tools, includeL2 = true).run(1234, "卡死")
        assertTrue(r1.ok)
        assertTrue(r1.l2Triggered)
        assertEquals(5, r1.toolCalls)
        assertEquals(600, r1.totalTokens)
        assertTrue(exec.commands.any { it.first() == "jmap" })

        exec.commands.clear()
        val r2 = WorkflowBaseline(llm, tools, includeL2 = false).run(1234, "卡死")
        assertTrue(r2.ok)
        assertFalse(r2.l2Triggered)
        assertEquals(4, r2.toolCalls)
        assertFalse(exec.commands.any { it.first() == "jmap" })
    }

    @Test fun `EvalCase 按 expected 判对标准校验答案`() {
        val deadlock = STANDARD_EVAL_CASES.getValue("01-deadlock")
        assertTrue(deadlock.grade("检测到死锁,transfer-worker 与 audit-worker 互相等待").passed)
        assertFalse(deadlock.grade("检测到死锁,但没找到线程名").passed)

        val leak = STANDARD_EVAL_CASES.getValue("02-heap-leak")
        assertTrue(leak.grade("堆内存泄漏,HeapLeak\$OrderSnapshot 持有大量 [B 对象").passed)
        assertFalse(leak.grade("老年代持续增长,疑似内存泄漏").passed)

        val spin = STANDARD_EVAL_CASES.getValue("03-cpu-spin")
        assertTrue(spin.grade("线程 price-calculator 在 recalculatePrice 死循环占满 100% CPU").passed)
        assertFalse(spin.grade("CPU 飙高,可能是业务线程问题").passed)
    }
}

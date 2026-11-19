package jvmdiag

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 假 Executor:记录收到的 argv,返回预设结果。 */
class FakeExecutor(var result: () -> ExecResult = { ExecResult(0, "ok", "") }) : Executor {
    val commands = mutableListOf<List<String>>()
    override fun run(command: List<String>, timeoutMs: Long): ExecResult {
        commands += command
        return result()
    }
}

class DiagnosticToolsTest {
    private val roots = listOf("/tmp/jvm-diag/logs")
    private val exec = FakeExecutor()
    private val entries = mutableListOf<TraceEntry>()
    private var now = 1_000_000L
    private var approveAnswer = true
    private val approvals = mutableListOf<ApprovalRequest>()

    private val fakeL2 = Tool(
        name = "heap_histo_fake", description = "L2", risk = Risk.L2, params = emptyList(),
        timeoutMs = 1000, command = { listOf("jmap", "-histo:live", "1") },
    )

    private fun tools(limits: Limits = Limits(maxOutputChars = 100)) = DiagnosticTools(
        tools = standardTools(roots) + fakeL2,
        executor = exec,
        approver = { approvals += it; approveAnswer },
        trace = { entries += it },
        limits = limits,
        nowMs = { now },
    )

    @Test fun `argv 由代码拼接 模型只提供参数值`() {
        tools().call("jstat_gc", mapOf("pid" to 4242, "samples" to 2))
        assertEquals(listOf("jstat", "-gc", "4242", "1000", "2"), exec.commands.single())
    }

    @Test fun `注入型参数不会到达 Executor`() {
        val t = tools()
        val r1 = t.call("jstack", mapOf("pid" to "1234; cat /etc/passwd"))
        val r2 = t.call("gc_log_tail", mapOf("path" to "/tmp/jvm-diag/logs/../../../etc/shadow"))
        val r3 = t.call("jstack", mapOf("pid" to 1234, "extra" to "-l"))
        assertFalse(r1.ok); assertFalse(r2.ok); assertFalse(r3.ok)
        assertTrue(r1.output.startsWith("参数错误"))
        assertTrue(exec.commands.isEmpty())
    }

    @Test fun `合法路径中的 shell 元字符只是 argv 的一个元素`() {
        val p = "/tmp/jvm-diag/logs/gc;rm -rf x.log"
        tools().call("gc_log_tail", mapOf("path" to p, "lines" to 5))
        assertEquals(listOf("tail", "-n", "5", "--", p), exec.commands.single())
    }

    @Test fun `未知工具返回可用列表`() {
        val r = tools().call("bash", mapOf("cmd" to "ls"))
        assertFalse(r.ok)
        assertTrue("jstack" in r.output && "top_threads" in r.output)
        assertTrue(exec.commands.isEmpty())
    }

    @Test fun `输出超限被截断并在 trace 标记`() {
        exec.result = { ExecResult(0, "x".repeat(500), "") }
        val r = tools().call("jstack", mapOf("pid" to 99))
        assertTrue(r.ok)
        assertTrue("已截断" in r.output)
        assertTrue(entries.single().truncated)
    }

    @Test fun `gc_log_tail 截断保留末尾`() {
        exec.result = { ExecResult(0, "a".repeat(200) + "LAST", "") }
        val r = tools().call("gc_log_tail", mapOf("path" to "/tmp/jvm-diag/logs/gc.log"))
        assertTrue(r.output.endsWith("LAST"))
    }

    @Test fun `L1 限频 间隔后放行`() {
        val t = tools(Limits(l1MinIntervalMs = 5000))
        assertTrue(t.call("jstack", mapOf("pid" to 99)).ok)
        now += 1000
        val limited = t.call("jstack", mapOf("pid" to 99))
        assertFalse(limited.ok)
        assertTrue("限频" in limited.output && "4000ms" in limited.output)
        assertEquals(1, exec.commands.size)
        now += 4000
        assertTrue(t.call("jstack", mapOf("pid" to 99)).ok)
    }

    @Test fun `L0 不限频不审批`() {
        val t = tools()
        repeat(3) { assertTrue(t.call("jstat_gc", mapOf("pid" to 99)).ok) }
        assertTrue(approvals.isEmpty())
    }

    @Test fun `L2 需要审批 批准后执行`() {
        val r = tools().call("heap_histo_fake", emptyMap())
        assertTrue(r.ok)
        assertEquals("heap_histo_fake", approvals.single().tool)
        assertEquals(Approval.APPROVED, entries.single().approval)
    }

    @Test fun `L2 拒绝后不执行且 trace 记录 DENIED`() {
        approveAnswer = false
        val r = tools().call("heap_histo_fake", emptyMap())
        assertFalse(r.ok)
        assertTrue(exec.commands.isEmpty())
        assertEquals(Approval.DENIED, entries.single().approval)
    }

    @Test fun `命令失败 超时 无法启动 都明确回传`() {
        exec.result = { ExecResult(1, "", "No such process") }
        val failed = tools().call("jstat_gc", mapOf("pid" to 99999))
        assertFalse(failed.ok)
        assertTrue("exit=1" in failed.output && "No such process" in failed.output)

        exec.result = { ExecResult(-1, "", "", timedOut = true) }
        assertTrue("超时" in tools().call("jstat_gc", mapOf("pid" to 99)).output)

        exec.result = { throw IOException("Cannot run program \"jstat\"") }
        assertTrue("无法启动" in tools().call("jstat_gc", mapOf("pid" to 99)).output)
    }

    @Test fun `每次调用都落 trace 含校验失败`() {
        val t = tools()
        t.call("jstat_gc", mapOf("pid" to 99))
        t.call("jstat_gc", mapOf("pid" to "x"))
        t.call("nope", emptyMap())
        assertEquals(listOf(true, false, false), entries.map { it.ok })
        assertEquals(listOf("jstat_gc", "jstat_gc", "nope"), entries.map { it.tool })
        assertEquals(Risk.L0, entries[0].risk)
    }

    @Test fun `top_threads 追加十六进制 nid 并保留前 N 行`() {
        val raw = """
            top - 10:00:00 up 1 day
            Threads:  3 total
            
              PID USER      PR  NI    VIRT    RES  %CPU  %MEM COMMAND
             4097 u         20   0    1000    100  99.0   1.0 java
             4098 u         20   0    1000    100   1.0   1.0 java
             4099 u         20   0    1000    100   0.0   1.0 java
        """.trimIndent()
        exec.result = { ExecResult(0, raw, "") }
        val out = tools(Limits()).call("top_threads", mapOf("pid" to 4096, "top_n" to 2)).output
        assertTrue("nid=0x1001" in out)
        assertTrue("nid=0x1002" in out)
        assertFalse("4099" in out)
        assertTrue(out.contains("COMMAND  NID"))
    }

    @Test fun `jstack 死锁段在输出末尾 截断前被置顶`() {
        val dump = "\"main\" ...\n".repeat(50) + "Found one Java-level deadlock:\n\"a\" waits \"b\"\nFound 1 deadlock.\n"
        assertTrue(hoistDeadlock(dump).startsWith("=== 死锁摘要(置顶) ===\nFound one Java-level deadlock"))
        assertEquals("no deadlock here", hoistDeadlock("no deadlock here"))

        exec.result = { ExecResult(0, dump, "") }
        val r = tools().call("jstack", mapOf("pid" to 99))
        assertTrue("Found one Java-level deadlock" in r.output)
        assertTrue("已截断" in r.output)
    }

    @Test fun `specs 描述每个工具的参数范围`() {
        val spec = tools().specs().first { it["name"] == "gc_log_tail" }
        @Suppress("UNCHECKED_CAST")
        val schema = spec["input_schema"] as Map<String, Any>
        assertEquals(listOf("path"), schema["required"])
        assertEquals(false, schema["additionalProperties"])
    }
}

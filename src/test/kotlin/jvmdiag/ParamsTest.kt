package jvmdiag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParamsTest {
    private val pid = IntParam("pid", "pid", min = 2, max = 4_194_304)
    private val path = PathParam("path", "p", listOf("/tmp/jvm-diag/logs"))

    private fun rejects(params: List<Param>, args: Map<String, Any?>): String =
        assertFailsWith<ToolArgException> { validate(params, args) }.message!!

    @Test fun `整数参数接受 Int Long 与整数值 Double`() {
        assertEquals(1234, validate(listOf(pid), mapOf("pid" to 1234))["pid"])
        assertEquals(1234, validate(listOf(pid), mapOf("pid" to 1234L))["pid"])
        assertEquals(1234, validate(listOf(pid), mapOf("pid" to 1234.0))["pid"])
    }

    @Test fun `pid 注入与非法类型被拒绝`() {
        for (bad in listOf("1234; rm -rf /", "1234", "\$(id)", "1 && ls", 12.5, true, listOf(1))) {
            rejects(listOf(pid), mapOf("pid" to bad))
        }
    }

    @Test fun `范围越界被拒绝且消息含范围`() {
        assertTrue("[2, 4194304]" in rejects(listOf(pid), mapOf("pid" to 0)))
        assertTrue("[2, 4194304]" in rejects(listOf(pid), mapOf("pid" to -5)))
    }

    @Test fun `缺必填与未知参数`() {
        assertTrue("缺少必填参数: pid" in rejects(listOf(pid), emptyMap()))
        assertTrue("未知参数: cmd" in rejects(listOf(pid), mapOf("pid" to 10, "cmd" to "ls")))
    }

    @Test fun `默认值补齐`() {
        val lines = IntParam("lines", "l", 1, 500, default = 100)
        assertEquals(100, validate(listOf(lines), emptyMap())["lines"])
        assertEquals(7, validate(listOf(lines), mapOf("lines" to 7))["lines"])
    }

    @Test fun `路径白名单`() {
        assertEquals("/tmp/jvm-diag/logs/gc.log", validate(listOf(path), mapOf("path" to "/tmp/jvm-diag/logs/gc.log"))["path"])
        assertEquals("/tmp/jvm-diag/logs/a/gc.log", validate(listOf(path), mapOf("path" to "/tmp/jvm-diag/logs//a/./gc.log"))["path"])
    }

    @Test fun `路径穿越与越界被拒绝`() {
        val bad = listOf(
            "/tmp/jvm-diag/logs/../../etc/passwd",
            "/etc/passwd",
            "/tmp/jvm-diag/logs",          // 目录本身
            "/tmp/jvm-diag/logs-evil/x",   // 前缀碰撞
            "relative/gc.log",
            "-n 5 /etc/passwd",
            "",
            "/tmp/jvm-diag/logs/x\u0000.log",
            123,
        )
        for (b in bad) rejects(listOf(path), mapOf("path" to b))
    }
}

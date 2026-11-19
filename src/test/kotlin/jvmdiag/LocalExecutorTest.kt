package jvmdiag

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalExecutorTest {
    @Test fun `参数不经 shell 解释`() {
        val r = LocalExecutor().run(listOf("echo", "a; echo b \$(id)"), 5000)
        assertEquals("a; echo b \$(id)\n", r.stdout)
    }

    @Test fun `超时被杀`() {
        val r = LocalExecutor().run(listOf("sleep", "10"), 200)
        assertTrue(r.timedOut)
    }

    @Test fun `捕获量有上限`() {
        val r = LocalExecutor(maxCaptureBytes = 100).run(listOf("head", "-c", "100000", "/dev/zero"), 5000)
        assertEquals(100, r.stdout.length)
    }

    @Test fun `命令不存在抛 IOException`() {
        assertFailsWith<IOException> { LocalExecutor().run(listOf("no-such-binary-xyz"), 1000) }
    }
}

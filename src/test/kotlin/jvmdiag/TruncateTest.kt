package jvmdiag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TruncateTest {
    @Test fun `未超限原样返回`() = assertEquals("abc", truncate("abc", 3, Keep.HEAD))

    @Test fun `HEAD 保留开头并标记丢弃量`() {
        val r = truncate("0123456789", 4, Keep.HEAD)
        assertTrue(r.startsWith("0123\n"))
        assertTrue("丢弃末尾 6 字符" in r)
    }

    @Test fun `TAIL 保留末尾并标记丢弃量`() {
        val r = truncate("0123456789", 4, Keep.TAIL)
        assertTrue(r.endsWith("\n6789"))
        assertTrue("丢弃开头 6 字符" in r)
    }
}

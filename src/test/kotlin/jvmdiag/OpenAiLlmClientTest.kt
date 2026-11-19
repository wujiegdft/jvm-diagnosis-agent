package jvmdiag

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiLlmClientTest {
    private val mapper = ObjectMapper()

    @Test fun `请求体把 input_schema 适配为 OpenAI tools 且空 toolCalls 不下发`() {
        val client = OpenAiLlmClient("http://localhost:9999/v1", "k", "m", maxTokens = 2048)
        val specs = DiagnosticTools(
            tools = standardTools(listOf("/tmp/logs")),
            executor = FakeExecutor(),
            approver = { true },
            trace = {},
        ).specs()

        val body = client.buildRequestBody(
            LlmRequest(
                messages = listOf(
                    SystemMessage("sys"),
                    UserMessage("hi"),
                    AssistantMessage(content = "ok", toolCalls = emptyList()),
                    AssistantMessage(content = null, toolCalls = listOf(ToolCall("c1", "jstack", """{"pid":12}"""))),
                    ToolMessage("c1", "jstack", "out"),
                ),
                tools = specs,
            )
        )

        assertEquals("m", body["model"])
        assertEquals(2048, body["max_tokens"])
        assertEquals("auto", body["tool_choice"])

        @Suppress("UNCHECKED_CAST")
        val msgs = body["messages"] as List<Map<String, Any?>>
        assertFalse("tool_calls" in msgs[2])
        assertTrue("tool_calls" in msgs[3])
        assertEquals("tool", msgs[4]["role"])
        assertEquals("c1", msgs[4]["tool_call_id"])

        @Suppress("UNCHECKED_CAST")
        val tools = body["tools"] as List<Map<String, Any>>
        val jstackTool = tools.first { (it["function"] as Map<*, *>)["name"] == "jstack" }
        @Suppress("UNCHECKED_CAST")
        val fn = jstackTool["function"] as Map<String, Any>
        assertTrue(fn.containsKey("parameters"))
        assertFalse(fn.containsKey("input_schema"))
    }

    @Test fun `解析 tool_calls 与 reasoning_content 与 reasoning_tokens`() {
        val client = OpenAiLlmClient("http://localhost:9999/v1", "k", "m")
        val json = """
            {
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "content": null,
                  "reasoning_content": "先看线程栈",
                  "tool_calls": [{
                    "id": "call_99",
                    "type": "function",
                    "function": {"name": "jstack", "arguments": "{\"pid\":1234}"}
                  }]
                }
              }],
              "usage": {
                "prompt_tokens": 100,
                "completion_tokens": 50,
                "completion_tokens_details": {"reasoning_tokens": 30}
              }
            }
        """.trimIndent()

        val resp = client.parseResponse(json)
        assertEquals("tool_calls", resp.finishReason)
        assertNull(resp.message.content)
        assertEquals("先看线程栈", resp.message.reasoning)
        assertEquals(1, resp.message.toolCalls.size)
        assertEquals(ToolCall("call_99", "jstack", """{"pid":1234}"""), resp.message.toolCalls[0])
        assertEquals(Usage(100, 50, 30), resp.usage)
    }

    @Test fun `HTTP 往返携带 Bearer key 并处理非 2xx 错误`() {
        var capturedAuth: String? = null
        var capturedPath: String? = null
        var statusToReturn = 200
        var bodyToReturn = """
            {
              "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": "死锁"}}],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5}
            }
        """.trimIndent()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            capturedAuth = ex.requestHeaders.getFirst("Authorization")
            capturedPath = ex.requestURI.path
            // 校验请求体是合法 JSON
            mapper.readTree(ex.requestBody.readBytes())
            val bytes = bodyToReturn.toByteArray()
            ex.sendResponseHeaders(statusToReturn, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/v1/"
            val client = OpenAiLlmClient(baseUrl, "secret-token", "zhipu/glm-5.3-flash")

            val okResp = client.chat(LlmRequest(listOf(UserMessage("hi")), emptyList()))
            assertEquals("Bearer secret-token", capturedAuth)
            assertEquals("/v1/chat/completions", capturedPath)
            assertEquals("死锁", okResp.message.content)

            statusToReturn = 401
            bodyToReturn = """{"error":{"message":"invalid key"}}"""
            val ex = assertFailsWith<LlmException> {
                client.chat(LlmRequest(listOf(UserMessage("hi")), emptyList()))
            }
            assertTrue("HTTP 401" in ex.message!! && "invalid key" in ex.message!!)
        } finally {
            server.stop(0)
        }
    }
}

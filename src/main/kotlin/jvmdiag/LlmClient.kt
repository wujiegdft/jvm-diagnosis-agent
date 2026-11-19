package jvmdiag

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 对话消息(内部协议无关模型,换厂商只替换 LlmClient 实现)。 */
sealed interface Message

data class SystemMessage(val content: String) : Message
data class UserMessage(val content: String) : Message
data class AssistantMessage(
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val reasoning: String? = null,
) : Message
data class ToolMessage(val toolCallId: String, val name: String, val content: String) : Message

data class ToolCall(val id: String, val name: String, val rawArguments: String)

data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoningTokens: Int = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

data class LlmRequest(
    val messages: List<Message>,
    val tools: List<Map<String, Any>>,
)

data class LlmResponse(
    val message: AssistantMessage,
    val finishReason: String,
    val usage: Usage,
)

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 模型调用抽象。AgentLoop 只依赖此接口,测试可直接注入假实现。 */
fun interface LlmClient {
    fun chat(request: LlmRequest): LlmResponse
}

/**
 * OpenAI Chat Completions 兼容客户端(直走 JDK HttpClient,无第三方 SDK/框架)。
 * 把通用 Tool.spec() 的 input_schema 适配为 OpenAI tools.function.parameters。
 */
class OpenAiLlmClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = 4096,
    private val timeoutSec: Long = 120,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) : LlmClient {
    private val endpoint = URI.create("${baseUrl.trimEnd('/')}/chat/completions")
    private val mapper = ObjectMapper()

    override fun chat(request: LlmRequest): LlmResponse {
        val payload = buildRequestBody(request)
        val req = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build()

        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw LlmException("模型 HTTP 请求失败: ${e.message}", e)
        }
        if (resp.statusCode() !in 200..299) {
            throw LlmException("模型返回 HTTP ${resp.statusCode()}: ${resp.body().take(500)}")
        }
        return parseResponse(resp.body())
    }

    internal fun buildRequestBody(request: LlmRequest): Map<String, Any> = buildMap {
        put("model", model)
        put("max_tokens", maxTokens)
        put("messages", request.messages.map(::toOpenAiMessage))
        if (request.tools.isNotEmpty()) {
            put("tools", request.tools.map(::toOpenAiTool))
            put("tool_choice", "auto")
        }
    }

    private fun toOpenAiMessage(msg: Message): Map<String, Any?> = when (msg) {
        is SystemMessage -> mapOf("role" to "system", "content" to msg.content)
        is UserMessage -> mapOf("role" to "user", "content" to msg.content)
        is AssistantMessage -> buildMap {
            put("role", "assistant")
            put("content", msg.content)
            if (msg.toolCalls.isNotEmpty()) {
                put("tool_calls", msg.toolCalls.map { tc ->
                    mapOf(
                        "id" to tc.id,
                        "type" to "function",
                        "function" to mapOf("name" to tc.name, "arguments" to tc.rawArguments),
                    )
                })
            }
        }
        is ToolMessage -> mapOf(
            "role" to "tool",
            "tool_call_id" to msg.toolCallId,
            "content" to msg.content,
        )
    }

    private fun toOpenAiTool(spec: Map<String, Any>): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to spec.getValue("name"),
            "description" to spec.getValue("description"),
            "parameters" to spec.getValue("input_schema"),
        ),
    )

    internal fun parseResponse(body: String): LlmResponse {
        val root = try {
            mapper.readTree(body)
        } catch (e: Exception) {
            throw LlmException("模型响应 JSON 解析失败: ${body.take(300)}", e)
        }
        val choice = root.path("choices").takeIf { it.isArray && it.size() > 0 }?.get(0)
            ?: throw LlmException("模型响应缺少 choices: ${body.take(300)}")

        val msgNode = choice.path("message")
        val content = msgNode.textOrNull("content")
        val reasoning = msgNode.textOrNull("reasoning_content")
        val toolCalls = msgNode.path("tool_calls").takeIf { it.isArray }?.map { tc ->
            val fn = tc.path("function")
            ToolCall(
                id = tc.path("id").asText(""),
                name = fn.path("name").asText(""),
                rawArguments = fn.path("arguments").asText(""),
            )
        } ?: emptyList()

        val usageNode = root.path("usage")
        val usage = Usage(
            promptTokens = usageNode.path("prompt_tokens").asInt(0),
            completionTokens = usageNode.path("completion_tokens").asInt(0),
            reasoningTokens = usageNode.path("completion_tokens_details").path("reasoning_tokens").asInt(0),
        )
        return LlmResponse(
            message = AssistantMessage(content = content, toolCalls = toolCalls, reasoning = reasoning),
            finishReason = choice.path("finish_reason").asText(""),
            usage = usage,
        )
    }

    private fun JsonNode.textOrNull(field: String): String? {
        val n = this.get(field) ?: return null
        return if (n.isNull) null else n.asText()
    }
}

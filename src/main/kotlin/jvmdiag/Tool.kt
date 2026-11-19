package jvmdiag

enum class Risk { L0, L1, L2 }

/** 超长输出保留哪一端。jstack 看头,日志看尾。 */
enum class Keep { HEAD, TAIL }

/**
 * 一个白名单工具。命令由 [command] 用校验后的参数拼成 argv,模型只能提供参数值。
 * L3(变更类)不提供,所以 Risk 里没有 L3。
 */
class Tool(
    val name: String,
    val description: String,
    val risk: Risk,
    val params: List<Param>,
    val timeoutMs: Long,
    val keep: Keep = Keep.HEAD,
    val command: (Map<String, Any>) -> List<String>,
    /** 对命令 stdout 的纯函数后处理(在截断之前)。 */
    val postProcess: (String, Map<String, Any>) -> String = { out, _ -> out },
) {
    /** 给模型的 JSON Schema 风格描述。 */
    fun spec(): Map<String, Any> = mapOf(
        "name" to name,
        "description" to "$description [risk=$risk]",
        "input_schema" to mapOf(
            "type" to "object",
            "properties" to params.associate { p ->
                p.name to buildMap<String, Any> {
                    put("type", p.jsonType)
                    put("description", p.description)
                    if (p is IntParam) { put("minimum", p.min); put("maximum", p.max) }
                    p.default?.let { put("default", it) }
                }
            },
            "required" to params.filter { it.default == null }.map { it.name },
            "additionalProperties" to false,
        ),
    )
}

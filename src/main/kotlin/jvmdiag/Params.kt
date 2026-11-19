package jvmdiag

class ToolArgException(message: String) : Exception(message)

/** 带类型的参数声明。模型给的值一律不可信,必须经 [validate]。 */
sealed interface Param {
    val name: String
    val description: String
    val default: Any?
    val jsonType: String
    fun check(value: Any): Any
}

class IntParam(
    override val name: String,
    override val description: String,
    val min: Int,
    val max: Int,
    override val default: Int? = null,
) : Param {
    override val jsonType = "integer"

    override fun check(value: Any): Int {
        val n = when {
            value is Int -> value
            value is Long && value in Int.MIN_VALUE..Int.MAX_VALUE -> value.toInt()
            value is Double && value == Math.rint(value) && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() -> value.toInt()
            else -> throw ToolArgException("参数 $name 必须是整数,收到: ${show(value)}")
        }
        if (n < min || n > max) throw ToolArgException("参数 $name 必须在 [$min, $max],收到: $n")
        return n
    }
}

/** 绝对路径,词法归一化后必须落在 allowedRoots 之下。纯函数,不访问文件系统(便于测试与 SSH 复用)。 */
class PathParam(
    override val name: String,
    override val description: String,
    val allowedRoots: List<String>,
) : Param {
    override val default: Any? = null
    override val jsonType = "string"

    override fun check(value: Any): String {
        if (value !is String) throw ToolArgException("参数 $name 必须是字符串,收到: ${show(value)}")
        if (value.isEmpty() || value.contains('\u0000')) throw ToolArgException("参数 $name 非法")
        if (!value.startsWith("/")) throw ToolArgException("参数 $name 必须是绝对路径,收到: $value")
        if (value.split('/').contains("..")) throw ToolArgException("参数 $name 不允许包含 '..': $value")
        val normalized = java.nio.file.Paths.get(value).normalize().toString()
        val ok = allowedRoots.any { normalized.startsWith(it.trimEnd('/') + "/") }
        if (!ok) throw ToolArgException("参数 $name 不在允许目录内。允许: ${allowedRoots.joinToString()},收到: $value")
        return normalized
    }
}

private fun show(v: Any): String = v.toString().take(80)

/** 校验并补默认值。未知参数、缺失必填、类型/范围错误都抛 [ToolArgException],消息会原样回给模型。 */
fun validate(params: List<Param>, args: Map<String, Any?>): Map<String, Any> {
    val unknown = args.keys - params.map { it.name }.toSet()
    if (unknown.isNotEmpty()) {
        throw ToolArgException("未知参数: ${unknown.joinToString()}。可用参数: ${params.joinToString { it.name }.ifEmpty { "(无)" }}")
    }
    return params.associate { p ->
        val raw = args[p.name] ?: p.default ?: throw ToolArgException("缺少必填参数: ${p.name}")
        p.name to p.check(raw)
    }
}

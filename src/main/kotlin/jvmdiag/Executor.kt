package jvmdiag

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

/** 传输层抽象。command 是 argv,不经过 shell。Local / SSH / MCP 只替换这一层。 */
interface Executor {
    /** 无法启动命令时抛 IOException。 */
    fun run(command: List<String>, timeoutMs: Long): ExecResult
}

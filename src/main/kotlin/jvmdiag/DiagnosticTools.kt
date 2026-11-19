package jvmdiag

import java.io.IOException

data class ToolResult(val ok: Boolean, val output: String)

data class ApprovalRequest(val tool: String, val args: Map<String, Any>, val risk: Risk)

/** 审批钩子。L2 调用前同步询问;实现可以是控制台 y/n、测试里的固定答案。 */
fun interface Approver {
    fun approve(request: ApprovalRequest): Boolean
}

data class Limits(
    val maxOutputChars: Int = 8000,
    /** L1 工具同名调用的最小间隔。 */
    val l1MinIntervalMs: Long = 5000,
)

/**
 * 工具层入口:校验 → 限频 → 审批 → Executor → 后处理 → 截断 → trace。
 * 对 Agent Loop 只暴露 [specs] 与 [call],对传输层只依赖 [Executor]。
 * 所有失败都以 ok=false + 明确信息返回,不抛给 loop。
 */
class DiagnosticTools(
    tools: List<Tool>,
    private val executor: Executor,
    private val approver: Approver,
    private val trace: Trace,
    private val limits: Limits = Limits(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val byName = tools.associateBy { it.name }
    private val lastL1Call = mutableMapOf<String, Long>()

    fun specs(): List<Map<String, Any>> = byName.values.map { it.spec() }

    fun call(name: String, args: Map<String, Any?>): ToolResult {
        val start = nowMs()
        fun finish(tool: Tool?, approval: Approval, ok: Boolean, text: String, truncated: Boolean = false): ToolResult {
            trace.record(TraceEntry(start, name, args, tool?.risk, approval, ok, text.take(200), nowMs() - start, truncated))
            return ToolResult(ok, text)
        }

        val tool = byName[name]
            ?: return finish(null, Approval.AUTO, false, "未知工具: $name。可用工具: ${byName.keys.joinToString()}")

        val valid = try {
            validate(tool.params, args)
        } catch (e: ToolArgException) {
            return finish(tool, Approval.AUTO, false, "参数错误: ${e.message}")
        }

        if (tool.risk == Risk.L1) {
            val last = lastL1Call[name]
            if (last != null && start - last < limits.l1MinIntervalMs) {
                val wait = limits.l1MinIntervalMs - (start - last)
                return finish(tool, Approval.AUTO, false, "限频: $name 属于 L1(有停顿),请 ${wait}ms 后重试,或先用其他工具")
            }
            lastL1Call[name] = start
        }

        var approval = Approval.AUTO
        if (tool.risk == Risk.L2) {
            approval = if (approver.approve(ApprovalRequest(name, valid, tool.risk))) Approval.APPROVED else Approval.DENIED
            if (approval == Approval.DENIED) return finish(tool, approval, false, "人工拒绝执行 $name,请改用其他只读手段")
        }

        val result = try {
            executor.run(tool.command(valid), tool.timeoutMs)
        } catch (e: IOException) {
            return finish(tool, approval, false, "命令无法启动: ${e.message}")
        }
        if (result.timedOut) return finish(tool, approval, false, "命令超时(>${tool.timeoutMs}ms)")
        if (result.exitCode != 0) {
            val msg = "命令失败 exit=${result.exitCode}\n" + (result.stderr.ifBlank { result.stdout })
            return finish(tool, approval, false, truncate(msg, limits.maxOutputChars, Keep.HEAD))
        }

        val processed = tool.postProcess(result.stdout, valid)
        val out = truncate(processed, limits.maxOutputChars, tool.keep)
        return finish(tool, approval, true, out, truncated = out.length != processed.length)
    }
}

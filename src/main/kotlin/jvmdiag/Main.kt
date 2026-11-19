package jvmdiag

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

private const val DEFAULT_BASE_URL = "http://127.0.0.1:3430/v1"
private const val DEFAULT_MODEL = "zhipu/glm-5.3-flash"

/**
 * CLI 入口:
 *   list                               列出白名单工具
 *   call <tool> [k=v ...]              手动调用单个工具
 *   diagnose <问题描述>                 启动 Agent Loop 自主诊断(需环境变量 LLM_API_KEY)
 *   eval <caseId> <pid> [outJsonl]     对指定故障进程跑 Agent vs 固定 Workflow 对照评估
 */
fun main(argv: Array<String>) {
    val logRoots = System.getenv("JVM_DIAG_LOG_ROOTS")
        ?.split(":")
        ?.filter { it.isNotBlank() }
        ?.ifEmpty { null }
        ?: listOf("/tmp/jvm-diag/logs")

    fun makeTools(approver: Approver) = DiagnosticTools(
        tools = standardTools(logRoots = logRoots),
        executor = LocalExecutor(),
        approver = approver,
        trace = JsonlTrace(Paths.get("trace", "trace.jsonl")),
    )

    val interactiveApprover = Approver { r ->
        print("[审批] 批准执行 ${r.tool} ${r.args} (${r.risk})? [y/N] ")
        readlnOrNull()?.trim().equals("y", ignoreCase = true)
    }

    if (argv.isEmpty() || argv[0] == "list") {
        val tools = makeTools(interactiveApprover)
        println("用法:")
        println("  list")
        println("  call <tool> [key=value ...]")
        println("  diagnose <问题描述>")
        println("  eval <caseId> <pid> [outJsonl]")
        println("\n可用工具:")
        tools.specs().forEach { println("  - ${it["name"]}: ${it["description"]}") }
        return
    }

    when (argv[0]) {
        "call" -> {
            if (argv.size < 2) {
                System.err.println("用法: call <tool> [key=value ...]")
                exitProcess(1)
            }
            val args = argv.drop(2).associate { kv ->
                val parts = kv.split("=", limit = 2)
                if (parts.size != 2) {
                    System.err.println("参数格式错误(期望 key=value): $kv")
                    exitProcess(1)
                }
                parts[0] to (parts[1].toIntOrNull() ?: parts[1])
            }
            val r = makeTools(interactiveApprover).call(argv[1], args)
            println(if (r.ok) r.output else "ERROR: ${r.output}")
        }

        "diagnose" -> {
            val prompt = argv.drop(1).joinToString(" ").trim()
            if (prompt.isEmpty()) {
                System.err.println("用法: diagnose <问题描述>")
                exitProcess(1)
            }
            val cfg = loadLlmConfig()
            val llm = OpenAiLlmClient(cfg.baseUrl, cfg.apiKey, cfg.model, cfg.maxTokens)
            val loop = AgentLoop(llm = llm, tools = makeTools(interactiveApprover), onEvent = ::printEvent)

            println("==> 开始诊断 | model=${cfg.model} | endpoint=${cfg.baseUrl}")
            val result = loop.run(prompt)
            JsonlSessionTrace(Paths.get("trace", "sessions.jsonl")).record(prompt, cfg.model, result)

            println("\n================ 诊断结论 (${result.stopReason}) ================")
            println(result.answer)
            println("================================================================")
            println(
                "统计: steps=${result.steps}, toolCalls=${result.toolCalls}, duplicateBlocked=${result.duplicateBlocked}, " +
                    "tokens=${result.totalTokens}(prompt=${result.promptTokens}, completion=${result.completionTokens}, reasoning=${result.reasoningTokens}), " +
                    "duration=${result.durationMs}ms"
            )
            if (result.stopReason != StopReason.COMPLETED) exitProcess(2)
        }

        "eval" -> {
            if (argv.size < 3) {
                System.err.println("用法: eval <caseId> <pid> [outJsonl]  (可用 caseId: ${STANDARD_EVAL_CASES.keys.joinToString()})")
                exitProcess(1)
            }
            val evalCase = STANDARD_EVAL_CASES[argv[1]] ?: run {
                System.err.println("未知 caseId: ${argv[1]} (可用: ${STANDARD_EVAL_CASES.keys.joinToString()})")
                exitProcess(1)
            }
            val pid = argv[2].toIntOrNull() ?: run {
                System.err.println("pid 必须是整数: ${argv[2]}")
                exitProcess(1)
            }
            val outFile = argv.getOrNull(3)?.let { Paths.get(it) }
            val cfg = loadLlmConfig()
            val llm = OpenAiLlmClient(cfg.baseUrl, cfg.apiKey, cfg.model, cfg.maxTokens)
            val prompt = evalCase.promptFor(pid)
            val mapper = ObjectMapper()

            fun writeRecord(row: Map<String, Any?>) {
                if (outFile == null) return
                outFile.parent?.let { Files.createDirectories(it) }
                Files.writeString(
                    outFile,
                    mapper.writeValueAsString(row) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                )
            }

            println("\n========== EVAL CASE: ${evalCase.id} (pid=$pid, model=${cfg.model}) ==========")

            // 1) Agent (评估模式下 L2 自动放行并记录是否触发)
            var agentL2 = false
            val agentTools = makeTools { agentL2 = true; true }
            println("--- [1/3] Agent ---")
            val agentRes = AgentLoop(llm = llm, tools = agentTools, onEvent = ::printEvent).run(prompt)
            val agentGrade = evalCase.grade(agentRes.answer)
            println("Agent 结果: pass=${agentGrade.passed} ${agentGrade.details} | stop=${agentRes.stopReason} steps=${agentRes.steps} tools=${agentRes.toolCalls} l2=$agentL2 tokens=${agentRes.totalTokens} duration=${agentRes.durationMs}ms")
            writeRecord(
                mapOf(
                    "caseId" to evalCase.id,
                    "mode" to "agent",
                    "model" to cfg.model,
                    "passed" to agentGrade.passed,
                    "checks" to agentGrade.details,
                    "stopReason" to agentRes.stopReason.name,
                    "steps" to agentRes.steps,
                    "toolCalls" to agentRes.toolCalls,
                    "duplicateBlocked" to agentRes.duplicateBlocked,
                    "l2Triggered" to agentL2,
                    "expectedL2Needed" to evalCase.expectedL2Needed,
                    "promptTokens" to agentRes.promptTokens,
                    "completionTokens" to agentRes.completionTokens,
                    "reasoningTokens" to agentRes.reasoningTokens,
                    "totalTokens" to agentRes.totalTokens,
                    "durationMs" to agentRes.durationMs,
                )
            )

            // 2) Workflow (含 L2)
            println("--- [2/3] Workflow (with L2) ---")
            val wfWithL2 = WorkflowBaseline(llm = llm, tools = makeTools { true }, includeL2 = true).run(pid, prompt)
            val wfWithL2Grade = evalCase.grade(wfWithL2.answer)
            println("Workflow(+L2) 结果: pass=${wfWithL2Grade.passed} ${wfWithL2Grade.details} | ok=${wfWithL2.ok} tools=${wfWithL2.toolCalls} l2=${wfWithL2.l2Triggered} tokens=${wfWithL2.totalTokens} duration=${wfWithL2.durationMs}ms")
            if (!wfWithL2.ok) println("  -> ERR: ${wfWithL2.answer.take(200)}")
            writeRecord(
                mapOf(
                    "caseId" to evalCase.id,
                    "mode" to "workflow_with_l2",
                    "model" to cfg.model,
                    "passed" to wfWithL2Grade.passed,
                    "checks" to wfWithL2Grade.details,
                    "stopReason" to if (wfWithL2.ok) "COMPLETED" else "ERROR",
                    "error" to if (wfWithL2.ok) null else wfWithL2.answer.take(300),
                    "steps" to 1,
                    "toolCalls" to wfWithL2.toolCalls,
                    "duplicateBlocked" to 0,
                    "l2Triggered" to wfWithL2.l2Triggered,
                    "expectedL2Needed" to evalCase.expectedL2Needed,
                    "promptTokens" to wfWithL2.promptTokens,
                    "completionTokens" to wfWithL2.completionTokens,
                    "reasoningTokens" to wfWithL2.reasoningTokens,
                    "totalTokens" to wfWithL2.totalTokens,
                    "durationMs" to wfWithL2.durationMs,
                )
            )

            // 3) Workflow (不含 L2,保守运维策略)
            println("--- [3/3] Workflow (no L2) ---")
            val wfNoL2 = WorkflowBaseline(llm = llm, tools = makeTools { false }, includeL2 = false).run(pid, prompt)
            val wfNoL2Grade = evalCase.grade(wfNoL2.answer)
            println("Workflow(noL2) 结果: pass=${wfNoL2Grade.passed} ${wfNoL2Grade.details} | ok=${wfNoL2.ok} tools=${wfNoL2.toolCalls} l2=${wfNoL2.l2Triggered} tokens=${wfNoL2.totalTokens} duration=${wfNoL2.durationMs}ms")
            if (!wfNoL2.ok) println("  -> ERR: ${wfNoL2.answer.take(200)}")
            writeRecord(
                mapOf(
                    "caseId" to evalCase.id,
                    "mode" to "workflow_no_l2",
                    "model" to cfg.model,
                    "passed" to wfNoL2Grade.passed,
                    "checks" to wfNoL2Grade.details,
                    "stopReason" to if (wfNoL2.ok) "COMPLETED" else "ERROR",
                    "error" to if (wfNoL2.ok) null else wfNoL2.answer.take(300),
                    "steps" to 1,
                    "toolCalls" to wfNoL2.toolCalls,
                    "duplicateBlocked" to 0,
                    "l2Triggered" to wfNoL2.l2Triggered,
                    "expectedL2Needed" to evalCase.expectedL2Needed,
                    "promptTokens" to wfNoL2.promptTokens,
                    "completionTokens" to wfNoL2.completionTokens,
                    "reasoningTokens" to wfNoL2.reasoningTokens,
                    "totalTokens" to wfNoL2.totalTokens,
                    "durationMs" to wfNoL2.durationMs,
                )
            )
        }

        else -> {
            System.err.println("未知子命令: ${argv[0]} (可用: list | call | diagnose | eval)")
            exitProcess(1)
        }
    }
}

private data class LlmConfig(val baseUrl: String, val apiKey: String, val model: String, val maxTokens: Int)

private fun loadLlmConfig(): LlmConfig {
    val apiKey = System.getenv("LLM_API_KEY")?.trim().orEmpty()
    if (apiKey.isEmpty()) {
        System.err.println("缺少环境变量 LLM_API_KEY")
        exitProcess(1)
    }
    return LlmConfig(
        baseUrl = System.getenv("LLM_BASE_URL")?.trim()?.ifEmpty { null } ?: DEFAULT_BASE_URL,
        apiKey = apiKey,
        model = System.getenv("LLM_MODEL")?.trim()?.ifEmpty { null } ?: DEFAULT_MODEL,
        maxTokens = System.getenv("LLM_MAX_TOKENS")?.toIntOrNull() ?: 4096,
    )
}

private fun printEvent(e: AgentEvent) {
    when (e) {
        is AgentEvent.LlmStep -> {
            val reasoning = e.reasoningPreview?.let { " | thought: $it..." } ?: ""
            println("[step ${e.step}] LLM finish=${e.finishReason} toolCalls=${e.toolCallCount} tokens=${e.usage.totalTokens}(r=${e.usage.reasoningTokens})$reasoning")
        }
        is AgentEvent.ToolExecuted -> {
            val status = if (e.ok) "OK" else "ERR"
            println("  -> tool ${e.tool}(${e.args}) [$status]: ${e.outputPreview}")
        }
        is AgentEvent.DuplicateBlocked -> {
            println("  -> BLOCKED duplicate ${e.tool}(${e.args}) (prev=${e.previousCount})")
        }
    }
}

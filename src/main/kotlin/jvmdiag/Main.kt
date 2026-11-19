package jvmdiag

import java.nio.file.Paths

/** 手动驱动工具层(尚未接模型): MainKt <tool> [key=value ...] ;不带参数则列出工具。 */
fun main(argv: Array<String>) {
    val tools = DiagnosticTools(
        tools = standardTools(logRoots = listOf("/tmp/jvm-diag/logs")),
        executor = LocalExecutor(),
        approver = { r -> print("批准 ${r.tool} ${r.args} (${r.risk})? [y/N] "); readlnOrNull()?.trim() == "y" },
        trace = JsonlTrace(Paths.get("trace", "trace.jsonl")),
    )
    if (argv.isEmpty()) {
        tools.specs().forEach { println("${it["name"]}: ${it["description"]}") }
        return
    }
    val args = argv.drop(1).associate { kv ->
        val (k, v) = kv.split("=", limit = 2)
        k to (v.toIntOrNull() ?: v)
    }
    val r = tools.call(argv[0], args)
    println(if (r.ok) r.output else "ERROR: ${r.output}")
}

package jvmdiag

/** 标准工具集。pid 一律是整数,命令名与 flag 全部写死在代码里。 */
fun standardTools(logRoots: List<String>): List<Tool> {
    val pid = IntParam("pid", "目标 JVM 进程号", min = 2, max = 4_194_304)
    return listOf(
        Tool(
            name = "jstack",
            description = "导出线程栈,可看死锁(Found one Java-level deadlock)、线程状态、锁等待。会进入 safepoint,有短暂停顿。",
            risk = Risk.L1,
            params = listOf(pid),
            timeoutMs = 20_000,
            command = { listOf("jstack", it.getValue("pid").toString()) },
            postProcess = { out, _ -> hoistDeadlock(out) },
        ),
        Tool(
            name = "jstat_gc",
            description = "采样 GC 统计(jstat -gc),各代容量/使用量(KB)与 GC 次数/耗时。多次采样可看增长趋势。",
            risk = Risk.L0,
            params = listOf(
                pid,
                IntParam("interval_ms", "采样间隔毫秒", min = 100, max = 2000, default = 1000),
                IntParam("samples", "采样次数", min = 1, max = 5, default = 3),
            ),
            timeoutMs = 15_000,
            command = {
                listOf("jstat", "-gc", it.getValue("pid").toString(), it.getValue("interval_ms").toString(), it.getValue("samples").toString())
            },
        ),
        Tool(
            name = "top_threads",
            description = "按 CPU 降序列出目标进程的线程(top -H)。每行追加 nid(十六进制线程号),可与 jstack 的 nid=0x.. 对应。",
            risk = Risk.L0,
            params = listOf(pid, IntParam("top_n", "返回前 N 个线程", min = 1, max = 30, default = 10)),
            timeoutMs = 10_000,
            command = { listOf("top", "-H", "-b", "-n", "1", "-w", "512", "-o", "%CPU", "-p", it.getValue("pid").toString()) },
            postProcess = { out, args -> formatTopThreads(out, args.getValue("top_n") as Int) },
        ),
        Tool(
            name = "gc_log_tail",
            description = "读取 GC 日志末尾 N 行。path 必须在允许目录内: ${logRoots.joinToString()}。",
            risk = Risk.L0,
            params = listOf(
                PathParam("path", "GC 日志绝对路径", logRoots),
                IntParam("lines", "末尾行数", min = 1, max = 500, default = 100),
            ),
            timeoutMs = 5_000,
            keep = Keep.TAIL,
            command = { listOf("tail", "-n", it.getValue("lines").toString(), "--", it.getValue("path") as String) },
        ),
    )
}

/** 保留 top 头部摘要 + 前 topN 个线程行,并给线程号追加十六进制 nid。纯函数。 */
fun formatTopThreads(raw: String, topN: Int): String {
    val lines = raw.lines()
    val headerIdx = lines.indexOfFirst { it.trimStart().startsWith("PID") }
    if (headerIdx < 0) return raw
    val summary = lines.take(headerIdx)
    val rows = lines.drop(headerIdx + 1).filter { it.isNotBlank() }.take(topN).map { row ->
        val tid = row.trim().split(Regex("\\s+")).first().toLongOrNull()
        if (tid == null) row else "$row  nid=0x${java.lang.Long.toHexString(tid)}"
    }
    return (summary + (lines[headerIdx] + "  NID") + rows).joinToString("\n")
}

/** jstack 的死锁结论在输出末尾,头部截断会把它丢掉。有死锁时把该段置顶。纯函数。 */
fun hoistDeadlock(dump: String): String {
    val i = dump.indexOf("Found one Java-level deadlock")
    if (i < 0) return dump
    return "=== 死锁摘要(置顶) ===\n${dump.substring(i)}\n=== 完整线程栈 ===\n${dump.substring(0, i)}"
}

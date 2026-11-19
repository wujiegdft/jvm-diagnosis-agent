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
        Tool(
            name = "jcmd_vm_flags",
            description = "查看目标 JVM 启动与生效的 VM 参数(jcmd VM.flags),如 MaxHeapSize、GC 算法。",
            risk = Risk.L0,
            params = listOf(pid),
            timeoutMs = 10_000,
            command = { listOf("jcmd", it.getValue("pid").toString(), "VM.flags") },
        ),
        Tool(
            name = "heap_histo",
            description = "统计存活对象直方图(jmap -histo:live),按字节降序返回前 N 类与 Total。注意:会触发 Full GC,属于 L2 重操作需人工确认。",
            risk = Risk.L2,
            params = listOf(pid, IntParam("top_n", "返回占用最大的前 N 个类", min = 5, max = 100, default = 20)),
            timeoutMs = 30_000,
            command = { listOf("jmap", "-histo:live", it.getValue("pid").toString()) },
            postProcess = { out, args -> formatHeapHisto(out, args.getValue("top_n") as Int) },
        ),
    )
}

/** 保留 jmap -histo 表头 + 前 topN 个类 + 末尾 Total 汇总行。纯函数。 */
fun formatHeapHisto(raw: String, topN: Int): String {
    val lines = raw.lines().filter { it.isNotBlank() }
    val sepIdx = lines.indexOfFirst { it.trim().startsWith("---") }
    if (sepIdx < 0) return raw
    val header = lines.take(sepIdx + 1)
    val body = lines.drop(sepIdx + 1)
    val totalLine = body.lastOrNull { it.trimStart().startsWith("Total") }
    val classRows = body.filterNot { it.trimStart().startsWith("Total") }
    val kept = classRows.take(topN)
    val omitted = classRows.size - kept.size
    return buildList {
        addAll(header)
        addAll(kept)
        if (omitted > 0) add("... (省略其余 $omitted 个类)")
        if (totalLine != null) add(totalLine)
    }.joinToString("\n")
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

package jvmdiag

/** 一项确定性判对条件:任意一个关键词命中(忽略大小写)即通过。 */
data class RubricCheck(val label: String, val anyOf: List<String>) {
    fun matches(answer: String): Boolean {
        val lower = answer.lowercase()
        return anyOf.any { it.lowercase() in lower }
    }
}

/** 故障用例的评估规范(对应 fault-cases/<id>/expected.md 中的判对标准)。 */
data class EvalCase(
    val id: String,
    val symptomTemplate: String,
    val expectedL2Needed: Boolean,
    val checks: List<RubricCheck>,
) {
    fun promptFor(pid: Int): String = symptomTemplate.replace("{pid}", pid.toString())

    fun grade(answer: String): EvalGrade {
        val details = checks.associate { it.label to it.matches(answer) }
        return EvalGrade(passed = details.values.all { it }, details = details)
    }
}

data class EvalGrade(val passed: Boolean, val details: Map<String, Boolean>)

val STANDARD_EVAL_CASES: Map<String, EvalCase> = listOf(
    EvalCase(
        id = "01-deadlock",
        symptomTemplate = "PID {pid} 对应的 Java 服务卡死无响应，请排查原因",
        expectedL2Needed = false,
        checks = listOf(
            RubricCheck("识别死锁", listOf("死锁", "deadlock")),
            RubricCheck("定位线程 transfer-worker", listOf("transfer-worker")),
            RubricCheck("定位线程 audit-worker", listOf("audit-worker")),
        ),
    ),
    EvalCase(
        id = "02-heap-leak",
        symptomTemplate = "PID {pid} 对应的 Java 服务内存持续上涨、响应越来越慢，请排查原因",
        expectedL2Needed = true,
        checks = listOf(
            RubricCheck("识别堆内存泄漏", listOf("泄漏", "leak")),
            RubricCheck("定位泄漏对象或缓存", listOf("OrderSnapshot", "ORDER_CACHE", "cache-loader")),
            RubricCheck("指出大对象 byte[] 占用", listOf("[B", "byte[]", "字节数组")),
        ),
    ),
    EvalCase(
        id = "03-cpu-spin",
        symptomTemplate = "PID {pid} 对应的 Java 服务 CPU 占用异常飙高，请排查原因",
        expectedL2Needed = false,
        checks = listOf(
            RubricCheck("识别 CPU 死循环/高占用", listOf("死循环", "忙等", "100%", "飙高", "spin", "循环")),
            RubricCheck("定位线程 price-calculator", listOf("price-calculator")),
            RubricCheck("定位热点方法 recalculatePrice", listOf("recalculatePrice")),
        ),
    ),
).associateBy { it.id }

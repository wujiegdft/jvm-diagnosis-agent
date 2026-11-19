# 01-deadlock 根因真值

**运行**: `java Deadlock.java`(启动时打印 pid)

## 根因
`transfer-worker` 持有 `ACCOUNT_LOCK` 等待 `LEDGER_LOCK`;`audit-worker` 持有 `LEDGER_LOCK` 等待 `ACCOUNT_LOCK`。
两把锁加锁顺序相反 → 循环等待 → 死锁。进程存活、CPU≈0、无 OOM。

## 关键证据
- `jstack`: 末尾 `Found one Java-level deadlock`,列出两个线程 `transfer-worker` / `audit-worker` 互相等待对方持有的 monitor。
- 两线程状态均为 `BLOCKED (on object monitor)`,栈在 `Deadlock.lambda$main$0` / `lambda$main$1` 的 `synchronized`。
- `top_threads`: 所有线程 CPU≈0(排除死循环)。
- `jstat_gc`: 堆稳定、无 Full GC 增长(排除内存问题)。

## 判对标准
结论须指出:死锁;涉及线程 `transfer-worker` 与 `audit-worker`;原因为加锁顺序相反(两把锁交叉获取)。
修复建议(加分):统一加锁顺序 / `tryLock` 超时。

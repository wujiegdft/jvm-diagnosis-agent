# 03-cpu-spin 根因真值

**运行**: `java CpuSpin.java`(启动时打印 pid)

## 根因
业务线程 `price-calculator` 在 `CpuSpin.recalculatePrice` 方法中陷入无终止条件的计算死循环(`while (true)`),持续占用约 100% 单核 CPU;其他线程(`heartbeat-worker`、`metrics-reporter`、`main`)均处于正常休眠,无 GC 抖动与锁死锁。

## 关键证据
- `top_threads`: 单个线程 CPU 接近 99~100%,记下其十六进制 `nid=0x...`,其余线程 CPU≈0%。
- `jstack`: 按上述 `nid` 精确匹配到线程 `"price-calculator"`,状态为 `RUNNABLE`,调用栈停留在 `CpuSpin.recalculatePrice(CpuSpin.java:...)`。
- `jstat_gc`: 堆使用量稳定、`FGC=0`(排除频繁 Full GC 导致的 CPU 飙高)。

## 判对标准
结论须指出:
1. 单个业务线程 CPU 死循环/高占用(非 GC 导致);
2. 精确定位到线程名 `price-calculator`(通过 `top_threads` 的 `nid` 与 `jstack` 关联);
3. 精确定位到热点方法 `CpuSpin.recalculatePrice`;
4. 修复建议(加分):检查循环退出条件、避免无休眠/无边界忙等,必要时用 async-profiler / JFR 做进一步 CPU 采样。

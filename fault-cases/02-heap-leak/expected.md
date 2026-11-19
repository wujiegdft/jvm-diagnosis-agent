# 02-heap-leak 根因真值

**运行**: `java -Xmx128m HeapLeak.java`(启动时打印 pid)

## 根因
`HeapLeak.ORDER_CACHE`(静态 `ConcurrentHashMap`)持续写入 `HeapLeak$OrderSnapshot`(每个持有一个 256KB 的 `byte[]` payload)且无过期/淘汰机制,导致存活对象不断晋升并滞留老年代,引发堆内存泄漏、老年代占用持续攀升直至频繁 Full GC / OOM。

## 关键证据
- `jstat_gc`: 老年代使用量 `OU` 持续单调上涨(或接近 `OC` 上限),`YGC`/`FGC` 次数与耗时不断增长,GC 后无法回收。
- `heap_histo`(L2,需人工确认): `[B`(byte[]) 与 `HeapLeak$OrderSnapshot`、`ConcurrentHashMap$Node` 排在存活对象前列,占总存活堆绝大部分字节。
- `jcmd_vm_flags`: 可见 `-XX:MaxHeapSize=134217728`(128MB)等堆上限配置。
- `jstack`: 可见业务线程 `cache-loader` 在 `HeapLeak.lambda$main$0` 中持续分配/写入。

## 判对标准
结论须指出:
1. 堆内存泄漏(老年代持续增长 / 存活对象无法被 GC 回收);
2. 关键泄漏对象类型(`HeapLeak$OrderSnapshot` 及其持有的 `byte[]` / `[B`,或静态缓存 Map 持续累积);
3. 修复建议(加分):改用有界缓存(LRU / Caffeine / 设上限与 TTL)、避免大对象长期常驻静态集合。

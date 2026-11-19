# 踩坑复盘

格式:现象 / 根因 / 解法

## 1. jstack 死锁结论在输出末尾,被头部截断丢掉
- **现象**:对真实死锁进程跑 `jstack`,输出超过 8000 字符上限,截断后丢了末尾 `Found 1 deadlock`;输出再大一点,整个死锁段都会没。
- **根因**:jstack 先打全部线程栈,最后才打 `Found one Java-level deadlock`。"保留开头"的通用截断策略恰好丢掉最关键证据。
- **解法**:截断前做工具专属后处理 `hoistDeadlock`,把死锁段置顶;截断策略按工具区分(`Keep.HEAD` / `Keep.TAIL`,日志看尾)。
- **要点**:截断不是 `substring`,要看"信息密度在输出哪一端"。tool 输出需要结构化后处理,不能统一交给 loop 裁剪。

## 2. 路径白名单只做词法校验,不防符号链接
- **决策**:`PathParam` 纯词法(绝对路径、拒 `..`、归一化后前缀匹配、前缀碰撞 `/logs-evil` 要拒),不访问文件系统。
- **取舍**:纯函数好测、SSH 到远端时同样适用(本机 canonicalPath 在远端无意义)。代价:白名单目录里的符号链接能指向目录外。
- **为什么可接受**:Agent 没有任何写工具,模型造不出软链;链接只能由运维预先放置。生产 sidecar 应在目标机侧再做 realpath 校验。
- 命令一律 argv 传递(无 shell),路径含 `;` `$()` 也只是单个参数;`tail` 加 `--` 防 `-` 开头参数。

## 3. 环境与测试小坑
- NixOS:`nix-shell` 首次拉 gradle 插件 + Maven 依赖约 2 分钟,之后秒级。`shell.nix` 固化 JDK17/kotlin/gradle,`JAVA_HOME` 指向 temurin。
- `top -H` 在 NixOS 下主线程 COMMAND 显示 `.java-wrapped`(java 被 wrapper 包装),不能用进程名判断 java;线程名靠 jstack 的 nid 对应(故 `top_threads` 追加十六进制 nid 列,省得模型自己做进制换算——模型算 hex 不可靠)。
- 测试坑:`Limits(maxOutputChars=100)` 的测试夹具会把 top 输出也截断,导致断言失败。截断上限是被测行为的一部分,夹具要显式指定。
- `jstat` 对不存在 pid 的报错是一整段 Java 栈,回给模型是噪声;目前原样截断回传,P1 评估时观察是否需要提炼首行。

## 4. Reasoning 模型默认消耗 `max_tokens`,过小会导致正文为空
- **现象**:接入 `zhipu/glm-5.3-flash` 初测时设 `max_tokens=32`,网关返 200 但 `content=""`、`finish_reason="length"`,`reasoning_tokens=31` 把额度吃满。
- **根因**:带思考链的模型(GLM-5.3-Flash / DeepSeek-R1 等)把 `reasoning_content` 计入 `completion_tokens`,思考还没结束就撞到 `max_tokens` 上限,来不及输出正文或 `tool_calls`。
- **解法**:`OpenAiLlmClient` 默认 `max_tokens=4096`(可配);`AgentLoop` 显式区分 `finish_reason=="length"` 且无正文/工具调用为 `StopReason.OUTPUT_TRUNCATED`,并记录 `reasoningTokens` 到会话 trace,避免静默返回空结论。
- **要点**:推理模型的 `max_tokens` 是「思考+输出」总预算,必须把 `reasoning_tokens` 纳入监控与成本评估。

## 5. Agent Loop 架构决策:协议隔离 + 护栏下沉到 Harness
- **协议隔离**:`Tool.spec()` 保持通用的 `input_schema`(兼容 Anthropic/MCP),由 `OpenAiLlmClient` 在发送时映射为 `tools[].function.parameters`;`AgentLoop` 只依赖 `LlmClient` 接口,换模型厂商不改 loop,且测试直接注入 `FakeLlmClient` 零网络跑完所有分支。
- **护栏不靠 Prompt**:步数(`maxSteps`)、token(`maxTotalTokens`)、耗时(`maxDurationMs`)、重复调用拦截(`maxDuplicateCalls`,序列化前按 key 排序归一化参数)全部硬编码在 `AgentLoop`;参数 JSON 解析失败不崩循环,以 `ERROR:` 回传给模型自纠。
- **实测验证(`01-deadlock`)**:`glm-5.3-flash` 3 步 3 次工具调用定位根因(L0 `top_threads` + `jstat_gc` 排除 CPU/GC → L1 `jstack` 命中死锁),`jstack` 输出确实触发了 8000 字符截断,`hoistDeadlock` 置顶生效;总计 9992 tokens(reasoning 503)、41.8s。

## 6. 满堆 OOM 抖动的 JVM 不响应 `SIGTERM`,导致评估脚本卡死
- **现象**:`eval/run_eval.sh` 跑完 `02-heap-leak`(运行约 4 分钟,`-Xmx128m` 满堆保压)后执行 `kill $PID && wait $PID`,JVM 进程 15 分钟未退出,脚本卡死在清理阶段。
- **根因**:`HeapLeak` 持续泄漏并吞掉 OOM 维持老年代近 100% 满载,GC 线程打满;收到 `SIGTERM` 时 JVM 尝试拉起 Shutdown Hook 线程做优雅停机,因无法分配内存而卡住。
- **解法**:评估脚本清理故障进程改为先 `kill` 等 1s,未退则 `kill -9` 强杀。
- **要点**:OOM 假死的 JVM 上 `kill -15` 常常失效。需要先留 `jstack`/`jmap` 现场,再 `kill -9`,不能指望优雅停机。

## 7. Agent 过度保守拒调 L2 + 幻觉不存在的日志路径
- **现象**:首次跑 `02-heap-leak` 评估时:
  1. Agent 从工具描述里的白名单目录 `/tmp/jvm-diag/logs` 猜出 `/tmp/jvm-diag/logs/gc.log` 调用 `gc_log_tail`,触发 `tail: No such file or directory`(验证了工具层错误回传机制有效,模型未死循环);
  2. Agent 看到 `jstat_gc` 老年代持续飙升(42MB→65MB)且 `jstack` 有 `cache-loader`,但**未调用 L2 `heap_histo`**,直接下结论,导致缺失具体泄漏对象类型(`OrderSnapshot` / `[B`),判对失败;同期 `workflow_with_l2` 调了 `heap_histo` 全项判对。
- **根因**:System Prompt 写"仅在必要时调用 L2(需人工确认)",模型过度自我审查,替人类提前否决了 L2,而不是在有 L0 证据支撑后发起申请交给 `Approver` 决定。
- **解法**:在 System Prompt 中明确两条边界:① 不猜测用户未提供的文件路径;② L2 由人工审批把关,当 L0 证据指向堆泄漏且需定位具体泄漏类时,应主动发起 `heap_histo` 请求(若人工拒绝再降级)。
- **额外观察**:`workflow_with_l2` 与 `workflow_no_l2` 一次性塞入 4~5 个工具全量输出后,`glm-5.3-flash` 单次 `reasoning_tokens` 飙到 3100~3300,`completion_tokens` 撞满 `4096` 上限且单步耗时 ~89s;二次评估时 `02-heap-leak` 的两个 workflow 更直接因单次请求耗时 ~90~100s 触发上游请求错误(`stopReason=ERROR`)。可见大上下文一次性喂给推理模型并不一定比多步聚焦的 Agent 更快更稳。

## 8. 评估复盘:Agent 到底值不值?(基于 3 用例实测)
- **结果**(详见 `eval/results.md`,模型 `zhipu/glm-5.3-flash`):
  - **Agent**:**3/3 全对**,**0 次误触 L2**,平均 3.3 步、4.7 次工具、15,017 tokens、65.7s。只在 `02-heap-leak` 看到老年代飙升后精准申请 `heap_histo`,`01-deadlock` 与 `03-cpu-spin` 均未触发 Full GC。
  - **Workflow(no L2,保守策略)**:0 误触 L2,死锁与 CPU 死循环 1 步搞定(~7k tokens),但**无法定位堆泄漏具体对象**(`02-heap-leak` 必败)。
  - **Workflow(with L2,激进全采)**:把所有故障一视同仁跑 `jmap -histo:live`,**3 个场景里 2 次误触 Full GC**,生产环境爆炸半径不可接受。
- **结论**:路径固定、工具全无副作用的场景选 Workflow(省 ~50% token、1 步完成);而线上故障类型开放、且重操作(L2)有 STW 副作用时,Agent 的核心价值不是"比 Workflow 更省 token",而是**按观察结果动态升级取证级别,把高危操作精准收敛到真正需要的场景**。
- **工程小坑**:`eval` 首次出现 `stopReason=ERROR` 时没把失败原因写入 JSONL,已补记 `error` 字段,保证任何失败都可回溯。

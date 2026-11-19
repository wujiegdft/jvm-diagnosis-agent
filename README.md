# JVM 故障诊断 Agent

从零手写的最小 Agent Harness,场景:自主排查 JVM 故障(OOM、死锁、CPU 飙高、Full GC 等)。

## 为什么做

- **目标**:从零手写一个最小 Agent Harness,搞清楚 harness 各层如何构成。
- **记录**:怎么做、遇到什么问题、怎么解决,见 `docs/lessons.md`。

## 为什么选 JVM 故障诊断

选题理由:数据可以自己造,且有可验证信号。

- **痛点真实**:日常排查线上问题,大量时间花在重复的 jstack / jmap / jstat 取证与关联分析。
- **数据自造**:自己写故障程序,**根因真值已知**,不依赖外部数据。
- **可评估**:定位结论是否正确、步数、token 成本。
- **为什么是 Agent 而不是 Workflow**:故障类型开放,下一步诊断动作取决于上一步观察结果,应由模型决策。
- **反例**:确定性强、路径固定的场景走 workflow,长尾开放场景才值得 agent。

## 架构:三层,各层可替换

```
Agent Loop ──(Tool 接口层)──▶ 命令执行层 ──(传输层)──▶ 目标机 JVM
              自定义 / MCP        白名单+审计+限流     Local / SSH / 常驻进程
```

- **MCP** 是 Agent 与工具之间的协议,管"怎么调用",**不解决安全与传输**。
- **SSH / 常驻进程** 是传输与执行方式,管"怎么到达目标机"。
- **权限控制** 做在命令执行层,与传输无关。

### 权限:工具即白名单,不给 bash

黑名单拦命令不可靠(`;`、管道、`$()`、重定向都能绕过)。只暴露带类型的工具:

```
jstack(pid) / jstat_gc(pid, interval) / jcmd_vm_flags(pid)
gc_log_tail(path, lines) / heap_histo(pid, top_n) / top_threads(pid) ...
```

- 参数强校验:pid 必须整数,path 必须在白名单目录内。
- 命令由**代码拼接**,不由模型拼接,杜绝注入。
- 单次输出截断、单会话步数/耗时预算、全量审计日志。

| 级别 | 工具示例 | 策略 |
|---|---|---|
| L0 只读轻量 | `jstat`、`jcmd VM.flags`、读 GC 日志、`top -H` | 自动放行 |
| L1 短暂停顿 | `jstack`(进 safepoint) | 自动放行,限频 |
| L2 重操作 | `jmap -histo:live`(**触发 Full GC**)、`jmap -dump` | **人工确认** |
| L3 变更 | 改参数、重启 | **不提供** |

### 传输方案对比

| | SSH 直连 | 目标机常驻进程 | MCP |
|---|---|---|---|
| 本质 | 传输方式 | 传输+执行方式 | 协议层 |
| 部署成本 | 零 | 每台机器部署 | 取决于后端 |
| 安全边界 | 弱(给的是 shell) | **强(无 shell,只有类型化 API)** | 不解决安全 |
| 输出裁剪 | Agent 侧 | 源头做,省带宽 | 同左 |
| 凭证 | Agent 机持 SSH 密钥,风险集中 | 只需 token | 需自做认证 |
| 价值 | 最快上手 | 生产级 | 工具可被 Claude Code / Cursor 复用 |

**终态**:目标机 sidecar 直接暴露为 MCP Server(streamable HTTP + 认证),内部是类型化白名单工具,Agent 只是 MCP Client。

### 分阶段

1. **P1**:`DiagnosticTools` 接口 + `Executor` 接口 + `LocalExecutor`。故障程序跑本机,最快闭环,评估在此阶段完成。
2. **P2**:加 `SshExecutor`,验证同一套工具换传输层。
3. **P3**:工具层包装为 MCP Server,Claude Code 也能调用。
4. **常驻进程**:不实现。生产环境更适合 sidecar + MCP:无 shell、强审计、源头裁剪。

## 故障用例(`fault-cases/`,根因真值已知)

| # | 场景 | 主要证据 |
|---|---|---|
| 1 | 死锁 | jstack: deadlock |
| 2 | 堆内存泄漏(静态 Map) | histo、jstat 老年代持续增长 |
| 3 | Metaspace 泄漏(动态类生成) | jstat -gc / VM.metaspace |
| 4 | 直接内存泄漏 | RSS 增长而堆正常、NMT |
| 5 | 线程池打满 | jstack 大量线程阻塞/排队 |
| 6 | CPU 死循环 | top -H + jstack 按 nid 对应 |
| 7 | Full GC 频繁 | GC 日志 / jstat |

先做 3 个(死锁、堆泄漏、CPU 死循环),跑通再扩。

## 评估

- 指标:根因定位是否正确、步数、token、耗时、重复/无效调用次数。
- 对照:同样的证据用固定 workflow(脚本依次取证后一次性交给 LLM)对比 agent,回答"agent 到底值不值"。

## 要踩并记录的坑

- jstack / histo 输出巨大 → 上下文裁剪策略
- 模型死循环、重复调同一工具 → 步数上限 + 重复检测
- 工具报错后能否自我纠正 → 错误信息如何回传
- 幻觉出不存在的 pid / 路径 → 参数校验 + 明确报错
- 成本与延迟 vs workflow
- 重操作的人工确认(权限边界)

每踩一个坑,写进 `docs/lessons.md`:现象 / 根因 / 解法。

## JVM 相关注意事项

- `jstack` / `jcmd` / `jmap` 依赖 attach 机制,**必须与目标 JVM 同一 OS 用户**,不能简单用"只读用户",靠白名单限权。
- JDK 8 目标机必须是 JDK 而非 JRE。
- 容器场景需 `kubectl exec` / `nsenter` → `Executor` 必须抽象。
- 成熟替代:Arthas、jattach,可作为一个工具后端。
- `jmap -histo:live` 会触发 Full GC;`jstack` 需进 safepoint,有停顿。

## 设计取舍

- **为什么不是 workflow**:故障类型开放,诊断路径取决于上一步观察。
- **和 Arthas 的关系**:Arthas 是工具,Agent 是编排者,可把 Arthas 作为一个工具后端。
- **安全怎么做**:工具白名单 + 参数校验 + 分级审批 + 审计,不靠命令黑名单。
- **为什么不用 LangChain / Spring AI**:目的是理解 harness 怎么做,自己写。

## 技术选型

- 语言:Kotlin(Agent),故障程序用 Java 单文件。
- 模型 API:直接 HTTP 调用,**不用任何 Agent 框架**。具体厂商/协议待定。
- 环境:NixOS,默认无 JDK,用 `nix-shell -p` 临时获取。

## 当前进度

- [x] P1 工具层:Executor / LocalExecutor / DiagnosticTools(`jstack`、`jstat_gc`、`top_threads`、`gc_log_tail`、`jcmd_vm_flags`、L2 `heap_histo`)/ 风险分级+审批钩子 / 截断 / trace
- [x] P1 agent loop:LlmClient(OpenAI 兼容直调 JDK HttpClient)+ AgentLoop(步数/token/耗时/重复调用护栏)+ 会话 trace
- [x] 故障程序 ×3:`01-deadlock`、`02-heap-leak`、`03-cpu-spin`(均含 `expected.md` 真值)
- [x] 评估脚本与实测对照(`eval/run_eval.sh`、`eval/results.md`):Agent **3/3 全对、0 误触 L2**(平均 3.3 步 / 15k tokens / 65.7s) vs 固定 Workflow(无 L2 漏堆泄漏;含 L2 在死锁与 CPU 场景 2 次误触 Full GC)
- [ ] P2 SshExecutor
- [ ] P3 MCP Server
- [x] docs/lessons.md 复盘(已记 8 条真实踩坑,持续更新)

## 开发

```bash
nix-shell                      # JDK17 + kotlin + gradle
gradle test                    # 单元测试(Executor 与 LlmClient 均用假实现)
gradle installDist
build/install/jvm-diagnosis-agent/bin/jvm-diagnosis-agent list                                     # 列出白名单工具
build/install/jvm-diagnosis-agent/bin/jvm-diagnosis-agent call jstack pid=1234                     # 手动驱动单个工具
LLM_API_KEY=... build/install/jvm-diagnosis-agent/bin/jvm-diagnosis-agent diagnose "PID 1234 卡死"  # Agent 自主诊断
LLM_API_KEY=... bash eval/run_eval.sh                                                              # 全量 3 用例 Agent vs Workflow 对照评估
java fault-cases/01-deadlock/Deadlock.java                                                         # 启动故障程序,打印 pid
```

环境变量:`LLM_API_KEY`(必填)、`LLM_BASE_URL`(默认 `http://127.0.0.1:3430/v1`)、`LLM_MODEL`(默认 `zhipu/glm-5.3-flash`)、`LLM_MAX_TOKENS`(默认 `4096`)。

代码:`src/main/kotlin/jvmdiag/` — `Executor`/`LocalExecutor`(传输)、`Params`(类型+校验)、`Tool`/`Tools`(白名单工具)、`DiagnosticTools`(校验→限频→审批→执行→截断→trace)、`LlmClient`(OpenAI 兼容 HTTP 客户端)、`AgentLoop`(多步决策+预算/重复拦截)、`WorkflowBaseline`/`Eval`(对照评估)、`Trace`(工具与会话 JSONL 审计)。

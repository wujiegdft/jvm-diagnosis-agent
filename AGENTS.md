# AGENTS.md

## 项目性质

从零手写的最小 Agent Harness(JVM 故障诊断)。目的是学习与实践 harness 构造,不是生产系统。背景、架构、决策见 `README.md`,先读它。

## 协作风格

- 始终中文,极简,只给重点。
- 每个设计决策都要记录:**为什么这么做 / 备选方案 / 取舍**。重要决策与踩坑写入 `docs/lessons.md`(现象 / 根因 / 解法)。
- 遇到有意义的坑,先停下记录再继续,别悄悄修掉。

## 硬约束

1. **不用 Agent 框架**:禁止 LangChain、Spring AI、LangChain4j 等。模型调用直接走 HTTP。目的是讲清 harness 如何构建。
2. **工具即白名单,不给 bash**:Agent 只能调用带类型、带参数校验的工具;命令由代码拼接,**不由模型拼接**;禁止提供通用 shell 工具。
3. **分层可替换**:`Agent Loop → DiagnosticTools → Executor`。传输(Local / SSH / 后续 MCP)只替换 `Executor`,工具层与 loop 不感知。
4. **风险分级**:L0 只读自动放行;L1 限频;L2 重操作(`jmap -histo:live`、`jmap -dump`)必须人工确认;L3 变更类不提供。
5. **所有输出必须截断**,单次工具输出有上限;会话有步数、token、耗时预算。
6. **全量审计**:每次工具调用(参数、结果摘要、耗时、审批结果)落 trace。
7. **故障程序根因真值写在代码旁**(如 `expected.md`),评估靠它判对错。

## 代码原则

- 简单直接优先;抽象只在明显提升可读性/可维护性/可扩展性时引入。
- 易测试:纯函数、显式依赖、清晰边界;少依赖 mock。`Executor` 是接口,测试可直接注入假实现。
- **不写兼容旧逻辑、历史行为或 fallback 代码**,除非用户明确要求。当前实现就是唯一路径。
- 参数校验失败、工具报错 → 明确返回错误信息给模型,不吞掉、不静默降级。

## 技术栈与环境

- Agent:Kotlin。故障程序:Java 单文件,放 `fault-cases/<编号-名称>/`,每个目录含 `expected.md`(根因 + 关键证据)。
- 默认 JDK 17(故障程序尽量不依赖新语法,方便日后在 JDK 8 复现)。
- 机器是 NixOS,**默认没有 java / kotlinc / gradle / jcmd**,用 `nix-shell -p` 获取(如 `temurin-bin.jdk-17`、`kotlin`、`gradle`)。需要的话在项目根加 `shell.nix` 固化环境。
- JVM attach 工具(jstack/jcmd/jmap)要求与目标 JVM 同一 OS 用户。

## 目录约定(建议)

```
README.md / AGENTS.md
docs/lessons.md        # 踩坑复盘
fault-cases/           # 故障程序 + expected.md
src/                   # Kotlin: agent loop / tools / executor / trace
eval/                  # 评估脚本与结果
```

## 当前任务顺序

1. P1 骨架:`Executor`、`DiagnosticTools`、agent loop、trace。
2. 故障程序 ×3:死锁、堆泄漏、CPU 死循环。
3. 评估:agent vs 固定 workflow 对照(准确率 / 步数 / token / 耗时)。
4. P2:`SshExecutor`。
5. P3:工具层包装为 MCP Server。

## 待用户决策

- 模型厂商与协议(Anthropic Messages / OpenAI 兼容)及 API key 提供方式(用 `request_secret`,不要写进仓库)。

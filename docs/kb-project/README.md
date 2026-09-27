# DeepResearch 项目实现知识包

> 信息边界：这些文档不包含用户 Bearer Token、JWT 签名密钥、数据库口令、API Key、联系方式、本机绝对路径或模型思维链。
> 快照日期：2026-08-26
> 文档版本：KB-PROJECT-1.1
> 证据基线：Java `0.0.1-SNAPSHOT`、Python workflow sidecar `0.1.0`、Flyway V7–V11
> 用途：为项目知识库、Agent 评测和面试讲解提供经过脱敏、可公开引用的实现事实

## 阅读规则

本目录只记录可以从当前仓库代码或测试核对的事实。每个主题都标注：

- **状态**：`已实现并有分层测试`、`已实现但待在线验收` 或 `未实现`；
- **代码证据**：实现所在的仓库相对路径和符号；
- **测试证据**：直接覆盖该事实的测试文件和测试名；
- **限制**：该证据不能推出什么结论。

这些文档不包含用户 Bearer Token、JWT 签名密钥、数据库口令、API Key、联系方式、本机绝对路径或模型思维链。文档中的版本号是仓库构建版本，不代表已发布的生产版本。

## 文档索引

1. [架构与信任边界](01-architecture-and-trust-boundaries.md)
2. [LangGraph checkpoint 与崩溃恢复](02-checkpoint-and-crash-recovery.md)
3. [claim、lease、heartbeat 与 fencing](03-claim-lease-and-fencing.md)
4. [SSE 持久事件与断线重放](04-sse-durable-replay.md)
5. [工具 receipt 与未知结果](05-tool-receipt-and-unknown-result.md)
6. [已验证、待验收与未实现边界](06-verification-boundaries.md)
7. [Agent 检索事实卡](07-agent-evaluation-fact-cards.md)

第 7 份文档只把 01–06 中已经核对的事实改写成单主题、短段落的检索入口，用来减少一个事实跨多个 chunk 后被截断的概率。它不新增项目能力，也不能替代主题文档中的代码、测试和限制证据。

## 三类持久化对象不要混淆

| 对象 | 解决的问题 | 当前事实来源 |
|---|---|---|
| LangGraph checkpoint | 图执行到哪个节点、恢复时下一步是什么 | Python sidecar 与 `langgraph` schema |
| `agent_workflow_event` | 用户可见的安全进度、SSE 游标与审计轨迹 | Java/Python 共用的业务事件表 |
| `agent_workflow_tool_receipt` | 某个确定性工具调用是否开始、完成或处于模糊态 | Python receipt + Java MCP execution receipt |

checkpoint 不能自动提供工具 exactly-once；event log 也不能代替 checkpoint。当前设计把三者分开，并通过 `run_id`、确定性 `call_id` 和当前 `claim_token` 关联。

## 可引用的总括表述

DeepResearch 当前采用 Java 安全控制面与 Python/LangGraph 图执行面的双运行时架构。Java 持有公网 REST/SSE、用户归属、任务状态、授权、MCP 工具和最终终态；Python 只在私网 claim 任务，执行 Planner、并行 Worker、Reviewer、Synthesizer，并用 PostgreSQL checkpoint 保存节点状态。claim token、lease、数据库条件写和触发器阻止旧 runner 写入；确定性工具 call ID 与双层 receipt 能重放已完成结果，对无法判断是否已执行的旧 `EXECUTING` 调用返回 `MCP_RESULT_UNKNOWN` 并拒绝盲目重试。

该表述的限制是：仓库已有分层测试和真实 PostgreSQL/Testcontainers 证据，但真实外部模型参与的进程 kill/restart、浏览器 SSE 断线全链、容量与生产高可用仍待单独验收，因此不能宣称 exactly-once、零丢失或生产级 SLA。

# DeepResearch Agent 检索事实卡

> 状态：对既有实现事实的检索友好重述，不新增能力声明
> 事实来源：本目录 01–06 文档及其中列出的代码、测试证据
> 快照：2026-08-26

## 使用边界

本文件把容易跨 chunk 的项目事实压缩为一问一答式事实卡，供 `kb_search`、Reviewer 和引用评测使用。事实卡不是独立的生产证明；代码位置、测试证据和不能越界的表述仍以 01–06 文档为准。若事实卡与代码或原始主题文档发生冲突，应先修正文档并重新导入，不能为了提高评测分数保留失真的答案。

## Java 控制面与 Python LangGraph 执行面

DeepResearch 的 Java/Spring Boot 是公网安全控制面，负责 workflow 创建、用户归属、任务状态、持久事件、授权、取消、REST/SSE、MCP 工具入口和 fenced finalize 最终终态；Python/LangGraph 是私网图执行面，负责 claim 任务并执行 Planner、并行 Worker、Reviewer、Synthesizer，以及用 PostgreSQL checkpoint 保存节点状态。该职责分离已有分层测试，但不是跨 Java/Python 的分布式事务证明。

## Compose workflow 端口与云上网络隔离

本地 Compose 中只有 Java `app` 映射宿主机 8080，Python `workflow` 没有宿主机端口映射，只通过 Compose 网络访问 Java 和 PostgreSQL；这不能证明云上 NetworkPolicy、防火墙或零信任网络已经实现。本地“没有宿主机端口”和云上“已完成网络隔离”是两个不同结论。

## Python 为什么不能写 SUCCEEDED

Python sidecar 使用受限数据库角色，只能在当前 claim 与有效 lease 下写非终态进度、事件、预算和 receipt；数据库触发器拒绝它直接写 `SUCCEEDED` 等终态。最终终态必须由 Java 控制面的 fenced `finalize` 写入，这是最小权限与 stale-claim 防护的一部分；它不表示 Java 控制面本身已经完成生产凭据和运维审计。

## Durable Workflow 的 Worker 工具白名单

Durable Workflow 当前只允许 Worker 调用 `kb_search`、`web_search`、`calculator` 三种只读工具；不支持 `file_read`、文件操作、写操作、付费操作或管理员工具。这里的“只读”描述 workflow 能力边界，不表示外部网页或模型调用没有延迟与费用。

## LangGraph checkpoint schema 与 thread_id

LangGraph checkpoint 存在独立的 `langgraph` schema；运行时用 `graph_thread_id` 作为 `thread_id`，Java 创建 run 时默认令 `graph_thread_id` 等于 `run_id`，因此默认关系是 `thread_id = run_id`。这是当前默认映射，不应外推为 LangGraph 框架要求所有项目都这样设置。

## 未完成与已完成 checkpoint 的恢复分支

runner 调用 `aget_state` 后，若 checkpoint 的 `snapshot.next` 非空，就以 `None` 恢复未完成图；若 checkpoint 已完成但 Java `finalize` 响应可能丢失，就读取最终 state 并重试幂等 `finalize`，不重复模型合成。checkpoint 只记录图状态，不能单独保证远端副作用 exactly-once。

## durability=sync 的保证边界

`durability="sync"` 保证 LangGraph 在节点或超步边界同步持久化图状态；它不能把第三方模型调用或远端工具动作纳入同一个数据库事务。它增强的是 checkpoint 的持久化时机，不是外部调用的 exactly-once 保证。

## advisory lock 与 fencing 的不同职责

PostgreSQL session advisory lock 按 `run_id` 串行化同一 run 的图执行，接管者要等待旧执行连接释放锁并再次检查 claim；advisory lock 不能替代 fencing。真正阻止 stale claim 写进度、预算、事件、receipt、授权和终态的是每次写操作携带当前 claim token 并校验 lease 的 fencing 条件。

## DeepResearch 的 SSE Last-Event-ID 契约

在 DeepResearch 项目中，SSE 的 `Last-Event-ID` 值采用 `<runId>:<eventId>`，这是本项目的游标契约；服务端验证 run 前缀和数字游标后，从 PostgreSQL 查询 `event_id > cursor`，并按 `event_id` 升序重放后续事件。该事实卡回答项目实现，不把 `<runId>:<eventId>` 冒充 WHATWG 对所有 SSE 应用规定的通用格式。

## 演示页的 SSE 去重与验证边界

`demo.html` 在当前页面生命周期内用事件 ID 的 `Map` 去重，只允许游标前进，并在重连请求中携带 `Last-Event-ID`；服务端按 `event_id > cursor` 重放。页面完全刷新会重建内存 `Map`，正确性仍依赖保存的游标和服务端重放；当前没有 Playwright 或真实浏览器故障注入测试证明任意断点都不丢不重。

## SSE 空闲心跳与连接关闭

workflow SSE 在连续 15 秒没有业务事件时发送 comment heartbeat；run 进入终态且数据库没有待发送事件后关闭连接，单个 emitter 的超时为 3 分钟。SSE heartbeat 用于连接保活，与 runner 的 10 秒 lease heartbeat 不是同一种机制。

## 真实外部模型 kill/restart 的验证边界

DeepResearch 没有完成真实外部模型参与的进程 kill/restart 全链验收；准确的简历表述是“checkpoint、receipt、claim fencing 恢复机制已实现并通过分层测试，在线 kill/restart 待验收”。不能把单元测试、PostgreSQL/Testcontainers 测试或单条在线烟测写成真实 provider 的恢复成功率。

## HITL、Kafka、Temporal 与 Kubernetes 范围

当前项目没有实现 HITL interrupt、Kafka、Temporal 或完整 Kubernetes 集群，也没有写工具和高风险审批流。它们属于后续路线，不得表述成已实现、已上线 Kubernetes 或已具备生产集群经验。

## 引用契约能证明与不能证明的内容

引用契约能证明正文中的引用编号可以确定映射到公开 citations 数组中的 `source ID`，并拒绝未知来源、越界或需要猜测的映射；引用契约不能自动证明来源权威性，也不能自动证明事实真实性。来源质量和论断支持关系仍需要检索质量、Reviewer、grounding 评测或人工审核。

## 模型重试、UNKNOWN 与预算

LangChain/OpenAI SDK 明确设置 `max_retries=0`，使一个数据库 reservation 对应至多一次由应用发起的 provider 请求；应用默认最多 2 个持久化 attempt。失败或结果不确定的 attempt 先标记为 `UNKNOWN`，并保守计入模型调用次数预算，但未知 token、准确成本和 provider 是否重复计费不能由本地记录恢复。

## 旧 claim 工具调用的未知结果

旧 claim 遗留的 Java `EXECUTING` receipt 返回稳定错误码 `MCP_RESULT_UNKNOWN`，系统不会自动重跑该工具。这个 at-most-once 优先选择降低了重复执行风险，但会牺牲结果可用性；它不是 exactly-once，也不能承诺零重复或零丢失。

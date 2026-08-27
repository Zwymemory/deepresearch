# SSE 持久事件与断线重放

> 状态：持久事件、游标协议和演示页自动重连已实现；真实浏览器故障注入全链待验收
> 版本：Java `0.0.1-SNAPSHOT`；Flyway V7–V11
> 快照：2026-08-26

## 1. 持久事件不是内存消息

**事实 S1｜状态：已实现并有数据库集成测试。** workflow 安全事件先写入 PostgreSQL `agent_workflow_event`，每条记录有递增 `event_id`、确定性 `event_key`、角色、任务、类型和受大小限制的 `safe_payload`。

- 代码证据：Flyway V7 的事件表；V8 的 payload 大小约束和 claim 字段；Python `write_event` 与 Java `insertEvent`。
- 测试证据：`DeepResearchApplicationIT.workflowIdempotencyAndDurableEventKeysAreEnforcedByPostgres` 验证相同 `(run_id,event_key)` 只写一次且事件可查询。
- 限制：`safe_payload` 的字段由代码约定和日志卫生保证；当前没有覆盖所有事件类型的自动敏感信息扫描测试。

**事实 S2｜状态：已实现。** SSE 查询使用 `event_id > cursor`、按 `event_id ASC` 排序，每次最多读取 200 条；也就是按 `event_id` 升序重放，因此断线后能从已确认游标继续，而不是依赖 Java 进程内缓存。

- 代码证据：`WorkflowRepository.eventsAfter`、`WorkflowController.stream`。
- 测试证据：数据库集成测试验证 `eventsAfter` 可读取持久事件；没有专门的 200 条分页压力测试。
- 限制：一次查询上限不代表事件总数上限，controller 会继续轮询；大量慢客户端的容量和背压仍未压测。

## 2. Last-Event-ID 契约

**事实 S3｜状态：已实现。** SSE ID 采用 `<runId>:<eventId>`。客户端重连时发送 `Last-Event-ID`；服务端验证游标后使用 `event_id > cursor` 并按 `event_id` 升序重放后续事件。服务端还要求前缀属于当前 run，错误 run 返回冲突，负数或非法数字返回参数错误。

- 代码证据：`WorkflowDtos.Event` 的 ID 组装、`WorkflowController.parseCursor`。
- 测试证据：当前没有独立的 `WorkflowController` MVC 测试覆盖非法游标。
- 限制：这是当前 HTTP 契约的已实现代码，但在补齐控制器测试前应表述为“实现待专项回归”，而不是“所有边界均已验证”。

**事实 S4｜状态：已实现。** 每次建立事件流前先验证 run 归属，流式轮询中也持续使用同一 `userId` 执行 owned 查询；跨租户不可通过猜测 runId 读取事件。

- 代码证据：`WorkflowController.events`、`WorkflowService.eventsAfterOwned`、`WorkflowService.owned`。
- 测试证据：workflow access/service 单测覆盖跨主体授权的相邻边界；当前没有真实 SSE 跨租户请求的 MVC 集成测试。
- 限制：浏览器端保存的最近 runId 和游标不是授权凭证，服务端仍以当前认证主体为准。

## 3. 心跳、终态与浏览器去重

**事实 S5｜状态：已实现。** 当 15 秒没有业务事件时，server 发送 SSE comment heartbeat；run 进入终态且数据库中没有待发送事件后关闭连接。单个 emitter 超时时间为 3 分钟，客户端需要重连。

- 代码证据：`WorkflowController.stream`。
- 测试证据：当前没有使用虚拟时钟覆盖 15 秒 heartbeat 和 3 分钟 emitter timeout 的自动测试。
- 限制：SSE 心跳用于保持连接与检测中断，不等于 workflow runner 的 10 秒 lease heartbeat；二者是不同机制。

**事实 S6｜状态：已实现于演示页。** `demo.html` 用事件 ID 的 Map 去重，只允许游标前进；断线后使用 fetch streaming 携带 `Last-Event-ID`，按指数退避重连。

- 代码证据：`demo.html` 的 `eventKey`、`advanceEventCursor`、`appendEvent`、`consumeEventStream`、`startEventLoop`。
- 测试证据：已有 JavaScript 语法检查；没有 Playwright/浏览器故障注入测试证明任意断点都不丢不重。
- 限制：内存 Map 在页面完全刷新后会重建；正确性仍依赖保存的游标与服务器重放。网络协议通常应按至少一次交付思考，客户端必须按 ID 幂等处理。

## 4. 当前不能越界的说法

- 可以说：事件持久化、确定性 event key、Last-Event-ID 查询和前端去重/重连代码已实现。
- 不可以说：SSE 与 LangGraph checkpoint 是同一个存储或同一事务。
- 不可以说：已经完成浏览器从任意事件断开、刷新、跨实例恢复的完整自动验收。
- 不可以说：SSE 提供 exactly-once 交付；客户端仍应按事件 ID 去重。

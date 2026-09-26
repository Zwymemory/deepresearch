# 工具 receipt、未知结果与安全重试

> 状态：确定性 call ID、Python receipt、Java MCP execution receipt 已实现并有分层测试
> 版本：workflow sidecar `0.1.0`；Flyway V9、V10、V11
> 快照：2026-08-26

## 1. 为什么超时不能直接重试

HTTP 或 MCP 超时只说明调用方没有收到确定结果，不说明远端动作没有发生。若客户端盲目 POST 第二次，可能重复搜索、扣费或产生副作用。因此当前链路先生成确定性 `call_id`，把它同时作为 Python receipt 主键和 MCP `Idempotency-Key`。

**事实 R1｜状态：已实现并有测试。** `call_id` 由 `run_id`、`task_id`、tool 和 query 的规范 JSON 做 SHA-256 后生成，重试同一逻辑任务得到同一 ID。确定性 ID 只提供关联键；只有下游真正持久化并消费该键，才有去重效果。

- 代码证据：`domain.py::deterministic_call_id`；`HttpMcpToolClient._request_headers`。
- 测试证据：`test_mcp_execution_binds_run_and_task_headers`。
- 限制：确定性 ID 只提供关联键；只有下游真正持久化并消费该键，才有去重效果。

## 2. Python 与 Java 双层 receipt

**事实 R2｜状态：已实现并有测试。** Python 的 `agent_workflow_tool_receipt` 先记录 `STARTED` 和请求指纹，成功后保存 `COMPLETED` 的安全结果。相同 call ID 若参数指纹不同会以 `MCP_RECEIPT_CONFLICT` 失败。

- 代码证据：`ReceiptCachingToolClient.execute`、`PostgresWorkflowRepository.begin_tool_receipt` 与 `complete_tool_receipt`。
- 测试证据：`test_python_receipt_replay_rejects_same_call_id_with_new_arguments`。
- 限制：Python 在调用 Java 后、写 Python `COMPLETED` 前崩溃时，仅靠这一层无法判断 Java 是否已经执行。

**事实 R3｜状态：已实现并有 Java/Testcontainers 证据。** V9 在同一 receipt 行增加 Java MCP execution 状态。Java 在执行工具前原子写 `EXECUTING`，工具结果落盘后写 `COMPLETED` 与安全结果；Python sidecar 只能读取这些列，不能伪造或修改。

- 代码证据：`V9__workflow_mcp_execution_receipt.sql`、`WorkflowMcpReceiptService`、`WorkflowRepository.beginMcpToolExecution` 与 `completeMcpToolExecution`。
- 测试证据：`DeepResearchApplicationIT.javaMcpReceiptReplaysCompletedResultAndFencesStaleClaims`。
- 限制：Java receipt 与真实第三方动作仍不是单个事务；动作成功但 `COMPLETED` 落盘前崩溃仍存在模糊窗口。

## 3. 四种恢复结果

**事实 R4｜状态：已实现并有单元测试。** 恢复时按持久状态区分：

1. Java `COMPLETED`：直接重放安全结果，不再次调用工具；
2. 同一当前 claim 的 `EXECUTING`：返回 `MCP_CALL_IN_PROGRESS`；
3. 旧 claim 遗留的 `EXECUTING`：返回 `MCP_RESULT_UNKNOWN`；
4. task/tool/参数指纹不一致：返回 `MCP_RECEIPT_CONFLICT`。

- 代码证据：`WorkflowMcpReceiptService.begin`。
- 测试证据：`replaysCompletedJavaResultWithoutExecutingAgain`、`rejectsInProgressConflictAndStaleClaimWithStableCodes`、`rejectsAmbiguousExecutionFromPreviousClaimAsResultUnknown`。
- 限制：`MCP_RESULT_UNKNOWN` 是安全失败，不是成功，也不是“工具一定没执行”。

**事实 R5｜状态：已实现并有测试。** `MCP_CALL_IN_PROGRESS`、`MCP_RESULT_UNKNOWN`、stale claim 和 receipt 冲突不会被 Python 当成可缓存 evidence，也不会自动覆盖成 `COMPLETED`。

- 代码证据：`mcp.py::NON_CACHEABLE_RECEIPT_CODES`、`ReceiptCachingToolClient.execute`、graph Worker 对 `ToolReceiptStateError` 的处理。
- 测试证据：`test_java_receipt_control_state_never_completes_python_cache`、`test_graph_preserves_java_receipt_state_without_caching_evidence`。
- 限制：当某个 Worker 因结果未知没有可用证据时，Reviewer 可能要求一次修订或最终返回证据不足；系统不会编造工具结果。

## 4. at-most-once 优先，而不是 exactly-once

**事实 R6｜状态：设计已实现，边界明确。** 当前只读工具链选择 at-most-once 优先：模糊态宁可返回未知，也不自动重复动作。这降低重复执行风险，但可能牺牲结果可用性。

- 代码证据：Java `WorkflowMcpReceiptService` 对旧 claim `EXECUTING` 返回 `MCP_RESULT_UNKNOWN`；Python 将该错误列为不可缓存控制状态。
- 测试证据：`WorkflowMcpReceiptServiceTest.rejectsAmbiguousExecutionFromPreviousClaimAsResultUnknown`。
- 限制：不能宣称 exactly-once、零重复或零丢失。若未来加入写工具，需要业务资源版本、outbox/inbox、补偿或人工对账，而不能只复用当前只读 receipt。

## 5. 与模型调用未知态的区别

模型调用使用 V10 `agent_workflow_budget_reservation` 的 `RESERVED/SETTLED/UNKNOWN` 记录。旧 claim 的未结算模型调用转为 `UNKNOWN` 并保守计入调用次数；工具调用则优先复用确定性 receipt，并对 Java `EXECUTING` 模糊态拒绝重跑。二者都承认网络中的“请求可能已被接受但本地没有结果”，但恢复策略不同。

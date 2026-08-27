# LangGraph checkpoint 与崩溃恢复

> 状态：持久化与恢复分支已实现并有分层测试；真实外部模型 kill/restart 全链待验收
> 版本：workflow sidecar `0.1.0`；LangGraph 1.2.10；langgraph-checkpoint-postgres 3.1.1
> 快照：2026-08-26

## 1. checkpoint 如何落盘

**事实 C1｜状态：已实现并有真实 PostgreSQL 测试。** sidecar 使用 `AsyncPostgresSaver`，checkpoint 放在独立 `langgraph` schema（即项目所称的 langgraph schema）；运行时以 `graph_thread_id` 作为 `thread_id`，Java 创建 run 时默认让它等于 `run_id`。

- 代码证据：`workflow-service/src/deepresearch_workflow/app.py` 的 saver 初始化；`runner.py::_execute`；`WorkflowService.create`。
- 测试证据：`test_langgraph_sync_checkpoint_is_persisted_and_readable` 会执行图并查询 `langgraph.checkpoints`。
- 限制：该测试证明 checkpoint 可写可读，不等于完整 Java、外部模型、MCP 和浏览器在进程 kill 后已经端到端恢复。

**事实 C2｜状态：已实现并有测试。** 图调用使用 `durability="sync"`，在 LangGraph 节点/超步边界同步持久化状态；状态包括计划、任务、证据、usage、review、修订轮次和最终结果，不包含用户 access token。

- 代码证据：`WorkflowRunner._execute` 的 `graph.ainvoke(..., durability="sync")`；`domain.py::WorkflowState`。
- 测试证据：`test_langgraph_sync_checkpoint_is_persisted_and_readable`；`test_graph_fans_out_validates_citations_and_succeeds`。
- 限制：同步 checkpoint 只保证框架状态边界，不能把第三方模型或远端工具动作纳入同一个数据库事务。

**事实 C3｜状态：已实现。** checkpoint 反序列化启用严格 msgpack，并显式传入 LangGraph 的安全类型 allowlist。

- 代码证据：`settings.py::harden_langgraph_deserialization`；`app.py` 创建 `JsonPlusSerializer(allowed_msgpack_modules=SAFE_MSGPACK_TYPES)`。
- 测试证据：当前没有专门构造恶意 checkpoint payload 的安全测试。
- 限制：allowlist 降低宽松反序列化风险，但不能替代数据库访问控制、备份加密、schema 迁移和旧 checkpoint 兼容测试。

## 2. runner 如何决定恢复位置

**事实 C4｜状态：已实现并有单元测试。** runner 在执行前调用 `aget_state`。没有历史值时传入初始 state；有 checkpoint 且 `snapshot.next` 非空时以 `None` 恢复未完成图；checkpoint 已完成但 Java finalize 响应可能丢失时，直接读取最终 state 并重试幂等 finalize，而不重复模型合成。

- 代码证据：`WorkflowRunner._execute`。
- 测试证据：`test_runner_keeps_nonterminal_finalizing_until_java_finalize`、`test_runner_finalizes_citation_contract_failure_without_reclassifying_it`。
- 限制：完成态 finalize 重试依赖 Java 的 claim 与请求指纹契约；超过 run deadline 后只允许已经完成的图状态走 finalize，不允许重新开始未完成图。

**事实 C5｜状态：已实现并有测试。** 恢复时会写入确定性 `RUN_RESUMED` 安全事件；event key 带 claim epoch，避免同一 claim 重复写相同恢复事件。

- 代码证据：`WorkflowRunner._execute` 中 `runner:resumed:<claim_epoch>`。
- 测试证据：Harness 的 `requireDurableResume` 能检查恢复事件；当前没有真实进程重启后专门断言该事件的端到端测试。
- 限制：看到 `RUN_RESUMED` 只证明 runner 读取过 checkpoint，不证明每个远端副作用都已安全恢复。

## 3. checkpoint 与外部调用之间的崩溃窗口

**事实 C6｜状态：已实现并有分层测试。** 模型调用在真正请求 provider 前，按确定性 operation key 和 attempt 在 PostgreSQL 预占。已经 `SETTLED` 的 typed 结果可重放；旧 claim 留下的 `RESERVED` 会转成 `UNKNOWN` 并继续计入调用预算，再决定是否允许新尝试。同一当前 claim 的 provider 失败也先把该 attempt 持久化为 `UNKNOWN`，然后才可能创建下一条 attempt。

- 代码证据：`PostgresWorkflowRepository.reserve_model_call`、`mark_model_call_unknown`、`settle_model_call`；`DurableResearchGraph._budgeted_model_call`；Flyway V10。
- 测试证据：`test_graph_replays_settled_typed_model_result_after_checkpoint_gap`、`test_model_retry_marks_unknown_before_new_attempt_on_same_claim`、`test_durable_budget_recovery_charges_unknown_model_and_reuses_tool_call`、`test_runner_reconciles_unknown_durable_attempts_before_finalize`。
- 限制：`UNKNOWN` 模型请求可能在 provider 侧已经计费或完成；系统只把它保守计入模型调用次数预算，未知的 token、成本和 provider 真实账单无法在本地恢复，也不能证明 provider 账单不会重复。

**事实 C6.1｜状态：已实现并有单元测试。** 当前 sidecar 默认 `MODEL_MAX_ATTEMPTS=2`，即默认最多 2 个持久化 attempt，配置允许 1–3；只有 timeout、rate limit、schema 失败或被分类为瞬态的 provider/transport 错误才会有限重试。第一次可重试失败发出安全事件 `MODEL_RETRY_SCHEDULED`，最终失败发出 `MODEL_CALL_FAILED`，事件只保存 operation、attempt、稳定 reason code 和 retryable，不保存 prompt 或原始响应。这个重试上限属于 sidecar 启动配置，并未写入每个 run 的不可变 `RunBudget` 快照；总模型调用仍受持久化的 `maxModelCalls` 约束。

- 代码证据：`settings.py` 的 `model_max_attempts=2` 与 `model_retry_backoff_seconds=0.5`；`model.py::classify_model_failure`；`graph.py::_emit_model_attempt_event`。
- 测试证据：`test_reviewer_retries_schema_failure_with_durable_attempts`、`test_model_timeout_retry_exhaustion_is_safe_and_finite`、`test_planner_wraps_provider_failure_with_safe_operation_context`。
- 限制：默认 2 次是可靠性与成本的当前取舍，不是对所有 provider 和任务的最优值；非幂等外部操作不能照搬模型重试策略。

**事实 C6.2｜状态：已实现并有单元测试。** LangChain/OpenAI SDK 的 `max_retries` 被显式设为 0，使一次数据库 reservation 对应至多一次由应用发起的 provider 请求；重试只由上述持久 attempt 状态机控制。

- 代码证据：`OpenAIWorkflowModel.__init__`。
- 测试证据：`workflow-service/tests/test_model.py` 对普通 provider 与 DeepSeek 配置都断言 `max_retries == 0`。
- 限制：关闭 SDK 内部重试提高计数可解释性，但网络中仍可能出现“provider 已接受、客户端未收到”的未知结果；数据库 attempt 计数不等于 provider 最终账单明细。

**事实 C7｜状态：已实现并有分层测试。** 工具调用使用确定性 `call_id` 和 receipt。Python 已完成 receipt 或 Java 已完成 MCP execution receipt 可以重放；模糊态拒绝盲目重试。

- 代码证据：`domain.py::deterministic_call_id`、`mcp.py::ReceiptCachingToolClient`、`WorkflowMcpReceiptService`。
- 测试证据：`test_durable_budget_recovery_charges_unknown_model_and_reuses_tool_call`、`WorkflowMcpReceiptServiceTest.replaysCompletedJavaResultWithoutExecutingAgain`。
- 限制：工具动作完成但 Java receipt 落盘前崩溃时，系统只能返回结果未知，不能凭 checkpoint 重建丢失结果。

## 4. 当前不能越界的说法

- 可以说：节点 checkpoint、恢复分支、已结算模型结果重放、claim fencing 和工具 receipt 已实现，并有单元及 PostgreSQL/Testcontainers 证据。
- 不可以说：已经完成真实 DeepSeek/外部 provider 的全链 kill/restart 验收。
- 不可以说：checkpoint 自动保证 exactly-once。
- 不可以说：任意崩溃点都不会丢失结果。
- 不可以说：持久 attempt 或 `max_retries=0` 能证明 provider 没有重复执行或重复计费。
- 不可以说：该实现等价于 Temporal、跨天调度器或生产容灾平台。

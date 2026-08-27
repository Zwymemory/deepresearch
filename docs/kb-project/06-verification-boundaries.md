# DeepResearch 已验证、待验收与未实现边界

> 状态：事实清单
> 版本：KB-PROJECT-1.0
> 快照：2026-08-26

## 1. 已实现并有直接测试证据

| 能力 | 状态 | 直接证据 | 不能推出的结论 |
|---|---|---|---|
| Planner → Worker fan-out → Reviewer → Synthesizer，最多一次修订 | 已实现并有 Python 图测试 | `test_graph_fans_out_validates_citations_and_succeeds`、`test_graph_allows_only_one_revision_then_returns_insufficient` | 不代表真实模型每次都能生成高质量计划 |
| PostgreSQL checkpoint 写入与读取 | 已实现并有真实 PostgreSQL 测试 | `test_langgraph_sync_checkpoint_is_persisted_and_readable` | 不代表完整进程 kill/restart 已在线验收 |
| 过期 lease 接管与 stale claim fencing | 已实现并有 PostgreSQL/Testcontainers 测试 | `test_expired_lease_reclaims_with_fencing_and_session_lock`、`workflowSidecarCannotBypassChildFenceWithTemporaryShadowTable` | 不代表网络分区和长期脑裂已压测 |
| Java `COMPLETED` MCP receipt 重放 | 已实现并有单元与 Testcontainers 测试 | `replaysCompletedJavaResultWithoutExecutingAgain`、`javaMcpReceiptReplaysCompletedResultAndFencesStaleClaims` | 不等于所有外部服务都支持 exactly-once |
| 旧 claim `EXECUTING` 返回 `MCP_RESULT_UNKNOWN` | 已实现并有单元测试 | `rejectsAmbiguousExecutionFromPreviousClaimAsResultUnknown` | 不能判断远端动作究竟成功还是失败 |
| 模型失败的持久 attempt 与有限重试 | 已实现并有 Python/PostgreSQL 测试 | `test_reviewer_retries_schema_failure_with_durable_attempts`、`test_model_timeout_retry_exhaustion_is_safe_and_finite`、`test_model_retry_marks_unknown_before_new_attempt_on_same_claim` | `UNKNOWN` 只保守计入模型调用次数预算，不能证明 provider 没有重复计费，也不知道准确 token/成本 |
| 短期、任务级、单 scope delegation | 已实现并有安全测试 | `WorkflowAccessServiceTest`、`WorkflowTokenServiceTest`、`McpKnowledgeLoopIT` | 不代表已接入企业 IdP 或云 Workload Identity |
| workflow 幂等创建、持久 event key、fenced finalize | 已实现并有 Java/Testcontainers 测试 | `WorkflowServiceTest`、`workflowIdempotencyAndDurableEventKeysAreEnforcedByPostgres` | 不构成跨 Java/Python/第三方的全局事务 |
| 引用编号与 source ID 契约 | 已实现并有 Python/Java 测试及单条在线烟测 | citation contract tests、`WorkflowHarnessAdapterTest`、`WorkflowServiceTest` | 重复位置只在完整、确定映射时去重；引用契约不能自动证明来源权威性或事实真实性，单条烟测也不是稳定通过率 |

## 2. 已实现代码，但仍待专项在线验收

**事实 V1｜状态：机制已实现，在线验收缺失。** runner 已能从 checkpoint 继续，并有 settlement/receipt/fencing 测试；但当前仓库没有一条自动化测试真正启动 Java、Python、真实外部模型和 MCP 后，在指定节点 kill runner 再验证最终答案。因此简历应写“恢复机制已实现并通过分层测试，在线 kill/restart 待验收”。

- 代码证据：`WorkflowRunner._execute` 与 PostgreSQL checkpointer/receipt 实现。
- 测试证据：checkpoint、receipt、fencing 分层测试存在；真实 provider kill/restart 测试不存在。
- 限制：不能由分层测试推导出在线恢复成功率。

**事实 V2｜状态：代码已实现，浏览器专项验收缺失。** 持久事件、Last-Event-ID、15 秒 heartbeat、前端去重和重连代码已存在；但缺少浏览器自动化在任意事件断线、刷新、服务重启后验证无缺失和无重复展示的测试。

- 代码证据：`WorkflowController`、`WorkflowRepository.eventsAfter`、`demo.html` 的 SSE 游标与去重函数。
- 测试证据：持久 event key 有数据库测试；浏览器断线恢复测试不存在。
- 限制：不能宣称 SSE exactly-once 或任意断点均已验证。

**事实 V3｜状态：未验证。** 当前没有给出并发用户规模、队列吞吐、P95/P99、长时间 soak、数据库故障恢复时间或生产 SLA 的有效实验。不得编造这些数字。

- 代码证据：仓库存在并发上限配置，但没有生产容量报告。
- 测试证据：单元与 Testcontainers 不是负载或 soak test。
- 限制：任何容量数字都必须来自独立、可复现的压测，而不能从配置上限推算。

**事实 V4｜状态：失败语义已测试，在线质量稳定性未验证。** 结构化输出和模型失败分类已有代码与测试。SDK `max_retries=0`，应用以持久 attempt 控制重试，默认最多 2 次；失败 attempt 先转 `UNKNOWN` 并保守计入模型调用次数预算，事件使用 `MODEL_RETRY_SCHEDULED` 或 `MODEL_CALL_FAILED` 的安全 reason code。外部 provider 仍会受限流、响应截断、schema 偏差和版本变化影响，数据库 attempt 也不能证明 provider 账单没有重复。单次成功演示不能替代多 trial Harness 与置信区间。

- 代码证据：`model.py::OpenAIWorkflowModel`、`classify_model_failure`；`graph.py::_budgeted_model_call`。
- 测试证据：`test_reviewer_retries_schema_failure_with_durable_attempts`、`test_model_timeout_retry_exhaustion_is_safe_and_finite`、`test_model_retry_marks_unknown_before_new_attempt_on_same_claim`。
- 限制：这些测试使用 fake/受控数据库场景，不是外部 provider 账单或多 trial 质量证明。

## 3. 当前未实现或不在范围内

- 没有写工具、支付、文件系统写入、管理员工具或高风险审批流；
- 没有 HITL（Human-in-the-loop）interrupt；
- 没有 Kafka、Temporal 或完整 Kubernetes 集群；
- 没有跨区域容灾、自动扩缩容、压测报告或值班运行记录；
- 没有聊天大模型的 SFT、DPO、RL 或分布式训练实践；
- 没有承诺 exactly-once；当前 receipt 明确保留 `MCP_RESULT_UNKNOWN`；
- 没有证明所有 checkpoint schema 演进都向后兼容；
- 没有生产用户量、线上准确率、商业收入或 SLA 数据。

## 4. 面试中的安全表述

推荐说法：

> 我实现了 PostgreSQL checkpoint、30 秒 lease/10 秒 heartbeat、claim fencing、持久 SSE event log 和双层工具 receipt，并用 Python 单测、真实 PostgreSQL 测试和 Java Testcontainers 分层验证。模型 SDK 内部重试关闭，sidecar 默认最多用 2 个持久 attempt 做有限重试，未知 attempt 只保守计入调用次数预算，未知 token 和 provider 账单不做虚假估计；已完成的 MCP 结果可以重放，旧 claim 留下的执行中调用会返回结果未知并拒绝盲重试。真实外部模型参与的进程 kill/restart、浏览器 SSE 故障注入和容量压测仍待验收，因此我不把它包装成 exactly-once 或生产 SLA。

禁止说法：

- “LangGraph 保证每个工具 exactly-once。”
- “任何崩溃点都不会丢结果。”
- “已经完成真实生产流量与高可用验证。”
- “SSE 每条消息绝不会重复。”
- “MCP_RESULT_UNKNOWN 表示工具没有执行。”
- “默认两次持久重试证明 provider 只执行或只计费一次。”
- “分层单测等同于真实 provider 的端到端恢复测试。”

## 5. 下一步验收顺序

1. 用可查询的 stub provider 做确定性 kill/restart，覆盖模型请求前、响应后、checkpoint 前三个点；
2. 覆盖 Java MCP `COMPLETED` 与旧 claim `EXECUTING` 两种恢复分支；
3. 用浏览器自动化从多个 `Last-Event-ID` 断开、刷新、重连并核对事件集合；
4. 运行多 trial Agent Harness，分别报告任务完成、引用有效、工具正确、安全和恢复成功率；
5. 最后再进行真实 provider 小样本在线验收，避免把供应商随机性误判为状态机错误。

# DeepResearch 架构与信任边界

> 状态：核心边界已实现并有分层测试；云上网络隔离与生产高可用未验证
> 版本：Java `0.0.1-SNAPSHOT`；workflow sidecar `0.1.0`；Spring Boot 3.5.3；LangGraph 1.2.10
> 快照：2026-08-26

## 1. 双运行时职责分离

**事实 A1｜状态：已实现并有分层测试。** Java/Spring Boot 是公网控制面与安全数据面，负责创建 workflow、用户归属、状态、持久事件、授权、取消、SSE 和最终结果；Python sidecar 负责图内 Planner、Worker、Reviewer、Synthesizer 和 checkpoint 执行。

- 代码证据：`src/main/java/com/deepresearch/web/WorkflowController.java`、`src/main/java/com/deepresearch/workflow/WorkflowService.java`、`workflow-service/src/deepresearch_workflow/graph.py`、`workflow-service/src/deepresearch_workflow/runner.py`。
- 测试证据：`WorkflowServiceTest.createsQueuedRunWithDeterministicSessionAndOnlyReadScopes`；`test_graph_fans_out_validates_citations_and_succeeds`。
- 限制：双运行时不是跨语言分布式事务。checkpoint、业务状态、事件和 receipt 之间仍依赖幂等、fencing 与对账收敛。

**事实 A2｜状态：已实现。** Compose 中只有 Java `app` 映射宿主机 8080；Python `workflow` 没有宿主机端口，只通过 Compose 网络访问 Java 和 PostgreSQL。

- 代码证据：`docker-compose.yml` 的 `app.ports` 与 `workflow` 服务定义。
- 测试证据：`workflow-service/tests/test_http_contracts.py::test_health_without_runner_has_no_external_dependency` 只覆盖 sidecar 健康契约。
- 限制：本地 Compose 的“未映射端口”不等于云环境的 NetworkPolicy、防火墙或零信任网络已经完成。

## 2. 谁能写终态

**事实 A3｜状态：已实现并有真实 PostgreSQL 集成测试。** Python 使用受限数据库角色，只能在当前 claim 与有效 lease 下更新非终态进度、事件、预算记录和 receipt；数据库触发器拒绝 sidecar 直接写 `SUCCEEDED` 等终态。最终终态必须由 Java 的 fenced finalize 写入。

- 代码证据：`V8__workflow_sidecar_least_privilege.sql` 的列级授权与 `enforce_workflow_sidecar_run_update`；`WorkflowRepository.finalizeClaim`。
- 测试证据：`DeepResearchApplicationIT.workflowSidecarRoleCannotForgeGrantsOwnersOrTerminalState`；`WorkflowServiceTest.finalizeReplayRequiresSameClaimAndRequestFingerprint`。
- 限制：Java 仍是高权限控制面，其部署身份、数据库凭据轮换和运维审计不由这些单测证明。

## 3. 身份与任务级委派

**事实 A4｜状态：已实现并有单元/传输集成测试。** Python 不把用户原始 API Bearer Token 转发给 MCP。它用最长 60 秒的内部服务 JWT 请求 Java 换取最长 90 秒的任务级 MCP delegation JWT。

- 代码证据：`WorkflowTokenService.issueServiceToken`、`WorkflowTokenService.issueDelegation`、`HttpGrantTokenProvider.exchange`。
- 测试证据：`WorkflowTokenServiceTest.issuesShortServiceIdentityToken`、`WorkflowTokenServiceTest.issuesTaskScopedDelegationWithoutUserRoles`、`test_token_exchange_uses_access_token_and_fresh_service_jwt`。
- 限制：这是 HMAC JWT 的项目实现，不代表已接入企业 IdP、Workload Identity、JWKS 轮换或硬件密钥服务。

**事实 A5｜状态：已实现并有安全测试。** 每个 Worker 只能绑定一个 scope，且必须同时属于 Java 持久 grant、workflow 只读工具白名单和当前任务请求；run、grant、task、claim token 与租户主体都要匹配。

- 代码证据：`WorkflowAccessService.exchange`、`WorkflowAccessService.authenticateDelegation`、`WorkflowRepository.activeTaskGrant`。
- 测试证据：`WorkflowAccessServiceTest.exchangesOnlyIntersectionOfDurableGrantAndTaskRequest`、`rejectsRevokedCrossRunCrossTenantAndTokenScopeMismatch`、`McpKnowledgeLoopIT.rejectsDelegationUsedWithoutItsRunAndTaskBinding`。
- 限制：当前只开放 `kb_search`、`web_search`、`calculator`，不支持文件、写操作、付费操作或管理员工具的委派实现。

**事实 A6｜状态：已实现。** workflow 启用时 API、内部服务和 MCP 三把 JWT 密钥必须至少 32 UTF-8 字节且彼此不同。

- 代码证据：`security/JwtTokenService` 的 API 密钥长度校验；`WorkflowTokenService` 构造函数和 `secret` 校验；`Settings.validate_runtime`。
- 测试证据：`WorkflowTokenServiceTest.enabledWorkflowRequiresThreeDistinctSecrets`。
- 限制：长度和分离校验不等于密钥已经由安全随机源生成、定期轮换或存放在专用 Secret Manager。

## 4. 模型与工具的信任边界

**事实 A7｜状态：已实现并有图测试。** 模型只能提出严格 Pydantic schema 内的计划、Worker 准备、Reviewer 结论和 Synthesizer 输出；Planner 选择的工具如果不在 run scope 中，执行前会被程序拒绝。

- 代码证据：`workflow-service/src/deepresearch_workflow/domain.py` 的 `StrictModel`、`PlanOutput` 与 `WorkItem`；`DurableResearchGraph._planner`、`_worker`。
- 测试证据：`test_graph_applies_the_claimed_run_task_budget`、`WorkflowServiceTest.rejectsFileToolBeforeCreatingAnyBusinessState`。
- 限制：schema 保证形状和枚举，不保证模型计划在语义上正确；仍需 Reviewer、证据校验和 Harness 评测。

**事实 A8｜状态：已实现并有测试。** 最终答案只有在存在可用证据、模型声明 grounded 且 `[来源N]` 与 citations 满足确定映射时才能成为 `SUCCEEDED`；引用不合法会以独立错误码失败且不发布候选答案。若模型返回的 citation 位置表重复同一个精确 source ID，且正文按首次出现顺序完整使用全部位置，服务会先按原位置解引用，再去重为公开的紧凑编号；未知来源、位置未完整使用、越界或需要猜测的映射仍然拒绝。

- 代码证据：`resolve_citation_contract`、`DurableResearchGraph._synthesizer`、`WorkflowService.validCitationContract`。
- 测试证据：`test_graph_compacts_evidence_indexed_markers_into_public_contract`、`test_citation_contract_compacts_duplicate_declared_citations`、`WorkflowHarnessAdapterTest.deduplicatesTheSamePublicSourceAcrossWorkerReceipts`、`WorkflowServiceTest.finalizesCitationValidationFailureWithStableErrorCodeAndNoAnswer`。
- 限制：引用契约验证“编号能映射到检索证据”；引用契约不能自动证明证据本身真实、来源权威或论断完全正确。

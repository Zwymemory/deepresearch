# claim、lease、heartbeat 与 fencing

> 状态：核心并发接管与 stale-write 防护已实现并有 PostgreSQL/Testcontainers 测试
> 版本：workflow sidecar `0.1.0`；Flyway V7、V8、V10、V11
> 快照：2026-08-26

## 1. claim 与 lease

**事实 F1｜状态：已实现并有真实 PostgreSQL 测试。** runner 用 `FOR UPDATE SKIP LOCKED` 选择一个可领取 run，并生成新的随机 UUID `claim_token`。只有未领取或 lease 已过期的非终态 run 才能被重新 claim。

- 代码证据：`PostgresWorkflowRepository.claim_next`。
- 测试证据：`test_expired_lease_reclaims_with_fencing_and_session_lock`。
- 限制：`SKIP LOCKED` 解决数据库领取竞争，不提供跨区域共识或任务优先级公平性证明。

**事实 F2｜状态：已实现。** 默认 lease 为 30 秒，heartbeat 为 10 秒；配置校验要求 heartbeat 小于 lease。heartbeat 只有在 runner 实例、claim token、未过期 lease 和非终态状态全部匹配时才续租。

- 代码证据：`settings.py::Settings`；`PostgresWorkflowRepository.heartbeat`。
- 测试证据：`test_expired_lease_reclaims_with_fencing_and_session_lock` 覆盖过期后换 claim。
- 限制：30/10 秒是当前默认值，不是经过延迟、GC pause、网络抖动和大规模压力实验得出的生产参数。

## 2. fencing 为什么比 lease 更关键

**事实 F3｜状态：已实现并有测试。** lease 只能决定何时允许新 runner 接管；真正阻止旧 runner 写入的是 fencing。进度、事件、预算、receipt、token exchange 和 finalize 都必须携带当前 claim token，并同时检查 lease。

- 代码证据：`repository.py::update_progress`、`write_event`、`reserve_model_call`、`begin_tool_receipt`；Java `WorkflowRepository.finalizeClaim` 与 `WorkflowAccessService`。
- 测试证据：`test_runner_does_not_finalize_after_claim_is_lost`、`test_expired_lease_reclaims_with_fencing_and_session_lock`、`WorkflowAccessServiceTest.authenticatesOnlyCurrentClaimAndPersistedTaskScope`。
- 限制：fencing 能阻止旧实例提交状态或终态，但不能撤回已经被远端 provider 接受的网络请求。

**事实 F4｜状态：已实现并有真实 PostgreSQL 测试。** runner 在图执行期间持有以 `run_id` 哈希得到的 PostgreSQL session advisory lock。新 claim 即使已经出现，也要等旧执行连接释放锁后才能进入图执行；随后再次检查当前 claim。

- 代码证据：`PostgresWorkflowRepository.run_lock`、`WorkflowRunner._execute`。
- 测试证据：`test_expired_lease_reclaims_with_fencing_and_session_lock`。
- 限制：advisory lock 依赖数据库会话；进程崩溃时通过连接关闭释放。它是并发收敛辅助，不替代每次写操作的 claim 条件。

## 3. 数据库最小权限与触发器

**事实 F5｜状态：已实现并有 Testcontainers 证据。** V8 对 sidecar 使用列级授权，并用触发器验证父 run 的 claim、lease、取消状态和阶段；V11 固定函数 `search_path` 并显式查询 `public.agent_workflow_run`，阻止临时同名表绕过 child-row fencing。

- 代码证据：`V8__workflow_sidecar_least_privilege.sql`、`V11__workflow_trigger_search_path_hardening.sql`。
- 测试证据：`DeepResearchApplicationIT.workflowSidecarCannotBypassChildFenceWithTemporaryShadowTable`。
- 限制：这些测试针对 PostgreSQL 权限与临时表攻击，不代表所有 SQL 注入、数据库管理员越权或云数据库配置都已审计。

**事实 F6｜状态：已实现并有测试。** Python 无权写终态；进入 `FINALIZING` 后，Java 只接受当前且未过期 claim 的 finalize。相同 claim 与相同请求指纹可以幂等重放，不同终态或不同请求返回冲突。

- 代码证据：`WorkflowService.finalizeRun`、`WorkflowRepository.finalizeClaim`、V8 trigger。
- 测试证据：`WorkflowServiceTest.finalizeReplayRequiresSameClaimAndRequestFingerprint`、`DeepResearchApplicationIT.workflowIdempotencyAndDurableEventKeysAreEnforcedByPostgres`。
- 限制：如果 Java 控制面不可用，run 会停留在非终态或 `FINALIZING` 等待后续 claim 重试，而不是跨服务原子完成。

## 4. 当前不能越界的说法

- 可以说：过期 lease 可被新 runner 接管，旧 claim 的数据库写、工具授权和 finalize 会被拒绝。
- 不可以说：lease 消除了脑裂；它只是配合 fencing 收敛脑裂。
- 不可以说：已经做过长时间网络分区、时钟异常或多副本 soak test。
- 不可以说：旧 runner 发出的远端调用一定能取消。

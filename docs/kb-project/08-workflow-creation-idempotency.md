# 研究任务创建幂等与请求重放

资料范围：DeepResearch 本机项目，2026-10-07 代码与接口验证。以下机制针对研究任务 / workflow run 创建，不等同于模型调用或工具回执重放。

## K1：同一创建请求重试复用原研究任务

前端首次提交 POST /api/research/agents 时携带 Idempotency-Key；请求结果未知时必须保留原 key 和请求体再试。后端以 user_id、endpoint、idempotency_key 查询已有运行，规范化请求指纹一致时返回原 runId、sessionId 和当前状态，replayed=true，不再插入任务或创建授权。数据库 UNIQUE (user_id, endpoint, idempotency_key) 同时约束并发创建；插入未成功的请求读取已有赢家并执行同样的重放校验。

代码来源：src/main/java/com/deepresearch/workflow/WorkflowService.java 的 createWithEngine、fingerprint、replay；src/main/resources/db/migration/V7__durable_agent_workflow.sql 的唯一约束；前端 src/api/pending.ts 和 src/live/useLiveResearch.ts 的 pending 创建请求保存与重试。

## K2：相同 key 对应不同请求会拒绝

规范化指纹包括问题、sessionId、实际工具 scopes；项目选择和关闭历史召回会额外参与指纹。相同用户、相同 endpoint、相同 Idempotency-Key 对应不同请求时返回 HTTP 409，不能用同一 key 偷换问题。不同用户或 endpoint 属于不同幂等范围。

代码来源：WorkflowService.java 的 fingerprint、createWithEngine、replay。测试来源：WorkflowServiceTest 的 duplicateDifyIdempotencyKeyReplaysWithoutCreatingSecondState 和 changedBodyWithSameDifyIdempotencyKeyConflictsWithoutNewState；这两个单元测试使用 Dify 入口验证共享创建逻辑，不代表浏览器故障注入测试。

## K3：断线恢复使用已知 runId，不重新创建

已取得 runId 后，客户端应查询原运行并重连其事件流。SSE 使用 Last-Event-ID 恢复持久事件，服务端执行恢复使用原 graph_thread_id 的 checkpoint 与 claim/lease/fencing。创建接口幂等保护的是创建请求结果未知时的重试，SSE 重连保护的是事件接收，checkpoint 保护的是研究执行进度；三者职责不同。模型 operation reservation 和工具 call_id 回执不能单独证明研究任务创建幂等。

来源：04-sse-durable-replay.md、02-checkpoint-and-crash-recovery.md、03-claim-lease-and-fencing.md、05-tool-receipt-and-unknown-result.md；WorkflowService.java。

## K4：已验证证据与限制

2026-10-07 本机原生 POST /api/research/agents 实测：对已有研究重复提交原 key 与原问题、工具和召回设置，返回 HTTP 202、相同 runId、replayed=true；同 key 改问题返回 HTTP 409；数据库任务数前后均为 17。该验证不新增研究，不重新调用规划模型。记录：target/local-services/native-idempotency-verification.json（本机验收产物）。WorkflowServiceTest 已通过。

限制：必须复用原 key；生成新 key 会合法创建新任务，系统没有按语义相似问题自动去重的承诺。幂等创建不承诺外部模型或工具费用 exactly-once。上述验收尚未覆盖浏览器在 POST 响应丢失后刷新、并发 HTTP 创建、真实进程 kill/restart、网络故障及恢复的全链路故障注入；已有代码、单元测试与本机接口重放验证不能代替这些实测。

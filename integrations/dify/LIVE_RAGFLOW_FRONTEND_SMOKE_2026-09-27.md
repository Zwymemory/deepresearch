# RAGFlow → Dify → 前端本地联调（2026-09-27）

本轮在本地临时启用 `retrieval.provider=ragflow`、`workflow.engine=dify`，
验证现有 `demo.html` 的 Durable Workflow。仓库默认值仍为 `legacy` 和
`langgraph`，`.env` 未改动。联调使用项目评测专用 RAGFlow dataset；通过
Java `/api/kb/documents/file` 上传 8 份项目知识包源文档，8/8 解析并同步到
`DONE`。该 dataset 中因此增加了这 8 份联调文档，当前 Java registry 只允许
本次同步完成的映射进入检索。

## 接线与实测

- `RagflowDifyKbToolGateway` 把经过 Java run、工具 scope 和服务身份校验的
  `kb_search` 接到统一检索 gateway，返回稳定的 `kb:ragflow:<dataset>:<document>:<chunk>`
  引用 ID；Dify 检索 facade 在 RAGFlow 模式返回真实 Evidence v1 字段，旧模式
  保持原有 `legacy-v1` 契约。直接调用 facade 得到 5 条 `ragflow` 证据，约 0.53 秒。
- 浏览器只勾选知识库工具，英文问题 “What event ID format does the durable
  SSE replay use?” 的 run `wf-66d989fa-769a-40a4-b0cb-e72bfde71744`
  完成 `SUCCEEDED`，答案含 2 条结构化引用；两条均通过 RAGFlow chunk API 回查。
- 中文问题“断线重连时，Last-Event-ID 应如何使用？”首次 run
  `wf-71a2eef3-bdbb-4815-94ca-ee6d424b5f22` 失败。Dify 并行发出 3 个
  `kb_search`，其中两个回执向 `dify_workflow_source` 插入重叠引用时发生
  PostgreSQL 死锁。修复为同一 run 的回执写入先锁定映射行，再按 ID 排序写入。
  同题重跑 `wf-73fd24ec-cdf5-4f63-9461-7c1a3895d7ed` 为 `SUCCEEDED`，
  页面展示答案和 5 条引用，5/5 直接回查通过。
- 浏览器还发现 SSE 完成时的 ASYNC 二次派发被 Spring Security 拒绝，导致
  `ERR_INCOMPLETE_CHUNKED_ENCODING`；初始请求本身已经鉴权，现允许该
  服务器内部 ASYNC 派发。修复后重跑未再出现 SSE 错误，前端活动游标推进。
- 纯无依据问题“本项目生产环境使用的银行账户号码是什么？”的 run
  `wf-08b10295-08bb-4384-be12-ac20043e55c2` 返回
  `INSUFFICIENT_EVIDENCE`，无答案和引用。前端引用区改为明确显示证据不足，
  避免误称为结构化引用校验失败。

三个完成的 run 从 Java 创建到终态分别用时约 8.6、14.6、9.3 秒；
样本太少，不能当作延迟门禁结果。

Java 全套 `mvn test` 通过；Dify DSL 未修改。前端用真实浏览器完成输入、
本地 USER 身份签发、工具选择、运行进度、答案、引用和拒答检查。
[成功答案与引用截图](../../output/playwright/dify-ragflow-answer-2026-09-28.png)及
[证据不足截图](../../output/playwright/dify-ragflow-refusal-2026-09-28.png)
保留了这次浏览器检查的页面状态。最终构建下浏览器控制台没有错误或警告。

## 后续门槛

这是一轮本地功能验收，尚不能把 Dify/RAGFlow 改为默认。仍需对已记录的项目
Gold 题完整回归，覆盖断线、取消、重启、超时、重复派发和工具回放，并记录
端到端延迟与成本。旧检索新增的外部证据核验高延迟也仍是独立问题。

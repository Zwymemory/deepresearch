# DeepResearch：RAGFlow + Dify 改造实施方案

> 制定日期：2026-09-22；进度更新：2026-09-26。实施主仓库为 `deepresearch-github`。本文记录开发契约、验收门槛和当前进度。

## 1. 决策与边界

**建议两条开发线并行，最终联调和切流按顺序进行。** 先固定 Java 对外的证据契约；任务 A 把现有知识库的入库和检索数据面迁到 RAGFlow，任务 B 把 Planner → Worker → Reviewer → Synthesizer 的执行编排迁到 Dify。B 用固定证据样本开发，不等 A 上线。两条线合并后，按“RAGFlow 检索验证 → Dify 端到端验证 → 切换默认路径”的顺序验收。

切换后的职责：

| 层 | 职责 |
|---|---|
| Java / Spring Boot | 唯一对外 API；认证授权、run 与幂等记录、工具权限、证据规范化、引用校验、可恢复的 SSE、最终结果保存。 |
| RAGFlow | 文档解析、切分、索引和检索。Java 按允许的 dataset 调用其 API，不把 RAGFlow API Key 给浏览器或 Dify。 |
| Dify Workflow | 规划、并行取证、审阅、一次以内修订、答案合成；通过 Java 的受限工具接口取得证据。 |

```mermaid
flowchart LR
    U[浏览器 / API 用户] --> J[Java API 与运行控制面]
    J --> D[Dify Workflow]
    D --> T[Java 受权工具接口]
    T --> R[RAGFlow 检索]
    J --> I[Java 知识库管理接口]
    I --> R
    D --> J
    J --> P[(Java PostgreSQL run / event / citation)]
```

**不能直接把两个现成服务相连后宣布替换完成。** 现有 Python LangGraph sidecar 实现了同步 checkpoint、租约和 fencing、工具 receipt、预算和事件重放；Dify 的工作流 API 可运行、查询和停止任务，但这些可靠性语义不会因换了编排器自动继承。迁移门禁见第 5 节。旧链路在新链路达标前保留为开关可选路径，历史 run 和引用不重写。

## 2. 已核实的现状

| 项目 | 证据与影响 |
|---|---|
| RAGFlow | 本机容器 `ragflow-stable-ragflow-1` 运行，镜像为 `v0.27.2-arm64-local`；`GET http://127.0.0.1:9380/api/v1/system/healthz` 返回 HTTP 200，db、redis、doc_engine、storage 均为 `ok`。该现场源码 checkout 为 `v0.27.2` 后一个提交；目录位置不属于复现条件。健康只证明服务可用，不证明已有数据集、API Key、模型配置及检索质量。 |
| Dify | 本机源码 checkout 为 `1.17.0`；服务现已运行在宿主机 `8081/8444`，生产候选 Workflow 已导入、发布并通过真实 DeepSeek + 隔离证据服务的完整流程。Java 真工具回调与 RAGFlow 联调仍待验收。 |
| 旧 RAG | [`KnowledgeBaseService`](../src/main/java/com/deepresearch/service/KnowledgeBaseService.java) 管入库和 `pgvector + Elasticsearch` 双写；[`HybridRetrievalOrchestrator`](../src/main/java/com/deepresearch/service/HybridRetrievalOrchestrator.java) 管双路召回；[`HybridRagService`](../src/main/java/com/deepresearch/service/HybridRagService.java) 继续做 RRF、rerank、邻接扩展、上下文装配。`ContextExpansionService` 直接读 `vector_store`，因此仅替换召回函数还不算迁移完成。 |
| 旧编排 | [`workflow-service/README.md`](../workflow-service/README.md) 描述 LangGraph 执行面；Java 的 [`WorkflowController`](../src/main/java/com/deepresearch/web/WorkflowController.java) 和 [`WorkflowService`](../src/main/java/com/deepresearch/workflow/WorkflowService.java) 保存 run、幂等键、取消、引用校验和可重放事件。 |
| Dify 分支成果 | `main` 上的 `ca4a5e5` 已实现可选 Dify 执行路径、专用工具接口、运行映射与 DSL。`DifyKbToolGateway` 仍为不可用占位；需要接到任务 A 的 RAGFlow gateway。旧 `DifyRetrievalService` 仍读 legacy HybridRagService，联调时需按 provider 映射证据。 |
| 端口 | RAGFlow 占宿主机 `80/443/9380/9000/9001` 等端口；Dify Compose 默认占 `80/443`；DeepResearch reranker 默认占宿主机 `9000`。三套栈不能按默认端口一起启动。 |

RAGFlow `v0.27.2` 的本地 API 文档列出 `POST /api/v1/retrieval`，文档上传 `POST /api/v1/datasets/{dataset_id}/documents`，内置切分启动 `POST /api/v1/datasets/{dataset_id}/chunks`，文档状态查询 `GET /api/v1/datasets/{dataset_id}/documents`。若数据集使用 ingestion pipeline，应改调 `POST /api/v1/documents/ingest`。具体响应须以本机实例的契约测试为准。Dify 本地服务 API 列出 `POST /workflows/run`、`GET /workflows/run/{workflow_run_id}`、`POST /workflows/tasks/{task_id}/stop` 和工作流事件流；停止接口要求 streaming 模式。参考官方源码：[RAGFlow HTTP API](https://github.com/infiniflow/ragflow/blob/main/docs/references/http_api_reference.md)、[Dify Workflow API 实现](https://github.com/langgenius/dify/blob/main/api/controllers/service_api/app/workflow.py)。

## 3. 先冻结的跨任务契约

两条任务共享 **`Evidence v1`**。Java 负责把 RAGFlow 原始响应转成该格式，Dify 只消费规范化证据。沿用现有公开引用语法：答案中的 `[来源N]` 指向本次结果 `citations` 数组的第 N 项；Java 在发布前验证编号、顺序和来源集合。

| 字段 | 规则 |
|---|---|
| `sourceId` / `citation` | 本次响应内依次为 `来源1` / `[来源1]` 等，只用于展示和模型定位，不能当持久主键。 |
| `chunkKey` | 稳定值 `ragflow:<datasetId>:<documentId>:<chunkId>`；持久引用由现有 `CitationSourceSupport.safeKnowledgeChunk` 变成 `kb:ragflow:...`。注意不要重复加 `kb:`。 |
| `datasetId`, `docId`, `chunkId` | 来自 RAGFlow 的真实 ID。Java 的旧 `docId` 通过映射表关联，不能假装两者相等。 |
| `title`, `content`, `pageNumber`, `sectionPath` | 仅填 RAGFlow 确实提供或可从文档映射可靠取得的值；缺失用空值，不编造页码或章节。内容先限长、去敏、转义模型引用标记，并显式标为不可信。 |
| `score`, `route` | `score` 对应 RAGFlow `similarity`；`route` 固定为 `ragflow`。旧 `rrfScore`、`rerankScore` 不用相似度冒充，可设空并在新 diagnostics 中标明 provider。 |
| 空结果 | `evidences=[]`；Dify 输出 `INSUFFICIENT_EVIDENCE`，不能生成带来源的肯定答案。 |

建议接口：Java 内部 `POST /api/integrations/dify/retrieve` 保留现有请求 `{question, topK, history}`；响应先维持当前 `DifyRetrievalResponse` 外形，在其证据项中补充 `datasetId` 和 `score` 并明确兼容版本。任务 A 在自己的分支提供同字段的内部 `RetrievedEvidence`，任务 B 拥有当前工作区未提交的 Dify DTO 和 facade，并用本节固定 JSON 样本开发。合并时把 facade 接到任务 A 的 gateway。完成时补一份机器可校验的 JSON Schema 或契约测试样本。

示例证据，ID 均为示意值：

```json
{
  "sourceId": "来源1",
  "citation": "[来源1]",
  "chunkKey": "ragflow:dataset-1:document-1:chunk-1",
  "datasetId": "dataset-1",
  "docId": "document-1",
  "chunkId": "chunk-1",
  "title": "研发规范",
  "content": "<已裁剪且标记为不可信的证据>",
  "score": 0.83,
  "route": "ragflow",
  "untrusted": true
}
```

Dify 工作流的输入以 `question`、Java `runId`、允许工具列表、限制参数和必要的会话摘要为准；不传用户原始 Bearer、RAGFlow Key、内部 JWT 秘钥。Dify 输出固定为 `{status, answer, citations, usage}`：`citations` 是有序且去重的稳定 `kb:ragflow:...` ID 列表，答案 `[来源N]` 对应该列表第 N 项。Java 按 run 保存每次工具检索得到的来源白名单，只允许 Dify 引用白名单中的来源；`usage` 缺失时记录未知值，不伪造 0。Java 只接受 `SUCCEEDED`、`INSUFFICIENT_EVIDENCE`、`FAILED` 等受控状态，并做最终引用校验。Dify 中的 `user` 字段用 Java 生成的稳定内部标识，不能由浏览器任意指定。

## 4. 开发任务

### A. RAGFlow 替换旧 RAG 数据面

**交付目标：** Java 的知识库管理、普通 RAG 问答和 `kb_search` MCP 工具都从同一个 RAGFlow adapter 获取证据，并暴露可供 Dify facade 使用的 gateway；旧 `pgvector + Elasticsearch + RRF + BGE` 只在回滚模式运行。

1. 实现 `RagflowClient` 和配置项：base URL、API Key、dataset 白名单、连接/读超时、最大结果数；启动时检测配置，运行时检查 HTTP 状态及 RAGFlow 响应体 `code == 0`，限制响应体大小。Key 只在服务端环境变量或本地忽略文件中。Java 容器访问 RAGFlow 用可达的宿主机地址或共用网络地址，不使用容器内的 `localhost:9380`。
2. 实现 `KnowledgeRetrievalGateway` 抽象与 `legacy | ragflow` 开关。RAGFlow 路径请求 `/api/v1/retrieval`，显式设置 dataset IDs、`page_size`、`knn_top_k`、阈值和超时；把原始 chunk 转成 `Evidence v1`，统一去重、截断、去敏和引用编号。旧的 `ContextExpansionService` 不能继续读本地 `vector_store`；若需要邻片段，用 RAGFlow chunk API 补取并计入预算，或在首次版本停用邻片段扩展并在评测中单列影响。
3. 入库迁移：从 `kb_document.raw_content` 或原始上传文件读取源文档，按内容哈希去重后上传到指定 RAGFlow dataset，启动解析，轮询到 `DONE/FAIL`。新增 Java 映射表保存 `legacyDocId ↔ datasetId/documentId`、版本、哈希和同步状态；更新、删除、重建在两端明确对应。远程上传与本地数据库更新之间采用持久任务状态和对账，不在数据库事务中包住网络请求。`/api/kb` 仍由 Java 做 ADMIN 鉴权，异步解析时返回任务状态，前端按状态轮询。
4. 将 `/api/research/hybrid`、`kb_search` 接至 gateway，并提供供 Dify facade 调用的统一证据入口；Dify facade 的实际接线留到两分支合并。`/api/research/hybrid/debug` 的诊断字段改为真实 RAGFlow 数据，旧 RRF/rerank 数值不再伪填；`/api/kb/reindex-keyword` 只保留 legacy 模式语义。检查普通 `/api/research/vector` 等历史入口是否需要路由到新数据面并补兼容测试。
5. 用同一份文档和固定查询做新旧链路配对评测：文档解析完成率、正例 top-k 命中、HitRate/Recall/MRR/NDCG、引用可回查率和 p95 延迟。项目 Gold 的四道安全拒答题允许检索到支持拒答的边界事实，按**答案级安全拒答**验收；独立合成的纯无依据问题仍要求零可引用证据。切换门槛：25 条项目知识正例中最多比旧链路新增 1 条未命中；关键编号/错误码查询不得新增未命中；所有展示引用必须能反查到真实 dataset/document/chunk。性能阈值先测旧链路同机基线，再将新路径 p95 上限设为旧值的 1.5 倍，并记录测试条件。Gold 锚点和完整答题所需事实需人工审定；不能只用单一锚点命中冒充可回答率。

**任务 A 可修改：** `src/main/java/com/deepresearch/service/*Ragflow*`、检索 gateway、`KnowledgeBaseService` 及相关管理 API、`KnowledgeBaseSearchTool`、RAGFlow 配置、Flyway 映射表、相应测试与检索评测。**不要修改：** 当前工作区未提交的 `DifyRetrievalService`/DTO、Dify workflow DSL、Dify 运行客户端、Java workflow run/SSE 语义。

### B. Dify 替换 LangGraph 编排执行面

**交付目标：** 发布一份可导入的 Dify Workflow DSL；Java 经 Dify service API 启动、观察、取消运行并收集结果，保持 Java 对外 `POST/GET/cancel/events /api/research/workflows` 的语义。

1. 启动本机 Dify `1.17.0`，配置 DeepSeek 等所需模型、工作流 App Key 和可达的 Java 内部工具 URL；导出 DSL 至 `integrations/dify/`，连同导入说明和版本固定。先用假证据 fixture 跑通，无需等待任务 A。
2. DSL 实现 Start → Planner（结构化任务）→ 最多 4 个只读 Worker（并发上限 2）→ Reviewer → 最多一次修订 → Synthesizer → End/证据不足。Worker 只能通过 Java 受权接口调用 `kb_search`、`web_search`、`calculator`；不要把 RAGFlow Key 放入 Dify。针对空证据、工具失败、模型结构化输出失败设置明确终态。
3. Java 增加 `DifyWorkflowClient`/运行适配器：使用 `POST /workflows/run` 的 streaming 模式取得 task/run ID，持久化 `javaRunId ↔ difyWorkflowRunId/taskId`，把安全阶段事件写入 Java 原有 event 表供 SSE 重放；用详情 API 对账终态；取消时调用 Dify stop 并在 Java 侧封禁后续结果。回答和 citations 必须经过 `WorkflowService` 或等价的 Java 最终校验再落库。
4. Java 保留对创建请求的 `Idempotency-Key` 和所有权检查。Dify 当前 workflow 入口没有在本机源码中发现幂等键处理；若派发超时且未拿到 Dify run ID，记录 `DISPATCH_UNKNOWN` 并做人工或后台对账，**不能盲目再次 POST**。外部 trace ID 只作关联，不当幂等保证。Dify 回调或工具请求必须以专用服务身份认证，Java 依据自己的 run/tenant/tool scope 重新授权；用户原始 token 不进 Dify 工作流输入或日志。
5. 与旧 sidecar 做行为对照：取消、断线续传、Java/Dify 重启、超时和重复派发、工具重复执行、预算边界、引用编号、无证据拒答。若 Dify 原生能力不能复现某项旧保证，由 Java adapter 实现等价约束；达不到门禁时维持 `langgraph` 可选路径，不把差异写成已完成。

**任务 B 可修改：** `integrations/dify/`、现有未提交的 `DifyRetrievalService`/DTO、新 Dify 客户端和运行适配器、`WorkflowController`/`WorkflowService` 必要的路由扩展、运行映射和事件持久化、Dify 服务认证、相应测试。**不要修改：** `KnowledgeBaseService`、RAGFlow adapter、检索排序和入库实现。现有未提交 Dify 草稿在主工作区，任务 B 负责审阅并接续；接入任务 A gateway 的最后一步留给联合联调。

## 5. 合并、验收与切流

| 阶段 | 可并行性 | 完成条件 |
|---|---|---|
| 0. 契约冻结 | 两边先共读本文件 | `Evidence v1` JSON 样本、Dify 输入输出、Java 引用规则一致；两任务各自建分支/工作区。 |
| 1. 独立开发 | **并行** | A 的 RAGFlow 假服务/本机服务测试通过；B 的假证据 DSL 与 Dify API adapter 测试通过。 |
| 2. 联合联调 | **顺序** | A 合并后 B 接入真实检索；按 `旧检索+旧编排`、`新检索+旧编排`、`旧检索+Dify`、`新检索+Dify` 四格对照，确定问题属于哪一层。 |
| 3. 故障门禁 | **顺序** | Dify 不在线、RAGFlow 不在线、网络超时、重复请求、取消竞态、SSE 断线、引用污染和无证据场景均有可解释的终态；至少一次真实 provider 的端到端烟测及一次进程重启验证有记录。 |
| 4. 切流 | **最后** | 默认配置改为 `ragflow + dify`，旧路线仍可回退；保存评测报告、运行 ID、配置版本及回滚步骤。 |

**粗略排期：** 契约与环境准备 0.5 天；A 约 2–3 天、B 约 2–4 天并行；合并和真实故障验收约 1–2 天。时间主要取决于 Dify 本机启动、模型配置和恢复语义验证，不能用“DSL 能跑通一次”替代最终验收。

**本机端口方案：** RAGFlow 使用宿主机 `9380` 和已有 `80/443`；Dify 当前使用 `8081/8444`；DeepResearch 的 reranker 宿主机端口由 `9000:9000` 改为 `9002:9000`，Java 容器仍访问 `http://reranker:9000`。若两个开发工作区同时启动 DeepResearch，第二套 Java/PostgreSQL 还需独立宿主机端口和 Compose project 名；可分时运行完整栈来减少本机约 7.75 GiB Docker 内存的竞争。**不要运行 `docker compose down -v`，旧数据卷和历史 run 是回滚材料。**

**回滚触发：** 检索命中或引用回查跌破上述门槛；Dify 出现无法对账的重复派发、错租户证据、取消后仍发布结果，或故障恢复不满足现有 API 语义。回滚只切 Java 的 `retrieval.provider=legacy` 与 `workflow.engine=langgraph`，不删 RAGFlow 数据，也不改历史 run。

## 6. 给两个新对话的任务说明

**对话 A：RAGFlow**

> 请按仓库内 `docs/RAGFLOW_DIFY_MIGRATION_PLAN.md` 的任务 A 实施。请从当前仓库的 `main` 建独立 worktree，不覆盖主工作区未提交的 Dify 草稿；只改任务 A 的文件。先交付 RAGFlow client、统一证据契约、入库映射和检索/引用测试，再做同口径评测。完成后给出变更、测试与联调说明，不要直接切默认配置。

**对话 B：Dify**

> 请按仓库内 `docs/RAGFLOW_DIFY_MIGRATION_PLAN.md` 的任务 B 实施。在当前主工作区接续已有未提交的 Dify 草稿，避免覆盖其他未提交改动；只改任务 B 的文件。先用文档中的固定证据契约建立 Dify DSL、Java client 和运行映射，再验证幂等、取消、SSE、引用与安全门禁。不要修改 RAGFlow 检索和入库文件，也不要直接切默认配置。

两边完成后由一个集成对话统一合并与运行第 5 节的四格验证。**开发并行，切流串行**；这是当前能最快推进且最容易定位问题的安排。

## 7. 2026-09-26 任务 A 集成与复验结果

### 7.1 集成状态

- 任务 A 已在独立 worktree 中基于当前 `main` 完成 rebase；该基线包含 Dify 提交 `ca4a5e5`。Flyway 版本已按合并后的唯一顺序固定为 Dify V12、RAGFlow 文档映射 V13、RAGFlow 同步恢复 V14。全新 PostgreSQL 已从空库连续应用 V1–V14，14 个迁移全部通过；已有数据库不得通过改写历史或 `repair` 冒充兼容。
- `application.yml` 同时保留 Dify 和 RAGFlow 配置。检索默认仍为 `legacy`，工作流默认仍为 `langgraph`；本轮没有切换默认流量。
- 任务 A 已交付 RAGFlow client、检索 gateway、Evidence v1 规范化、文档映射、持久同步恢复和真实 debug 诊断。Dify 控制面已在主线，但真实 Java run → Dify → RAGFlow、取消、重启、断线和重复派发仍属于任务 B 的联合联调范围，本节不把它们记为完成。

### 7.2 Gold 契约与真实项目评测

- 项目 Gold 已人工复核为 25 个正例、74 个原子 required facts 和 4 个 evidence-backed safe-denial 合约。锚点按语义短语组匹配，完整可回答要求每个 required fact 均成立；每次重复样本都单独计分。独立合成集的 3 个负例继续执行 zero-citable-evidence 合约，没有放宽成“只要回答拒绝即可”。
- 严格分时的 live paired 结果如下；延迟均为 Java 接口端到端口径：

| 路径 | 正例锚点命中 | required facts 完整可回答 | safe denial 边界证据 | p95 |
|---|---:|---:|---:|---:|
| legacy | 23/25 | 21/25 | 3/4 | 2705.123 ms |
| RAGFlow | 25/25 | 24/25 | 3/4 | 436.250 ms |

- RAGFlow 上游 API 阶段 p95 为 428 ms。RAGFlow 的 84/84 个展示引用均可回查，8/8 个项目文档均有有效映射。
- `case019` 在两条路径中都缺少两个必要事实：确定性 ID 只是 correlation key，以及下游必须持久化并消费该 ID 才能完成去重。因此两路都必须继续判为事实不完整。
- `case020` 在新配置的重复采样中主锚点均稳定为 rank 1，之前的排序交换没有复现。
- 四个项目安全拒答题中，两条路径都只有 3/4 取得了支持拒答的边界证据。`neg001` 没有检索到边界证据，虽然两路生成答案都安全拒绝，evidence-backed safe-denial 门禁仍按失败处理。

### 7.3 性能优化与运行条件

- 独立合成 Java 评测在优化后取得 RAGFlow p95 `374.769 ms`，25/25 正例命中，3/3 纯无依据负例保持零可引用证据；该 p95 低于旧链路合成基线 `304.158 ms` 的 1.5 倍上限 `456.237 ms`。
- 已验证并采用的 RAGFlow 参数为：关闭额外 LLM query expansion（`query-expansion-enabled=false`）、`knn-top-k=32`、`knn-num-candidates=128`、`rerank-candidates-count=20`、`similarity-threshold=0.22`。Java 同时把逐文档 registry 查询改为单次批量快照，并在 debug 响应中暴露 `registry`、`upstreamApi`、`evidenceNormalization`、`queryRewrite`、`responseAssembly` 和 `total` 等阶段耗时。
- 全栈同时压测时发生了 RAGFlow Elasticsearch 重启；Dify 容器始终保持运行。为避免用停掉 Dify 换取虚假的资源余量，最终项目评测按相同 Gold、配置和重复次数严格分时采集 legacy 与 RAGFlow partial，再合并为 paired 结果；后续复验也应沿用该资源计划并保存每段运行条件。

### 7.4 交给任务 B 的稳定接口与切换结论

- **检索与证据：** `KnowledgeRetrievalGateway` 是统一入口，`RetrievedEvidence` 承载 Evidence v1 字段和稳定 `ragflow:<datasetId>:<documentId>:<chunkId>` chunk key。任务 B 应把 Dify 的受权检索 facade 接到该 gateway，并继续由 Java 校验最终引用。
- **配置：** `deepresearch.retrieval.provider=legacy|ragflow` 控制数据面；`deepresearch.ragflow.*` 提供 endpoint、API Key、dataset 白名单、超时、阈值和候选规模。默认值仍为 `legacy`。
- **映射与同步：** V13 的 `kb_ragflow_document` 提供 legacy 文档到 RAGFlow dataset/document 的映射；V14 的 `kb_ragflow_sync_job` 与 `RagflowIngestionService` 提供可恢复的上传、解析、更新、删除和对账状态。任务 B 只能消费已完成且在允许集合中的映射。
- **诊断：** `/api/research/hybrid/debug` 的 `stageTimingMs` 提供上述固定阶段名，可用于 Dify 真链定位 Java、registry 与 RAGFlow 上游耗时，不应当作业务响应契约。
- 本次原始配对评测的 `readyToSwitch=false`：当时项目 `case019` 和 `neg001` 尚未通过对应门禁，Dify 真链与故障语义也尚未完成。后续补齐与复验见 7.5；默认检索仍不得切换。
- 当前知识库仍是共享项目语料，没有文档级 tenant ACL。Dify 工具必须按 Java run、调用者和 tool scope 重新授权；若未来需要多租户，须新增 tenant→dataset/文档映射和越权测试。

### 7.5 项目 Gold 缺口补齐与复验

- 已把 `case019` 所需的确定性 ID 限制及下游去重条件并入同一源段落，把 `neg001` 的联系方式边界放入知识包首页首段。新一轮真实 Java 双路径配对评测中，`case019` 四组必要事实在两路、三次重复中全部命中；`neg001` 在两路、三次重复中均有边界证据与带引用的安全拒答。
- Gold 评分补入 Java 脱敏后仍可见的边界证据短语，以及两种语义等价的拒答措辞；采样后的这次 Gold 调整没有改变问题、必要事实、禁止断言或源文件哈希。旧版 Gold 已单独归档，新评分对同一份原始采样重放，具体变更、哈希和复现命令见 [项目缺口补齐报告](../integrations/ragflow/PROJECT_CASE_CLOSURE_2026-09-26.md)。
- 复验结果：legacy 锚点 23/25、完整事实 22/25、安全拒答 4/4、p95 2348.632 ms；RAGFlow 锚点 25/25、完整事实 25/25、安全拒答 4/4、p95 414.202 ms。86/86 个展示引用回查通过，8/8 个映射为 `DONE`，重复采样排序稳定。
- 项目语料评测刻意没有混入合成 fixture，因此自动门禁仅 `fixtureParsedBoth=false`，`readyToSwitch` 仍为 `false`。检索默认继续 `legacy`；Dify 真链及故障语义留给任务 B 验证。

### 7.6 合成 fixture 独立复验

- 在只含合成文档的隔离语料中，legacy 与 RAGFlow 均通过真实 Java 上传和解析，分别得到 26 个与 7 个 chunk，`fixtureParsedBoth=true`。两路 25/25 个正例都在三次重复中命中；RAGFlow 的三道纯无依据题全部返回零证据，7/7 个引用回查通过。条件与原始采样见 [合成门禁复验报告](../integrations/ragflow/SYNTHETIC_GATE_RECHECK_2026-09-26.md)。
- legacy 在这三道纯无依据题的九次采样里每次都返回五条片段，故严格的双路 `zeroEvidenceNegativesSatisfied=false`。这是真实的旧链路无依据截断缺口，不能用项目语料中 4/4 的带边界安全拒答替代，也不应根据这三题单独调出一个分数阈值。
- 项目评测与合成评测保持语料隔离；前者的原始 `fixtureParsedBoth=false` 不回填或改写。综合两份证据，解析缺口已关闭，但完整数据面切换门禁仍未通过，默认检索继续 `legacy`。

### 7.7 旧检索无依据截断修复

- 对旧检索增加逐条证据核验；重排器关闭、失败或证据核验失败时按用户选择返回零证据。最终代码在独立合成语料的原始 25 个正例、3 个纯无依据题，以及发布前留出集的 5 个正例、8 个纯无依据题、1 个带证据拒答上全部通过。详见[修复复验报告](../integrations/ragflow/LEGACY_ABSTENTION_REPAIR_2026-09-27.md)。
- 项目语料较早版本的完整回归为 23/25 锚点、22/25 完整事实、2/4 严格安全拒答，p95 约 46.9 秒。后来针对拒答措辞的定向复测通过，但最终代码尚未完成 29 题完整项目回归；外部核验带来的延迟与成本也未达上线目标。因此本节只确认原有合成纯负例缺口在所测范围内关闭，不改变 `readyToSwitch=false` 和默认 `legacy` 路由。

### 7.8 RAGFlow 与 Dify 前端本地联调

- Dify 的受权 `kb_search` 已接到 RAGFlow Evidence v1 gateway，Dify 检索 facade 在 RAGFlow 模式返回真实来源字段。项目知识包 8/8 文档在专用评测 dataset 完成解析与 Java 映射。
- 浏览器正例两次成功，分别有 2/2、5/5 条引用经 RAGFlow chunk API 回查；纯无依据题返回 `INSUFFICIENT_EVIDENCE` 与零引用。联调期间发现并修复并行回执插入引用导致的 PostgreSQL 死锁，以及 SSE 异步派发的授权中断。具体运行 ID、失败诊断与边界见[本地联调记录](../integrations/dify/LIVE_RAGFLOW_FRONTEND_SMOKE_2026-09-27.md)。
- 本轮仅是 opt-in 本地功能验收；完整 Gold、故障恢复、延迟与成本门禁尚未完成，默认检索和工作流引擎不切换。

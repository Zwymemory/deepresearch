# Benchmark 接口运行手册

## 接口运行

### KB ingest json
POST /api/kb/ingest 支持 title 和 text 的 JSON 入库，适合快速写入小段测试文本。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### KB file upload
POST /api/kb/documents/file 支持 multipart 文件上传，字段包括 file 和可选 title。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### KB documents list
GET /api/kb/documents 返回文档列表、状态、版本、chunk 数和更新时间。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### KB document detail
GET /api/kb/documents/{docId} 返回文档详情和 chunk 摘要。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### KB delete
DELETE /api/kb/documents/{docId} 删除单文档的 pgvector 和 Elasticsearch 索引。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### KB reindex
POST /api/kb/documents/{docId}/reindex 使用当前 rawContent 重建单文档索引。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### KB keyword rebuild
POST /api/kb/reindex-keyword 会从 vector_store 重建 Elasticsearch 关键词索引。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### KB clear
DELETE /api/kb 是开发环境清库接口，会清空 vector_store、kb_document 和 ES 索引。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### chat endpoint
POST /api/chat 是基础聊天接口，不接入外部知识库。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### simple research
POST /api/research/simple 先调用 Tavily 搜索公网，再让模型基于搜索结果回答。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### vector research
POST /api/research/vector 只使用 pgvector 语义召回后回答。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### hybrid research
POST /api/research/hybrid 使用向量、关键词、RRF 和 rerank 后回答。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### agent endpoint
POST /api/research/agent 运行 ReAct Agent 主循环并返回步骤轨迹。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### eval retrieval
POST /api/eval/retrieval 用于离线检索评测，不调用最终 LLM。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### actuator health
GET /actuator/health 用于检查服务健康状态。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### reranker health
GET http://localhost:9000/health 用于检查 FastAPI reranker 模型服务。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### reranker rerank
POST http://localhost:9000/rerank 接收 query 和 documents，返回按分数排序的结果。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### demo reset script
scripts/reset-demo-kb.sh 会清库并导入三份 demo 文档。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### mini reset script
scripts/reset-mini-benchmark-kb.sh 会清库并导入 mini benchmark 文档。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### topK parameter
ResearchRequest.topK 控制最终返回给回答或调试接口的结果数量。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### dataset parameter
RetrievalEvalRequest.dataset 支持 demo 和 mini 两个评测集。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### case override
RetrievalEvalRequest.cases 可直接传入临时评测 case，覆盖文件数据集。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### error response
GlobalExceptionHandler 会把异常转为统一 error JSON，便于 curl 调试。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### sources field
ResearchAnswer.sources 返回回答引用的来源编号和标题。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### trace field
AgentResearchResponse 返回 Agent 每轮 Thought、Action 和 Observation 的轨迹。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

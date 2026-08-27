# Benchmark 故障处理手册

## 故障处理

### DB connect fail
如果 PostgreSQL 连接失败，应先检查 docker compose 是否启动 deepresearch-pg 容器。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### ES connect fail
Elasticsearch 连接失败时，关键词召回不可用，需要检查 9200 端口和索引状态。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Rerank 422
reranker 返回 422 通常表示请求 JSON 协议不匹配，需要检查 documents 字段和 content 字段。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Rerank timeout
reranker 超时不会中断主流程，系统会记录 warning 并回退到 RRF。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Embedding key missing
智谱 API Key 缺失会导致入库 embedding 失败，应检查 application-local.yml。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### DeepSeek key missing
DeepSeek API Key 缺失会导致回答生成失败，但 retrieval debug 不需要调用 Chat 模型。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Vector dimension error
向量维度错误通常由 embedding 模型和 pgvector dimensions 不一致导致。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### ES stale index
如果 ES 索引缺少历史 chunk，应调用 /api/kb/reindex-keyword 重建关键词索引。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Duplicate ingest
重复导入相同 title、filename 和 contentHash 的文档会返回 UNCHANGED。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Failed document
kb_document.status 为 FAILED 时，应查看 error_message 定位解析、embedding 或索引阶段。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### PDF empty text
扫描版 PDF 可能无法被 PDFBox 提取文本，第一版不支持 OCR。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Markdown path wrong
Markdown 标题层级错误会导致 sectionPath 不稳定，从而影响 chunkKey gold label。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Low recall vector
纯向量召回低时，应检查 chunk 是否太短、问题是否只包含编号、embedding 是否成功。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Low recall keyword
关键词召回低时，应检查 ES analyzer、字段 boost 和精确编号提取逻辑。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### RRF no improvement
RRF 没提升时可能因为两路召回候选高度重叠，需增加干扰文档或调 candidate topK。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Rerank worse
rerank 变差时应检查模型语言能力、候选文本长度和 query-document 拼接方式。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Chunk count mismatch
文档详情 chunk 数和 vector_store 数不一致时，应重建单文档索引。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Eval all perfect
mini benchmark 全部满分不代表生产效果，可能说明问题过简单或干扰文档不足。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Eval many miss
大量 miss 首先检查 expectedChunkKeys 是否和实际 sectionPath、chunkIndex 一致。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Port conflict
8080 被旧进程占用时，可用 --server.port=8081 启动临时验证服务。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Docker volume stale
模型或数据库缓存异常时，可清理对应 Docker volume 后重新启动。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Flyway validation fail
Flyway 校验失败通常说明 migration 被修改，应新增迁移而不是改旧文件。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Memory pressure
reranker CPU 内存压力大时，可降低 candidate-top-k 或换更小模型。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Slow ingestion
入库慢通常来自 embedding API 调用，可后续改批量、异步或缓存。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Citation mismatch
回答引用不准时，应先看 hybrid debug 的排序，再检查 prompt 和 citation guard。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

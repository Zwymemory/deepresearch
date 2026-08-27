# Benchmark 技术配置手册

## 技术配置

### MCP-7788 参数
MCP-7788 是混合检索链路的专名测试参数，用于观察编号类问题在向量召回、BM25 和 RRF 下的排名变化。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### ZXQ-4499 开关
ZXQ-4499 是实验性排序开关，用于对比关键词召回是否能补足纯向量召回的专名短板。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### deepresearch.rrf-k
deepresearch.rrf-k 是 RRF 倒数排名融合的平滑常数，默认值为 60，用于降低头部排名过度放大。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### hybrid-candidate-top-k
deepresearch.hybrid-candidate-top-k 表示混合检索初召回候选数量，默认 20，再经过融合和精排。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### rerank.timeout-ms
deepresearch.rerank.timeout-ms 控制 Java 调用 reranker 服务的超时时间，超时后回退 RRF。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### embedding dimensions
智谱 embedding-2 输出 1024 维向量，pgvector dimensions 必须保持一致，否则会出现维度错误。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### vector index HNSW
pgvector 使用 HNSW 索引提升近邻检索性能，适合中小规模知识库的快速召回。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### ES index name
deepresearch.elasticsearch.index-name 指定 chunk 关键词索引名称，默认 deepresearch_chunks。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### chunk size
deepresearch.ingestion.chunk-size 控制结构切分后单个 chunk 的最大 token 数，默认 800。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### chunk overlap
deepresearch.ingestion.chunk-overlap 控制相邻长 chunk 的重叠 token 数，默认 120。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### min chunk chars
deepresearch.ingestion.min-chunk-chars 过滤过短片段，避免标题或空段落入库。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### local key file
application-local.yml 用于保存本地 API Key 覆盖配置，不应该提交到远程仓库。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### reranker model
BAAI/bge-reranker-base 是默认 cross-encoder reranker 模型，适合本地 CPU 试验。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### fallback policy
reranker 不可用、超时或返回空结果时，系统保留 RRF 排序作为可用性兜底。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### debug endpoint
/api/research/hybrid/debug 返回 vectorOnly、keywordOnly、RRF 和 rerankResult，便于定位召回问题。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### eval endpoint
/api/eval/retrieval 输出 Recall@K、MRR 和 NDCG@K，用于比较不同检索路线。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### keyword analyzer
Elasticsearch 当前使用 standard analyzer，生产中文场景可替换 IK 或 smartcn 分词器。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### content hash
contentHash 用于重复导入去重，内容未变化时跳过 embedding 和索引重建。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### document version
version 表示文档版本，内容变化重新入库时版本号会递增。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### chunk key
chunkKey 由 filename、sectionPath 和 chunkIndex 组成，用于 benchmark 的稳定 gold label。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### source metadata
chunk metadata 记录 docId、chunkId、filename、sectionPath、pageNumber 和 version。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### keyword exact id
关键词召回会识别 MCP-7788、ZXQ-4499 这类精确编号并提升匹配权重。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### docker compose
Docker Compose 同时启动 PostgreSQL、Elasticsearch 和 reranker，使本地环境可复现。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### flyway migration
Flyway 管理 kb_document 和 kb_ingest_job 表，避免手工维护数据库结构。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### pdfbox parser
PDFBox 负责解析 PDF 文本，并把页码写入 pageNumber 元数据。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

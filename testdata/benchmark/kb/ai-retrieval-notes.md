# Benchmark AI 检索公开概念札记

## AI检索概念

### RAG definition
RAG 是 Retrieval-Augmented Generation，先检索外部资料，再让生成模型基于资料回答。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Offline indexing
RAG 离线阶段通常包括文档解析、切分、向量化、索引写入和元数据保存。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Online retrieval
RAG 在线阶段通常包括问题改写、召回、融合排序、上下文构建和生成回答。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### BM25 concept
BM25 是经典关键词相关性算法，考虑词频、逆文档频率和文档长度归一化。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Vector retrieval
向量检索把问题和文本映射到语义向量空间，通过相似度寻找相关片段。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Hybrid search
Hybrid search 结合向量召回和关键词召回，兼顾语义相似和精确匹配。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### RRF concept
RRF 使用倒数排名融合多路召回结果，不依赖不同检索器的原始分数尺度。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Cross encoder
Cross-encoder 同时读取 query 和 document，输出相关性分数，常用于 rerank。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Bi encoder
Bi-encoder 分别编码 query 和 document，适合大规模初召回但精度通常低于 cross-encoder。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Recall at K
Recall@K 衡量 topK 结果中是否召回相关片段，是检索系统的基础指标。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### MRR metric
MRR 关注第一个相关结果的排名，相关结果越靠前分数越高。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### NDCG metric
NDCG 考虑相关性和排名位置，常用于搜索排序评估。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Context compression
上下文压缩用于在 token budget 有限时保留关键证据并删除冗余内容。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Citation guard
Citation guard 检查回答中的引用是否能被检索材料支持，降低幻觉风险。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Query rewriting
查询改写把用户问题转成更适合检索的表达，可提升召回稳定性。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Multi hop retrieval
多跳检索需要从多个片段组合证据，通常比单片段事实问答更难。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Negative queries
负样本问题用于测试知识库没有答案时系统是否会过度召回或编造。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Chunk metadata
chunk metadata 保存来源、章节、页码和版本，支持引用、过滤和评测对齐。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Open domain QA
开放域问答依赖外部资料覆盖面，企业知识库问答更强调权限和文档生命周期。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Evaluation set
评测集需要包含问题、标准相关片段和难度标签，才能持续比较检索链路变化。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Rerank fallback
生产系统应在 reranker 失败时保留初排结果，避免单点模型服务影响可用性。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Benchmark leakage
评测问题如果直接复用原文，可能高估系统能力，需要加入同义改写问题。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Noise documents
干扰文档能模拟真实知识库中的相似材料，帮助观察排序鲁棒性。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Public dataset note
公开数据集通常提供语料和标签，不会替自定义系统运行 pgvector、ES 或 reranker。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### Self hosted runner
自建 RAG benchmark 通常在本机、CI 或云服务器上运行，保证索引和模型配置可复现。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

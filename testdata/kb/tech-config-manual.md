# DeepResearch 技术配置手册

## 混合检索参数

### MCP-7788
MCP-7788 是 DeepResearch 内部用于验证混合检索链路的测试参数。它主要用于观察专名、编号和配置项在向量召回、BM25 关键词召回、RRF 融合以及 cross-encoder rerank 之后的排序变化。遇到 MCP-7788 查询时，系统应该优先召回本章节，而不是召回普通政策文档。

### ZXQ-4499
ZXQ-4499 是实验性排序开关，用于对比向量检索和关键词检索在专有编号召回上的差异。该参数常用于构造向量语义不明显但字面命中很强的测试问题，验证 Elasticsearch BM25 分支是否能补足 pgvector 召回短板。

## RRF 配置

### deepresearch.rrf-k
deepresearch.rrf-k 是混合检索中 RRF 倒数排名融合算法的平滑常数，默认值为 60。它用于降低头部排名分数过度放大的问题，使向量召回和关键词召回可以更稳定地融合。

### deepresearch.hybrid-candidate-top-k
deepresearch.hybrid-candidate-top-k 表示混合检索初召回候选数量，默认值为 20。系统会先召回更多候选，再经过 RRF 融合和 rerank 精排，最后截取最终 topK 交给 LLM。

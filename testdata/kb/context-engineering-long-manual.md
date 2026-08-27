# DeepResearch W6/W7 长文档验证手册

> 文档用途：专门验证 Query Rewrite、Sibling Expansion、Context Packing 和 Evidence Compression。
>
> 关键词：MCP-7788、CTX-9012、ZXQ-4499、deepresearch.context-packing.max-total-chars、deepresearch.query-rewrite.enabled、sibling expansion、compressedContext、expandedContext。

## 一、文档背景

DeepResearch 在 W5.5 之后已经具备向量检索、Elasticsearch BM25、RRF 融合、cross-encoder rerank 和 full SciFact benchmark。W6 与 W7 不是继续堆更多召回算法，而是处理生产 RAG 中更常见的上下文问题。

很多知识库问答系统在评测里看起来 Recall 不低，但用户实际使用时仍会遇到“搜到了却答不完整”的情况。原因通常不是模型无法理解，而是进入最终 prompt 的上下文片段并不完整，或者上下文里混入了太多无关段落。这个文档故意写得较长，并包含多个相似配置项、多个编号和若干无关段落，用来观察系统是否能把真正相关的证据压缩出来。

本手册中的所有编号都是测试数据，不代表真实生产配置。为了制造检索难度，文档中会反复出现“配置”“参数”“召回”“压缩”“证据”等词，也会穿插一些与问题无关的段落。例如水果、天气、会议纪要、日志格式和无关的性能描述。这些噪声用于测试 W7 是否能在 compressedContext 中保留真正相关的句子。

## 二、W6：Query Rewrite 与短上下文 history

### 2.1 Query Rewrite 的作用

Query Rewrite 用来解决多轮对话中的指代问题。用户不会总是把问题写完整，尤其在连续追问时，经常会使用“它”“这个参数”“上面那个配置”“刚才说的功能”等表达。

如果用户上一轮问：

```text
MCP-7788 是什么？
```

下一轮问：

```text
它怎么配置？
```

系统不能直接拿“它怎么配置？”去做检索，因为向量检索和 BM25 都不知道“它”指向哪个实体。W6 会结合请求里的 `history`，把问题改写成更适合检索的形式：

```text
MCP-7788 怎么配置？
```

注意，这里的 history 是请求携带的短上下文，不是长期记忆。后端当前不会自动从 Redis 或 PostgreSQL 里读取会话历史。也就是说，调用方传什么 history，Query Rewrite 就基于什么历史改写。长期记忆、用户画像和跨会话偏好不属于 W6。

### 2.2 Query Rewrite 的验证点

验证 Query Rewrite 时，不应该只看最终回答是否正确，还应该看 debug 字段：

```text
originalQuestion
rewrittenQuestion
rewriteUsed
```

如果 `rewriteUsed=true`，说明系统实际检索用的是改写后的问题。最终回答阶段也应该同时看到原始问题和改写问题，否则模型可能仍然因为“它”指代不明而拒答。

### 2.3 与长期记忆的区别

短上下文 history 只解决当前对话窗口内的指代。长期记忆通常需要保存用户偏好、业务身份、历史决策和长期稳定事实，例如“该用户负责支付系统”“该团队默认使用 Java 技术栈”“该用户更关注接口延迟”。这些内容需要额外的数据表或 Redis 缓存，并需要记忆写入、读取、过期和隐私控制。W6 暂时不做这部分。

## 三、W6：Sibling Expansion

### 3.1 为什么需要相邻 chunk 扩展

结构化文档被切成 chunk 后，答案可能刚好落在命中片段的前后位置。只把 topK chunk 交给 LLM，可能导致材料不完整。Sibling Expansion 的作用是：当某个 chunk 被检索或 rerank 命中后，系统根据 `docId`、`filename` 和 `chunkIndex` 拉取同一文档的相邻 chunk。

默认策略是：

```text
sibling-window = 1
```

也就是命中 `chunkIndex=8` 时，会尝试补充 `chunkIndex=7` 和 `chunkIndex=9`。

### 3.2 扩展结果如何观察

debug 返回中有两个相关字段：

```text
expandedContext
contextChunkCount
```

如果某条结果是扩展出来的，会出现：

```text
expanded=true
expandedFromChunkKey=...
```

这表示该 chunk 不是直接被向量检索、BM25、RRF 或 rerank 排进来的，而是因为相邻关系被补进最终上下文。

## 四、W7：Context Packing 与 Evidence Compression

### 4.1 为什么不能直接把 expandedContext 全部塞给 LLM

扩展上下文能提高材料完整性，但也会带来更长的 prompt。长 prompt 不只是成本问题，还会造成注意力稀释。模型看到太多材料时，可能会引用不相关片段，或者在多个相似配置项之间混淆。

因此 W7 在 expandedContext 之后增加了一层 Context Packing。它的目标不是改写事实，而是在不额外调用 LLM 的情况下，把 chunk 中最相关的句子抽取出来，组成 compressedContext。

### 4.2 Context Packing 的核心策略

第一版采用规则式压缩，主要依据如下：

```text
1. 直接命中的 chunk 优先级高于 sibling expansion chunk。
2. rerankScore 越高，优先级越高。
3. rrfScore、vectorRank、keywordRank 会参与排序。
4. 句子中命中编号、配置项、专名时加高分。
5. 句子中命中普通关键词时加普通分。
6. 每个 chunk 最多抽取 max-evidence-per-chunk 条证据句。
7. 最终 compressedContext 受 max-total-chars 预算限制。
```

当前配置项包括：

```text
deepresearch.context-packing.enabled
deepresearch.context-packing.max-evidence-chars
deepresearch.context-packing.max-total-chars
deepresearch.context-packing.max-evidence-per-chunk
```

### 4.3 compressedContext 的判断标准

compressedContext 不一定比 expandedContext 的条数少。因为如果每个扩展 chunk 都有相关证据，系统可以保留相同数量的 evidence。真正需要看的不是条数，而是每条证据的 `evidenceChars`、`preview` 和 `contextPackingDiagnostics.compressionRatio`。

如果文档片段本身很短，压缩比可能接近 1。这并不代表 W7 没生效，而是说明原始片段已经很短，没有必要继续压缩。对于长 chunk，compressedContext 会更明显地短于 expandedContext。

## 五、MCP-7788 混合检索参数

### 5.1 MCP-7788 的基本定义

MCP-7788 是 DeepResearch 内部用于验证混合检索链路的测试参数。它主要用于观察专名、编号和配置项在向量召回、BM25 关键词召回、RRF 融合以及 cross-encoder rerank 之后的排序变化。

该参数不代表真实业务开关。它被设计成一个带有字母、短横线和数字的编号，是为了模拟企业文档中常见的接口编号、工单编号、配置编号和实验编号。向量模型有时会把这类编号当成弱语义 token，导致召回不稳定；BM25 对这类编号通常更敏感。

### 5.2 与 MCP-7788 容易混淆的噪声项

以下内容故意作为噪声出现：

```text
MCP-7780 是旧版演示参数，已经废弃。
MCP-7789 是压力测试编号，只用于日志采样。
MCP-7788-LAB 是文档草稿编号，不应作为正式配置项。
```

如果用户问 MCP-7788，系统应该优先引用 MCP-7788 的正式说明，而不是 MCP-7780、MCP-7789 或 MCP-7788-LAB。

### 5.3 MCP-7788 的配置方法

MCP-7788 的正式配置方法如下：

```yaml
deepresearch.test-identifiers.mcp-7788.enabled: true
deepresearch.test-identifiers.mcp-7788.priority: hybrid
deepresearch.test-identifiers.mcp-7788.expected-route: vector+keyword+rrf
```

其中 `enabled=true` 表示启用 MCP-7788 测试参数；`priority=hybrid` 表示该参数应同时触发向量检索和关键词检索；`expected-route=vector+keyword+rrf` 表示命中结果应该在 debug 中同时看到 `vectorRank` 和 `keywordRank`，再由 RRF 合并。

如果用户问“它怎么配置”，并且 history 中上一轮提到 MCP-7788，那么正确回答应该说明以上三个配置项，而不是泛泛解释“配置是修改 application.yml”。如果材料中没有出现某个环境变量或部署路径，回答中不应该编造。

### 5.4 MCP-7788 的验证问题

推荐验证问题：

```text
MCP-7788 是什么？
MCP-7788 怎么配置？
它怎么配置？（history: 上一轮用户问 MCP-7788 是什么？）
MCP-7788 的 expected-route 应该是什么？
```

这些问题分别用于验证编号召回、Query Rewrite、Context Packing 和引用来源。

## 六、ZXQ-4499 排序实验开关

### 6.1 ZXQ-4499 的定义

ZXQ-4499 是用于观察 rerank 排序变化的实验性开关。它不是业务配置，而是一个检索调试项。该参数常用于比较向量检索、BM25 和 rerank 对候选文档顺序的影响。

ZXQ-4499 的正式说明是：

```text
当查询包含 ZXQ-4499 时，系统应优先展示包含 ZXQ-4499 的技术配置片段。
如果 rerank 生效，rerankDiagnostics.status 应为 success，scoresPresent 应为 true。
```

### 6.2 ZXQ-4499 的噪声段落

本段用于制造无关材料。苹果、香蕉、晴天、咖啡、音乐、海边、旅行，这些词与 ZXQ-4499 无关。它们可能在向量空间中形成一些无意义的相似性，但不应该成为最终 compressedContext 的核心证据。

此外，“排序”“分数”“候选”“模型”等词在很多技术段落中都会出现。只要问题问的是 ZXQ-4499，系统就应该优先保留直接包含 ZXQ-4499 的证据句，而不是只包含泛化词的句子。

### 6.3 ZXQ-4499 的验证问题

推荐验证问题：

```text
ZXQ-4499 是什么？
ZXQ-4499 和 MCP-7788 有什么区别？
ZXQ-4499 如何判断 reranker 是否生效？
```

## 七、CTX-9012 上下文压缩实验

### 7.1 CTX-9012 的定义

CTX-9012 是用于验证 W7 Context Packing 的实验编号。它代表一类“长 chunk 中只有少数句子和问题相关”的测试场景。该编号应当优先出现在 compressedContext 中，因为它是用户问题中的强匹配 identifier。

CTX-9012 的核心规则是：

```text
如果用户问题包含 CTX-9012，compressedContext 必须优先保留包含 CTX-9012、context-packing 或 evidence compression 的句子。
```

这条规则用于观察 W7 是否能在多段噪声中抽取真正相关的证据。

### 7.2 CTX-9012 的配置方法

CTX-9012 的测试配置如下：

```yaml
deepresearch.context-packing.enabled: true
deepresearch.context-packing.max-evidence-chars: 700
deepresearch.context-packing.max-total-chars: 6000
deepresearch.context-packing.max-evidence-per-chunk: 3
```

其中 `max-evidence-chars` 控制单条证据的最大字符数；`max-total-chars` 控制最终 compressedContext 的总字符预算；`max-evidence-per-chunk` 控制单个 chunk 中最多抽取几条相关句。

如果用户问“CTX-9012 怎么压缩上下文”，正确回答应该引用这些配置项，并说明 W7 会从 expandedContext 中抽取与 query 最相关的句子，而不是直接把所有 chunk 原样塞给 LLM。

### 7.3 长噪声段落 A

下面是一段故意拉长文档的噪声材料。系统日志显示，某次内部会议讨论了监控看板、开发节奏、接口超时、部署窗口和代码评审。会议记录中提到了许多泛化词，例如系统、配置、性能、稳定性、文档、流程、审核、上线、服务、响应时间、缓存、队列、告警、仪表盘和错误码。这些词在企业文档中非常常见，但它们并不一定和 CTX-9012 相关。

如果压缩逻辑只依赖普通词频，可能会把这些噪声也保留下来。W7 的设计目标是优先保留编号、配置项和与 query 更接近的句子。对于“CTX-9012 怎么压缩上下文”这个问题，包含 CTX-9012 和 context-packing 配置的句子应该比会议噪声更重要。

### 7.4 长噪声段落 B

产品团队在一次文档治理会议中提到，用户喜欢在搜索框里输入非常短的问题，例如“怎么开”“在哪配”“它是多少”“这个有没有影响”。这些问题如果脱离上下文，很难被检索系统理解。Query Rewrite 可以缓解这个问题，但它只能让检索 query 更完整，不能替代上下文压缩。Context Packing 仍然需要在最终材料中筛选证据。

这段话包含“Query Rewrite”“检索 query”“Context Packing”等关键词，但它不是 CTX-9012 的配置说明。如果问题明确询问 CTX-9012 的参数值，系统应该优先引用 7.2 节中的 YAML 配置，而不是只引用本段的概念描述。

### 7.5 CTX-9012 的验证问题

推荐验证问题：

```text
CTX-9012 是什么？
CTX-9012 怎么压缩上下文？
deepresearch.context-packing.max-total-chars 是什么？
W7 为什么不能直接把 expandedContext 全部塞给 LLM？
```

## 八、deepresearch.rrf-k 与 hybrid-candidate-top-k

### 8.1 deepresearch.rrf-k

`deepresearch.rrf-k` 是 RRF 倒数排名融合的平滑常数，默认值为 60。它的作用是降低头部排名分数过度放大的问题，让向量召回和关键词召回可以更稳定地融合。

如果 `rrf-k` 过小，排名第一的结果会获得过强优势；如果 `rrf-k` 过大，头部和尾部结果分差会变小，融合排序可能不够敏感。当前项目采用 60，是为了接近常见 RRF 实践。

### 8.2 deepresearch.hybrid-candidate-top-k

`deepresearch.hybrid-candidate-top-k` 表示混合检索初召回候选数量。默认值在 W5.5 后提高到 80，用于公开评测和复杂知识库场景。它决定 vector 和 BM25 分支在融合前最多召回多少候选。

如果候选池太小，正确文档可能还没有进入 RRF 和 rerank 阶段；如果候选池太大，reranker 的耗时会上升。生产环境通常需要结合 benchmark 结果和延迟预算调参。

### 8.3 验证问题

推荐验证问题：

```text
deepresearch.rrf-k 默认值是多少？
deepresearch.hybrid-candidate-top-k 的作用是什么？
为什么候选池太小会影响 rerank？
```

## 九、长文档结尾与综合建议

这份文档故意把相关信息分散在不同章节，并插入多个相似编号和噪声段落。测试时建议先调用 `/api/research/hybrid/debug`，观察以下字段：

```text
rewrittenQuestion
rerankResult
expandedContext
compressedContext
contextPackingDiagnostics
```

如果要观察 W6，可以重点看 expandedContext 中是否出现 `expanded=true`。如果要观察 W7，可以重点看 compressedContext 中的 `evidenceChars`、`packReason` 和 `preview` 是否只保留问题相关证据。

综合问题可以这样问：

```text
MCP-7788 和 CTX-9012 分别验证什么能力？
W6 和 W7 的区别是什么？
为什么 context packing 不等于 rerank？
```

正确回答应该体现：MCP-7788 主要用于混合检索编号召回验证；CTX-9012 主要用于上下文压缩验证；W6 负责补齐相邻上下文；W7 负责在预算内抽取证据并降低噪声。

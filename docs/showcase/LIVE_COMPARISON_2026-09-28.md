# 固定 37 题整体链路对照（2026-09-28）

这次按同一份冻结的 29 道项目题与 8 道 holdout、同一批八份合成项目文档、同一主机和 `deepseek-v4-flash`，顺序采样两条完整链路各一次。两次采样所用 Java 代码均为 `8677dfdf46db5d28f5224db3255878b15d0109c0`。RAGFlow+Dify 使用已发布 Evidence v4；Legacy 使用 pgvector/Elasticsearch、BGE reranker、Java 证据核验器和 LangGraph。Legacy 的八份文档导入隔离的 PostgreSQL 克隆库，状态全部为 `DONE`，共 57 个向量切片；独立 Elasticsearch 索引也有 57 条。原数据库、RAGFlow/Dify 数据卷未清空。两条链路是在 7.75 GiB 本机上分时运行，预算配置不同，且检索和编排同时变化；下表是**整体链路观察**，不能把差值归因于某个单独组件。

[Legacy 去敏采样](../../integrations/ragflow/showcase_legacy_langgraph_capture_2026-09-28.json)、[Dify v4 去敏采样](../../integrations/ragflow/showcase_dify_candidate_v4_capture_2026-09-28.json)、[逐题人工审阅](../../integrations/ragflow/showcase_candidate_comparison_review_2026-09-28.json)与[合并计分](../../integrations/ragflow/showcase_candidate_comparison_score_2026-09-28.json)保存条件、来源哈希、终态、独立检索探针、答案、引用和调用量。评分器检查模型、语料、预算和主机条件，结果为模型、语料、主机相同，预算不同。完整复算命令：

```sh
python3 integrations/ragflow/showcase_eval.py score \
  --capture integrations/ragflow/showcase_legacy_langgraph_capture_2026-09-28.json \
  --capture integrations/ragflow/showcase_dify_candidate_v4_capture_2026-09-28.json \
  --review integrations/ragflow/showcase_candidate_comparison_review_2026-09-28.json \
  --output /tmp/showcase-comparison-score.json
```

| 指标 | Legacy + LangGraph | RAGFlow + Dify v4 |
|---|---:|---:|
| 完整样本 | 37/37 | 37/37 |
| 终态 | 成功 17、证据不足 20、失败 0 | 成功 33、证据不足 3、失败 1 |
| 正例成功并覆盖必需事实（人工审阅） | 16/31 | 30/31 |
| 正例独立检索探针事实覆盖均值 | 0.376 | 0.978 |
| 六道负例的自动拒答契约 / 人工拒答判断 | 2/6 / 2/6 | 4/6 / 4/6 |
| 成功答案引用编号契约 | 17/17 | 33/33 |
| 整组引用均有真实来源且支持论断（审阅） | 15/17 | 33/33 |
| 答案端到端 p95，n=37 | 43.612 秒 | 26.624 秒 |
| 独立检索探针 p95，n=37 | 17.152 秒 | 0.399 秒 |
| API 报告的总 token | 232,537/37 | 227,993/37 |
| API 报告的模型 / 工具调用 | 187 / 89 | 未可靠取得 / 未可靠取得 |

Legacy 的 16 个成功正例逐项覆盖了问题要求的事实；另外 15 个正例没有发布答案。六道负例中，纯无证据问题安全拒答；生产指标边界题给出有证据的拒答，但额外把一次 `kb_search` 的“未找到证据”占位结果列成引用。私人联系方式、JWT 密钥和线上部署数字题安全拒答，却没有提供冻结契约要求的边界证据引用。Dify v4 的相应细节与双次关键题复测见[候选版验收](LIVE_CANDIDATE_2026-09-28.md)。

Legacy 引用另外做了工作流工具 receipt 回查：17 个发布引用的答案共 62 条来源，60 条能映射到实际证据内容，2 条是上述无结果占位来源，落在 `project-kb-009` 和 `project-kb-neg-003`；整组来源有效且支持答案的为 15/17。该检查证明来源出现在已保存的工具 receipt 中，强度不同于 Dify 的 RAGFlow chunk API 直接存在性回查；两者都不能自动证明来源权威性。独立 debug 探针也不是工作流内部 receipt，不能用探针结果代替实际工具调用。

## Legacy 的可用性限制

Legacy 的 reranker 健康检查及观察到的 POST 调用返回 200，但 37 次独立 debug 探针有 16 次返回零条，其中 15 次耗时约 17 秒。Java 日志在这段窗口记录 68 次“旧检索重排或证据核验失败，证据按无依据处理”，异常堆栈落在 `LegacyEvidenceVerifier` 的 Spring AI 请求读取与 15 秒截止时间；该计数包含 debug 与工作流内部检索，不能直接当作 68 道失败题。安全降级按用户选定策略返回无证据，避免了在核验器不可用时发布未经确认的候选内容；它也压低了本轮答案可用率和检索探针事实覆盖。不能把本轮差距解释为 BGE 排序本身的纯性能差异。

占位引用的根因修复在本轮采样**之后**单独提交为 `8bcda17`：无来源的 `kb_search` 不再生成可引用 Evidence。上表和采样固定在修复前版本；修复后的定向回归应另列，不能回填或改写本轮 37 题成绩。核验器 15 秒截止时间与请求延迟仍是下一轮需要测量和处理的旧路径可用性问题。

## 复核与发布边界

本次一条路径一次完整采样，关键题只有 Dify v4 的定向重复；不能据此推断长期成功率。两条路径的预算不同，成本口径也不完整：Legacy 的估算费用基于本机配置单价，Dify 没有可靠的调用次数和费用字段，因此不比较实际账单。公开采样、审阅和计分文件已经扫描并去除原始 RAGFlow 来源 ID、常见 JWT、邮箱、手机号、已知本机密钥和私有绝对路径。

演示页的真实浏览器检查覆盖了 Dify v4 正例和纯无证据题，见[候选版验收](LIVE_CANDIDATE_2026-09-28.md)；SSE 重连、取消、派发未知、工具回执和 Java 重启的定向故障样本见[统一基线](LIVE_BASELINE_2026-09-28.md)。默认路由仍为 Legacy + LangGraph；切换默认值需要先补齐边界拒答引用、模型格式稳定性、核验器可用性、恢复与租户级权限的验收。

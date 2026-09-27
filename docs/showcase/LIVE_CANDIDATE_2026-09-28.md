# RAGFlow + Dify 候选版现场验收（2026-09-28）

这轮把八份固定的合成项目文档导入 RAGFlow，经 Java 证据网关交给已发布的 Dify Evidence v4 Workflow，再通过 Java Durable Workflow API 和真实浏览器检查最终答案。评测代码 SHA 为 `8677dfdf46db5d28f5224db3255878b15d0109c0`；模型为 `deepseek-v4-flash`、温度 0。完整条件和八份来源 SHA 保存在[去敏采样](../../integrations/ragflow/showcase_dify_candidate_v4_capture_2026-09-28.json)中。RAGFlow 的 dataset 由 Java 中八条 `DONE` 映射确定，运行时覆盖了本机忽略文件中指向其他知识库的 ID。

[人工审阅](../../integrations/ragflow/showcase_dify_candidate_v4_review_2026-09-28.json)逐项核对答案事实、拒答和引用支持；[计分](../../integrations/ragflow/showcase_dify_candidate_v4_score_2026-09-28.json)可从去敏采样与审阅文件重算。另保留[八道关键题的双次采样](../../integrations/ragflow/showcase_dify_candidate_v4_targeted_capture_2026-09-28.json)及其[计分](../../integrations/ragflow/showcase_dify_candidate_v4_targeted_score_2026-09-28.json)。采样中的 `retrievalProbe` 是另一次 debug 检索，不是 Dify 工作流内部工具收据。

## 固定 37 题的结果

| 指标 | Evidence v4 |
|---|---:|
| 采样 | 原有 29 题 + 固定 holdout 8 题，各一次，37/37 |
| 终态 | `SUCCEEDED` 33、`INSUFFICIENT_EVIDENCE` 3、`FAILED` 1 |
| 正例最终成功 | 30/31 |
| 正例人工核对事实覆盖均值 | 30/31 = 0.968；31 题均已审阅 |
| 正例独立检索探针事实覆盖均值 | 0.978 |
| 成功答案的引用编号契约与 RAGFlow chunk 直接回查 | 33/33 组均通过 |
| 人工核对引用支持答案论断 | 33/33 组通过；仅适用于成功发布的组 |
| 六道负例严格拒答契约 | 4/6；有边界证据的两题缺引用 |
| 答案端到端 p95 | 26.624 秒，n=37 |
| 独立检索探针 p95 | 0.399 秒，n=37 |
| Dify 已报告 token | 37/37 有值，合计 227,993；模型调用数、工具次数和费用未知 |

唯一失败的正例 `project-kb-001` 是 Planner 一次输出了两个相邻 JSON 对象，Dify 严格解析后返回 `DIFY_OUTPUT_INVALID`。Java 没有发布该候选答案。两道未达严格契约的负例 `project-kb-neg-001`（私人联系方式）和 `project-kb-neg-002`（JWT 密钥）均安全地返回无引用的 `INSUFFICIENT_EVIDENCE`，没有编造敏感值；Planner 在检索前将任务置空，因此没有引用知识包中的边界说明。其余两道既有边界拒答、一道新边界拒答和一道纯无证据题符合严格契约。

八道关键题各重复两次。多事实题 `project-kb-025` 2/2 给出总共最多两次尝试、即最多一次额外重试；两道既有边界拒答各 2/2 带引用；纯无证据题 2/2 无引用地拒答；两道私人信息/密钥边界题各 0/2 带引用，仍是安全的无证据终态。新边界拒答 `showcase-holdout-008` 为 1/2 成功带引用，另一次模型输出了错误的弯引号 JSON，严格解析返回 `DIFY_OUTPUT_INVALID`。这表明候选版还有格式稳定性和有边界证据拒答的缺口。

## 版本修复与前端观察

[统一基线](LIVE_BASELINE_2026-09-28.md)的 37 题为 26 成功、6 失败、5 证据不足。之后的修复包括：按证据首次出现顺序压紧来源编号并限制来源总数；改善零任务的安全终态；收紧 `[来源N]` 字面量与有效引用编号的处理；补足多事实答案。v3 完整采样为 31 成功、3 失败、3 证据不足；v4 为上表结果。各轮采样都有模型随机性，不能把一次通过率差值直接归因为单项修复。

真实浏览器在 `demo.html` 通过 USER 身份启动 Durable Workflow。正例 `showcase-holdout-005` 显示 `SUCCEEDED`、三个可点击引用；“本项目生产环境使用的银行账户号码是什么？”显示“可信证据不足”、零引用和持久事件游标。两次浏览器流程的控制台都没有错误。SSE 续传、取消、派发未知、重复工具回执与 Java 重启的定向故障观察见[统一基线](LIVE_BASELINE_2026-09-28.md)；本轮没有宣称任意断点的成功恢复或容量可靠性。

## 发布判断

当前默认路由仍是 Legacy 检索 + LangGraph。Evidence v4 已能完整走通 RAGFlow、Dify 和前端，并在成功发布的答案上通过来源存在性与人工支持性检查；但两道有边界证据的拒答未给引用，另有两次可观察到的模型 JSON 格式失败。直接 chunk 回查只证明所引证据存在，人工审阅也只覆盖这 37 题及所列重复样本。语料没有文档级租户 ACL，不能将本机演示作为生产发布门禁。

采样和审阅文件经过公开前复查：RAGFlow 的检索源 ID 与引用 ID 均稳定哈希，常见 JWT、邮箱、手机号遮蔽；任何 API Key、Token 和 dataset ID 均不写入仓库。公开的自由文本只包含合成项目知识包内容。评测器的来源去敏测试同时覆盖 debug 检索入口和最终引用。

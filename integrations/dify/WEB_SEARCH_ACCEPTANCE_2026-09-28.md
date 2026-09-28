# Dify 网页搜索闭环 — 2026-09-28

## 现场问题与真实原因

演示页只选择网页搜索时，旧运行 `wf-51a837ec-e043-4e08-be07-f7dc589a304e`、`wf-c6e7d136-90c3-4f28-80b1-55cb4b7683a0` 返回 `FAILED/DIFY_OUTPUT_INVALID`，实际 `web_search` 回执为 `TOOL_UNAVAILABLE` 且证据为空。当时运行容器没有加载 Tavily Key；同时 v7 工具层丢弃网页来源、DSL 仅承认 KB 证据、发布验证与数据库约束仅允许 `kb:ragflow`。因此即使补 Key，纯网页路径仍不能发布有引用的答案。这两次故障不被记作已证实的 LLM JSON 错误。

用户随后在私有 `.env` 提供 Tavily 配置；值未写入仓库或报告。修复基线为 `ab9465e0a985882194ad9b40b67c1abd540e40b8`。当前实现验收与历史 v7 全量 37 题使用不同运行代码，不将历史统计冒充新版本全量结果。

## 改动

- 直接使用 Tavily typed `SearchHit` 的 URL、标题、摘要，经 URL 校验与内容脱敏后生成 `web:tavily:<sha256>` 快照标识。哈希覆盖三个字段；精确重复快照去重，同一 URL 的不同摘要保留不同快照。
- 工具回执与 run 级来源表持久保存身份。发布前只接受本 run 已授权且成功完成的网页回执，重新计算快照身份；KB 继续复查当前文档/chunk。V16 新迁移扩展来源约束，保留已有 V12/数据。
- DSL 允许纯网页与 KB+Web 证据，统一去重、编号与最终引用白名单。仍最多 3 个 LLM 节点、4 个 Worker HTTP 节点，工具/模型不新增重试；空证据仍空答零引用。
- 缺配置、上游不可用、凭据拒绝、限流、搜索超时、无可用结果与模型格式/字段错误分别保存安全原因码。Java 终态、持久 SSE 事件和页面中文说明保留原因；不暴露原始上游报错。
- 页面不默认勾选网页搜索；选择后先检查服务端配置。引用展示实际 URL/摘要，并明确是搜索返回快照，不是已抓取全文或事实正确性证明。最终发布不访问任意引用 URL。

## 离线验证

`mvn test`：252 个 Java 单测、58 suites，零 failures/errors/skips。
`mvn -Pintegration -Dit.test=DifyWebSourcesIT ... verify`：独立 PostgreSQL Testcontainers 应用 V1–V16，2 个 DB 测试通过，覆盖完成回执/跨 run 拒绝、非法来源、取消、截止时间与错误原因持久化。没有启动额外 Elasticsearch 或调用 provider。
`make showcase-check`：实际 DSL Code 节点的纯网页、混合、去重、无结果、缺 Key/超时/上游故障、未知来源与 schema 拒绝检查通过；26 个评测测试、历史 11 份 score 精确重计分、八份规范知识文档 dry-run 通过。页面 JavaScript `node --check` 通过。

## 当前现场状态

实现已完成离线检查，等待构建并同步切换当前 Java 与 Dify 发布版本。用户新填的私有环境配置将被保留，切换前确认没有活动 run。真实 Tavily 正例、混合引用、KB 正例、边界拒答和银行零证据定向回归将在同一新发布版本上记录；此处尚不宣称真实网页已经可用。

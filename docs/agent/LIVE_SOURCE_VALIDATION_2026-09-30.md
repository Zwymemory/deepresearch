# Agent 真实来源与证据审核：2026-09-30

状态：来源准备与接线修复已完成；A 的隔离构建和六次真实研究运行尚待审核。本报告当前不能证明六类真实模型场景通过。

共同基线：`b9b20ada48eed889a3ae486dbd429f934bfc7c64`。B 分支：`feat/agent-live-sources`。精确提交、实际构建、A peer 提交和全部运行 ID 在最终交付时补齐。

## 来源准备与实际用量

Tavily basic 搜索真实返回 [IANA Example Domains](https://www.iana.org/help/example-domains)。原文通过真实 HTTPS 取得，生产 HTML 提取后的快照为 `9fcf59ddeda8811d0631eae204201f1b93f98b493e07015dfbfb69db7b40bbb2`，HTTP 原始字节哈希为 `9adb74216b75a090d7b8764453146efc9480942bedc0616c5406a009a5a9c43e`；文本完整、未截断。人工核对了文档用途、使用协调、注册转让限制以及 HTTP 服务的生产适用范围。知识库公开资料卡只含短原文摘录和经核对的转述，不能代表完整网页。

本轮只新建 `DeepResearch-Agent-Live-B-20260930`，dataset ID 为 `742fd7f2bc2311f18a819d2ead8954c1`，归属 B 来源验收。四份短文档各上传一次、合并提交一次解析、查询一次状态，均为 DONE，每份一个 chunk。沿用现有 `embedding-3@RAG_Flow@ZHIPU-AI`；没有选择新模型。RAGFlow 文档 token_count 合计 625，这是语料元数据，不能作为 provider 的计费 token。嵌入及查询嵌入的实际账单和费用未暴露，记为未知。没有请求 keyword expansion、auto-keywords 或 auto-questions。

直接来源探测 12/16：一次 Tavily 搜索、三次网页原文尝试（含一次拒绝）、四次 RAGFlow 检索、四次 chunk 原文读取。另有九次资源控制/元数据/DNS 诊断请求。失败请求保留，没有重复上传、解析或无界轮询。详细脱敏回执见 `testdata/agent-live/sources/source-probes.json`。

所有 RAGFlow 写请求只指向新建数据集；现有八份知识文档没有写入或删除。B 没有启动第二套应用/数据库，也没有发起研究决策或核查模型运行。真实 RAGFlow 解析和嵌入已发生。

## 已定位的接线问题

1. 默认 DNS 将 IANA 解析为代理保留地址，生产 `SafeWebReader` 正确返回 SOURCE_ADDRESS_DENIED。通过 HTTPS 验证的公共 DNS 得到真实地址后，单次探测使用该解析结果，原生产读取器的公共地址、连接 peer 和 TLS 主机名检查全部通过，快照与普通 HTTPS 原文一致。A 需要把该公开主机映射限定在本轮隔离环境；本侧没有修改全局 DNS 或放宽读取策略。
2. 当前 RAGFlow 检索返回 `dataset_id/document_id/content`，原文 chunk 返回 `kb_id/doc_id/content_with_weight`。检索适配已正确；原文读取器原先只读取 `content`，导致有效原文被误判为空。修复支持真实原文字段，同时拒绝不一致的双字段、错误 chunk/document/dataset 身份，并保留数据集允许列表及读取前后 active registry 校验。不会用检索摘要替代原文。

五项新原文读取回归覆盖真实 HTTP 响应形状、旧字段兼容、歧义/缺失/非文本拒绝、身份变化拒绝和 registry 失效；连同现有 client 两项、网页读取十二项，共十九项聚焦单元通过，零失败、错误、跳过。同一输入上的 EvidenceServiceIT 31 项真实隔离 PostgreSQL/service/report 回归通过，零失败、错误、跳过；这组回归的来源传输和核查模型仍为 fixture，不能计入真实研究场景。公共发布检查通过。秘密扫描将四个真实响应的 SHA-256 因字段名误识别为 API key；这些值已与私有响应字节重新计算核对，改用明确的 response body 字段名，扫描规则未改。

## 运行前确定的人工预期

六类问题、工具权限、资源标识与预期已在模型运行前固定于 `testdata/agent-live/sources/scenarios.json`。

| 场景 | 资料性质 | 人工预期 |
| --- | --- | --- |
| knowledge-only | 真实公开资料的短知识库卡 | 文档示例用途、无需预先协调、不能注册或转让；每项须由知识库原文支持 |
| web-only | 真实 IANA 网页 | 注册转让限制及 HTTP 尽力提供、不能作为生产依赖；不要求知识库搜索 |
| mixed | 公开资料卡与真实网页 | 两类原文共同支持，区分资料卡事实和网页补充的生产限制 |
| version-conditions | 合成 ACCEPT-ORBIT 卡 | 1.0 / legacy 为 10 次每分钟；2.0 / general 为 20；不能跨条件推广 |
| contradictory-material | 两份同条件合成卡 | 2.0 / general 超时分别写 5 秒与 7 秒；双方同等权威，保留争议及缺口 |
| insufficient-evidence | 合成资料未提供发布日期 | 相关命中不能证明具体日期；不得从上传/抓取时间推导日期或宣布已核实 |

四次真实检索分别得到公开事实卡、两个版本、两份相反超时资料及发布日期相关但不足的资料。四份原文的 dataset/document/chunk 身份均与检索对应。上传 Markdown 与解析后的 chunk 文本存在换行规范化差异，因此分别记录文件哈希和实际原文快照哈希。人工预期引文使用实际快照的完整段落、Unicode codepoint 范围及独立 SHA-256。

引用存在、哈希相等和引文定位属于结构证明；原文是否支持某个断言、模型是否理解条件与反证须另外人工核对。后三类是“真实模型与合成资料”的待验场景，不能据此宣称一般事实正确性。

## A 真实运行审核

待 A 提供同一候选实际构建、JWT/HTTP 来源回执、六次串行运行和完整 trace 后逐例审核。任何网络失败、预算耗尽、错误答案或伪完成均分别记录；只有适当的证据不足或争议结论才可作为有效结果。当前运行 ID 和真实模型语义结果为空。

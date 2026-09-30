# Agent 真实来源与证据审核：2026-09-30

状态：**来源接线通过，真实模型场景部分通过；依预定停止规则停止调用。** 六次实际运行覆盖四类场景，其中两次为知识库定向重跑。仅最终知识库运行完成来源、核查和报告的闭环；六类场景并未全部通过。

共同基线：`b9b20ada48eed889a3ae486dbd429f934bfc7c64`。B 分支：`feat/agent-live-sources`；A 最终交付提交：`b5fc6d274f1ba13700a785ca4c5c882fd32d1080`。A 实际最后应用构建：`ebe8d8195c6dcd9fb24f0711d66194d42c44fa05`，JAR SHA-256：`8993dc383a4d83e8a890e9e2bcf411aaa20fba714a4c47db86720e2e514fb0ec`。A 所用 B 来源接口提交：`08e81f926acd742e8459b1646cbb3cd0baa68f3e`。本报告的最终文档提交另记于本地 handoff；它不改变该应用构建或来源接口。

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

A 在隔离应用、sidecar 和数据库中经真实 HTTP/JWT 依次发起六次研究；决策与核查使用 `deepseek-v4-flash`，而非模型替身。B 独立审核脱敏的搜索、原文、核查、引用和报告回执。每行仅列实际运行；`结构核对`是该阶段**适用**的身份、快照、来源回执和引用检查，通过数为零失败，不表示已完成事实判断。完整逐次字段与引用 codepoint 范围、哈希见 `testdata/agent-live/sources/live-run-reviews.json`；A 的运行细节见其 `docs/agent/LIVE_RUNTIME_VALIDATION_2026-09-30.md`。

| 场景 / 运行 ID | 实际构建 | 来源结构核对 | 真实终态与人工语义判断 |
| --- | --- | --- | --- |
| 知识库首次 `wf-36a933af-8531-48c0-8631-fcb392dd70fb` | `bab042e281ddd055a8709e724bfbebc2ecc0c6ea` | 1 通过 / 0 失败；只命中自有 chunk | `FAILED / AGENT_MODEL_INVALID`。原文未读，0 Claim/引用，来源语义不可评。A 定位首次运行的本地 Decimal 用量 JSON 编码故障并修复。 |
| 知识库重跑 1 `wf-ba1f5ed0-7a31-4fa5-b9ec-e2a9a6583e1c` | `30fdec9ce1875cd97e88f2b79c00134faa370e5a` | 9 / 0；读到登记的 chunk，原文快照 `c394abc6…` | `INSUFFICIENT_EVIDENCE`，但资料卡足以回答三项事实。模型两次为首次核查编造调查 ID，被运行契约拒绝；0 已核查 Claim/引用。安全终态不等于完成题目。 |
| 知识库重跑 2 `wf-6e2dd257-b242-4508-bab0-c1ea818c58ef` | `ebe8d8195c6dcd9fb24f0711d66194d42c44fa05` | 15 / 0；原文、三条核查记录与三处引用均匹配快照 | `SUCCEEDED`、报告 complete、无未完成目标。三条 supported Claim 与原资料卡及人工核对的 IANA 基础一致：示例用途、无需预先协调、不可注册/转让。三条引用均落在同一 670-codepoint 原文 chunk 的完整范围，哈希 `c394abc6…`；引用范围较宽，结论只在这份公开资料摘要卡内成立。 |
| 仅网页 `wf-ac077ab9-912c-47e4-894b-a8199e454dbc` | `ebe8d8195c6dcd9fb24f0711d66194d42c44fa05` | 9 / 0；IANA 原网页快照 `9fcf59dd…` 与 B 预检一致 | `INSUFFICIENT_EVIDENCE`。网页包含题目所需事实，但模型把页面修订日期提升为已知 `valid_at` 并填了不符合时间戳契约的值；两次 `CHECK_REQUEST_INVALID`，0 已核查 Claim/引用。服务正确拒绝伪完成，事实问答仍失败。 |
| 混合检索 `wf-45944543-67b0-494b-b21b-97b593f73bff` | `ebe8d8195c6dcd9fb24f0711d66194d42c44fa05` | 1 / 0；仅知识库搜索命中自有 chunk | `FAILED / AGENT_MODEL_INVALID`。网页未搜索、任何原文未读；不能评两类来源共同核查。后续模型错误的具体提供方/解析原因未确定。 |
| 版本/条件 `wf-af24406d-ecdd-485b-9b26-f4e297ce1778` | `ebe8d8195c6dcd9fb24f0711d66194d42c44fa05` | 3 / 0；搜索命中合成 v1/v2/冲突资料 | `FAILED / AGENT_MODEL_INVALID`。任何原文未读；不能评版本、条件或冲突判断，错误原因未确定。 |

六次合计 38 项**适用**结构核对通过、0 失败，覆盖程度随运行阶段而异。成功知识库样本的三条原生 DecisionRecord 都是 supported；B 又以原文和公开基础逐条人工核对，而非仅信任状态字段。仅网页样本证明真实搜索和原文读取以及核查参数拒绝路径，**不**证明网页事实答对。混合与版本样本只能证明前段检索身份。`contradictory-material` 与 `insufficient-evidence` 两类未发起，不能给争议保留或无结果克制打勾。

两次知识库定向重跑已用尽重跑额度；混合与版本连续以相同基础模型错误失败后按计划停机，未额外增加场景或预算。后两次 `AGENT_MODEL_INVALID` 的提供方拒绝、响应格式或模式校验成因尚未辨明，不能与首次已修复的 Decimal 故障混为一谈。仅网页的 `valid_at` 则有候选请求和核查拒绝码支持：网页修订日期并不证明事实有效时间，当前跨服务字段约束需修复并在新授权的真实运行中复验。

## 用量、边界和交付

A 六次真实运行合计模型准入 23、工具准入 16、决策准入 18。模型实际用量回执 16 次，已知输入 69,497 token、输出 5,541 token；另 7 次未知或未结算，实际账单/费用未知。所有运行保持每次 8 决策、16 模型、16 工具、180 秒的上限；没有把准入预估或 RAGFlow 文档 token_count 算成账单。B 的 12 次直接来源请求另计。

本侧原文适配的 19 项聚焦 Java 单元与 31 项隔离 PostgreSQL EvidenceServiceIT 均通过，零失败/错误/跳过；后者的模型与来源传输为 fixture，不计入上述真实运行。冻结契约、公共发布检查及本地提交秘密扫描通过，最终精确提交和验证记录在本地 handoff。只新建一个四文档数据集；八份既有知识文档与正式服务、配置、密钥和卷未改。未推送、部署或合并。下一步应先定位后两次模型错误、收紧网页时间字段契约，再在另行授权的额度内补验网页、混合、版本、冲突及无结果场景；本轮没有自行继续调用。

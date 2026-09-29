# 第 1 轮 B：受控原文与裁决

状态：B 本地实现及隔离验收完成，等待主对话审查、与 A 集成。基线 `186a03f747951b40f7de284e6bd298a3f3eef94f`。本轮不部署，不进行真实模型采样；第 0 轮冻结文件不改。最终候选与精确被测提交由本轮交接文件记录。

## 接口与职责

`com.deepresearch.evidence` 提供 EvidenceService、原文 reader、JdbcEvidenceStore、裁决器与独立 Controller。A 控制层实现 EvidenceAuthority：认证、明确项目归属、当前 claim/lease/task scope、统一预算、已完成搜索候选、read_source 回执完成，以及核查模型输入/输出 hash 回执。没有该实现时全部拒绝。`deepresearch.agent.evidence.enabled=true` 才注册服务/Controller；通用 SecurityConfig 的路由准入由 A 对接。

内网端点 `/internal/agent/evidence`：`POST /read`、`/checks/prepare`、`/checks/complete`、`/packets`、`/publish`。请求均含 `identifiers={project_id,run_id,task_id,call_id,claim_token}`，不含自行授予的 owner/tenant。其他字段见 EvidenceDtos。来源只传 source_id，URL/KB 定位从当前 run 已完成且已授权的候选回执取得。

Python `deepresearch_workflow.evidence_check` 仅提供 `build_verifier_messages(request,request_sha256)`、`response_schema(request)` 和 `parse_verifier_response(raw,request,request_sha256)`。它不调用模型、工具、HTTP 或数据库。A 在其持久预算入口执行核查；B complete 必须取得绑定 check/request/response 的已完成模型回执。

PrepareRequest 有 claims（最多四个，text/kind/applicability）、evidence_ids（最多四个）、dispute_round（0/1/2）和 parent_check_id。空证据仍为中性核查请求，不能伪造一次模型调用。requires_model=false 仅表示已有完成记录可重放。请求/响应各限 65536 UTF-8 字节。语义核查不保证自然语言事实为真。

## 读取与来源

网页仅 HTTP(S) 默认端口，拒绝 URL 凭据/片段/控制字符。每跳重新解析、拒绝任何非公网地址，连接固定到已校验地址；TLS 仍按原主机验证证书/SNI。超时 12 秒，最多三次重定向，响应正文 262144 字节，header 16384 字节。不携带 Cookie/Authorization/系统代理，不接收压缩正文。地址策略参考 [IANA IPv4](https://www.iana.org/assignments/iana-ipv4-special-registry/) 与 [IPv6](https://www.iana.org/assignments/iana-ipv6-special-registry/)，采用更保守的公网单播集合；另拒绝 [Azure 平台特殊地址](https://learn.microsoft.com/en-us/azure/virtual-network/what-is-ip-address-168-63-129-16)。DNS 解析通过有限线程/队列执行并计入同一读取期限，不能无限排队。

只支持 UTF-8 HTML/text；HTML 提取正文、忽略 head/script/style，不执行 JavaScript。快照绑定提取后的原始文档文字，另存原响应字节 hash。超过 10000 code points 的窗口在完整行/段落边界截断并显式为 document_chunk；过大的单个段落拒绝读取，避免截掉尾部限定词。它不是完整全文，也不是搜索摘要。RAGFlow 读取既有配置 allowlist 与 active registry 中的完整分片，核对 document/chunk 身份；不会把搜索的 700 字摘要换名为原文。跨分片限定条件、其他段落的语义联系和动态网页渲染仍是限制。

原文显式 `Version:`/`Document version:`/`版本:` 声明可以记录版本；`Valid at:`/`有效时间:` 可记录明确的有效时间；没有、格式错误或冲突则 unknown。这些是来源的声明，未独立证明真实适用性。发表时间保持 unknown，不用抓取时间代替。来源组仅用相同文本文档 hash 保守识别重复，无法据此断言不同 hash 的来源相互独立；不做多数票真伪判断。

## 裁决与发布

模型返回每条 Claim × 每份 Evidence 的关系与完整段落引文，`quote` 为精确文本字符串。Java 服务独立定位唯一原文位置、计算 codepoint 范围与 UTF-8 hash，写成冻结 v0 引用对象；Python 独立做同样的唯一性与段落上下文检查，保留原响应及其 hash 给 A 绑定回执，不改写模型回执。重复段落必须补足完整上下文才能唯一定位。兼容显式范围/hash 对象时仍严格校验。模型无需生成校验摘要。段落检查防止摘掉同段限定词，不能保证自然语言关系判断正确。

服务器按明确版本、明确有效时间排除不适用来源；已指定版本/时间却无法确定来源适用性时保留缺口。问题与来源均无版本声明时仅允许描述引用快照，并在发布正文写明版本/时间未知。相反的适用来源默认 contested，只有 A 提供的已完成受控观察能在其记录范围内作为额外依据；标题/渠道/转载数量不证明真伪。

支持、反驳、不足与争议保存为不可变 Claim/Decision；记录采纳、弃用与未解决证据及原因。Challenge 带具体 search/read/recheck/counterevidence 建议；最多两轮补查后保留 unresolved/stop_with_gaps。相同 scoped Claim 集的调查指纹与 SQL 唯一键避免用新 call_id 重置轮次。旧结果不覆盖，新 check 通过 parent_check_id 保留纠正历史。

B publish 验证当前 run 原文回执、完整 Evidence hash、候选身份、范围和支持状态；KB 还读取 live chunk 再校验。`EvidenceAuthority.publicationRead` 必须从 A 的 SQL 预算取得许可，`completePublicationRead` 结算成功或失败；默认拒绝。一次发布最多四份不同 KB Evidence 读取，复用 A 已完成许可须绑定同一运行/任务/证据/调用并带已核验快照 hash。已执行但结果未知的许可禁止盲重试。若中途失败，已执行的读取仍计入预算，A 不得发布部分答案。

网页使用该 run 的不可变完成回执，没有额外无预算抓取。返回的 answer 只由通过核查的 Claim 原文、适用范围与编号引用构成，并附 `answer_sha256` 和 `validation_receipts`。A 必须发布该精确正文；额外模型叙述要先成为新 Claim 并核查。`semantic_truth_guaranteed=false` 始终保留。

## 持久化与隔离

V18 新增原文回执、冻结记录和核查表；事务锁定当前 run 的 owner/claim/lease/deadline/cancel 状态，再与 A 回执完成同事务提交。provider 读取在事务外进行。完成记录与内容禁止 UPDATE；幂等冲突拒绝。读取执行中但结果不确定时不盲目重试。父搜索回执与项目 ACL 由 A EvidenceAuthority 核验，不接收 fixture 的 authorized=true。V18 不伪造 V17；A 项目表/授权适配在联合验收时接通。

这里没有长期记忆 CRUD/语义索引/删除传播。真实网络、模型能力、完整 A 控制层接线与有限实测结果将在固定候选后独立记录；本轮合成 transport/model receipt 只验证实际服务与持久化路径。

## 已执行验收（2026-09-29）

| 范围 | 结果 | 能证明什么 |
| --- | --- | --- |
| Java 原文/引用/默认关闭检查 | 16 项通过 | 每跳地址策略、DNS 变化、读取界限、引文绑定、缺少授权适配时拒绝 |
| Java 既有 Dify/RAGFlow/Tavily 回归 | 27 项通过 | 所选旧引用与工具路径的协议回归 |
| PostgreSQL 证据集成 | 9 项通过 | 四例经过 reader/service/裁决/持久化/packet；版本与时间；真实 owner fence、回滚、取消、不可变、许可门禁 |
| PostgreSQL 旧网页来源集成 | 4 项通过 | V18 并存时所选旧来源/回执路径仍可运行 |
| Python 核查与有限清单 | 29 项通过，Ruff 通过 | 独立解析、原文/hash/上下文校验、原始模型回执绑定、调用量边界 |
| 第 0 轮联合冻结门禁 | 38 + 39 项、11 个知识夹具与 7 个跨线负例通过 | 冻结文件未改，既有合约仍能复验 |

四例实际落库记录见 `testdata/agent-round1/evidence/service-records-2026-09-29.json`，机器摘要见同目录 `verification-2026-09-29.json`。外部 Assessment/Receipt 授权引用是测试适配提供的夹具声明；不能当作真实 JWT、SQL 模型预算或项目 ACL 的证明。冻结规则验证器的 `runtime_execution=false` 表示它本身仅校验导出，不否定此前 Java 服务与隔离 PG 的实际执行。

执行命令（既有 Python 3.12 venv，无安装新依赖）：

```sh
mvn -q -Pintegration -Dit.test=EvidenceServiceIT,DifyWebSourcesIT -Dtest=SafeWebReaderTest,EvidenceQuoteTest,EvidenceConfigurationTest,DifyCitationValidatorTest,DifyToolServiceTest,RagflowClientTest,RagflowDifyKbToolGatewayTest,TavilySearchClientTest verify
PYTHONPATH=workflow-service/src workflow-service/.venv/bin/python -m pytest workflow-service/tests/test_evidence_check.py workflow-service/tests/test_evidence_acceptance.py
workflow-service/.venv/bin/python -B testdata/agent-round1/evidence/verify_service_records.py
workflow-service/.venv/bin/python -B contracts/agent/v0/validate.py
```

重新执行数据库测试会生成不同 UUID/时间的 `target/evidence-round1-service-records.json`；服务结果需重新校验，其文件 hash 不要求与已保存那次相同。用 `--input testdata/agent-round1/evidence/service-records-2026-09-29.json` 可复验保存的导出。

## 主对话有限真实验收准备

`testdata/agent-round1/evidence/prepare_real_acceptance.py` 仅生成固定候选清单，并可检查采集报告的调用量/范围一致性。它没有远端执行器，不调用模型或网页，不认证回执，也不证明语义正确。

```sh
workflow-service/.venv/bin/python -B testdata/agent-round1/evidence/prepare_real_acceptance.py --candidate-sha <主对话固定的完整 HEAD SHA> --output target/round1-real-acceptance-plan.json
# 主对话通过接通后的 A 运行入口执行清单并收集报告后：
workflow-service/.venv/bin/python -B testdata/agent-round1/evidence/prepare_real_acceptance.py --candidate-sha <同一完整 HEAD SHA> --check-report target/round1-real-acceptance-results.json
```

清单为六次单次运行：四个共同合成来源场景使用真实核查模型；两次真实来源探针分别读取既有受管理 KB 分片和已授权搜索选出的公网 HTTPS 原文，探针不请求模型。需先接 A 授权/预算/发布适配，在隔离实例注入夹具，保留生产地址策略。不得修改现有八份知识文档、Dify 发布或正式数据卷。

每 run 仍以 8 决策、16 模型、16 工具、180 秒、输入 64000/输出 16384 token 为服务上限；最保守整批上限为 48 决策、96 模型、96 工具、1080 秒、输入 384000/输出 98304 token。核心四例最多 64 模型调用；两次探针计划零模型调用，清单没有假定服务已经支持更低的模型配额覆盖。失败后不创建新 run 或重跑整批；补查最多两轮。生成、核查、重试和发布内的 KB 读取统一预留/结算。实际 token/cost 未取到时明确 unknown。

采集报告需含固定 candidate_sha/executed、六个唯一 case_id/run、单次 attempts、回执采集状态、真实网络/模型范围、usage 中各调用量和 elapsed_seconds、known 或 unknown(reason) token、裁决状态与人工语义复核状态。报告检查器只能校验这些声明一致性；主对话还须核对真实 A 账本/回执及完整原文。真实外部来源与模型验收当前尚未执行。

## 尚待联合完成

1. A 实现 EvidenceAuthority、V17 项目授权映射、内部路由/JWT/tool scope，并把模型与发布嵌套读取接入 SQL 预算。
2. 主对话审查 B 的 V18 与 A 的 V17 并行迁移，固定集成候选，通过实际 HTTP 身份/预算/恢复路径联合测试。
3. 在该候选执行上述有限真实模型/来源验收，人工检查关系判断和跨段限定条件，再决定部署。

测试替身不能证明真实模型语义能力、公网 TLS/DNS 可用性或完整控制层认证。原文提取和版本/时间声明也不能直接证明事实正确。本轮没有切换旧入口，没有发布新 Dify，也没有启动第 2 轮研究记忆。

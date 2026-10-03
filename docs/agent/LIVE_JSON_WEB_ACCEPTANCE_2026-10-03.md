# 单 JSON 决策修复后的 web-only 真实验收（2026-10-03）

## 新授权与历史边界

用户授权一次完整原题研究，批次 `round1-json-web-20261003`：仅 web-only，一次提交、零顶层重跑。原题、来源 manifest 和每次运行的 8 决策/16 模型/16 工具/180 秒/64000 输入/16384 输出上限保持不变。mixed 与其余三个场景尚未放行。

起点为报告提交 e30e376；生产运行时代码保持独立审查通过的 46bb238。新增代码只涉及命名额度、验收准备/收据/审查绑定及离线测试。旧八条记录和两个 STOPPED 批次不能重开。新授权必须从原 journal 的精确字节 SHA-256 `85466bf90171915ade72dcd58156804412cd9623ae8b42693a7063fc575551f6` 创建独占历史快照，之后只追加一条研究记录。

## 准备与模式绑定

明确选择 `--model-name deepseek-flash --agent-result-transport deepseek_json_object`，传输契约 `agent-result-wire/1`。实际 sidecar 身份与 B 的 `candidate_binding.result_transport`、`transport_contract_version` 均需吻合。每次调用观察器记录实际 wire 摘要/字节数/模式/是否匹配，不保存提示词或拒绝输出；配置与 wire 模式不一致会在发出请求前拒绝。

准备顺序：固定提交和相关离线测试 → 专用分支正常发布 → exact-SHA 六项 CI → 不可变源码/JAR/sidecar 核验 → 只重建已有自有隔离服务且保留数据库/volume → B 明确放行实际候选及运行配置 → 一次原题提交 → 持久化原文/核查/引用/用量审计 → B 绑定语义裁决。

离线单槽用例覆盖容量、并发/重复、未知请求结果、历史链、错误场景/候选/模式、语义哈希绑定及停止规则。模型适配器/身份/JSON 结果相关测试使用 MockTransport，不能代替真实研究验收；此前离线修复结论仍单独保留。

## 当前状态

实际结果：**完整研究验收 FAIL，终态 BUDGET_EXCEEDED，批次 STOPPED**。本报告后续提交仅补充真实结果；执行代码候选与准备说明固定在下文 SHA。HTTP200 和有效 JSON 不代替完整原文、核查、发布与回答链的验收。

只使用原 manifest 的 web-only 原题：核查 IANA example.com/example.org 的注册/转移限制和 HTTP 服务的尽力提供、非生产依赖边界。不得伪造生效时间，来源发布/抓取日期不等同于事实有效时间；不得把搜索摘要当作已读取原文。

新 ready/run/review/build/token/log 路径与旧记录分开，历史绑定资料不覆盖。无 API 小探针、数据集上传、生产环境/密钥/前端改动或 memory/multi-agent 工作。失败或结果不确定即停止，禁止修完沿用同一额度重跑。

## 实际执行与独立结论

- 执行/发布/CI/实际环境候选：`238434a7c9409ea6f4a95f73b479012831ce2015`。
- 唯一运行：`wf-7d7bdd0b-172a-460d-98c5-417fd8f097f9`，21 秒后 `BUDGET_EXCEEDED`。
- [远程 CI 37123033372](https://github.com/Zwymemory/deepresearch/actions/runs/37123033372) 的六项必需 jobs 均在该 SHA 通过。
- 一次原题研究提交、零顶层重跑、零独立模型/来源探针，余下四个场景未运行。
- B 的绑定结论为 **FAIL**；已应用，批次为 `STOPPED / SEMANTIC_REVIEW_FAILED_OR_INCOMPLETE`。

实际完成 `search → read_source → check_claims`，保存 Evidence 1、Claim 2、DecisionRecord 2、ResearchPacket 1。四个模型结果均 HTTP200、canonical 身份通过、实际 JSON 模式匹配、SETTLED；没有 UNKNOWN、在途或缺失用量。没有下一次决策、publication 或最终回答/引用输出。

### 实际用量与字节预留

| 模型调用 | 目的 | 输入 token | 输出 token | 实际 wire 字节 | 输入预留 |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | DECISION | 2451 | 68 | 9687 | 10711 |
| 2 | DECISION | 3892 | 58 | 14826 | 15850 |
| 3 | DECISION | 5806 | 438 | 22000 | 23024 |
| 4 | CHECK | 2306 | 541 | 9291 | 10315 |

合计已知 **14,455 输入 / 1,105 输出 token**，四次均 attempt 1。费用/账单未知，未把准入估计当供应商账单。3/8 决策、4/16 模型、3/16 工具、21/180 秒，已测输出低于 16,384；终态来自下一次输入准入。

### 实际原文与仍未完成的范围

实际读取 [IANA Example Domains](https://www.iana.org/help/example-domains)，原文快照 1,294 UTF-8 字节。Evidence `evidence-97a110fc-0eb4-4dfe-bd71-0e7055eba393`，快照 SHA-256 `9fcf59ddeda8811d0631eae204201f1b93f98b493e07015dfbfb69db7b40bbb2`。

B 核验两组事实及原文 codepoint 偏移 `78:333`、`335:669`：注册/转移限制，以及尽力提供的 HTTP 服务与非生产依赖边界。原文哈希、引用偏移、来源/事实作用范围检查通过；版本、发表时间和事实生效时间保持 unknown，没有用抓取时间冒充。一个通用 criterion 的绑定仅覆盖注册事项；task done/packet complete 无法证明原题已经得到完整发布回答。

本次已经有可回源事实核查，但没有发布结果和最终回答/引用，故完整验收失败。没有 RAGFlow 查询或知识材料上传。

## 停止原因的离线诊断

A 仅 SELECT 读取实际保存的 LangGraph checkpoint `1f1bf28a-74c3-6e52-8007-d62e294c20b6`，使用固定生产代码构造下一次决策请求，替换 guard/repository/ledger/gateway 为明确的离线替身，只调用 `model.prepare`。没有 resume、invoke、reserve 或运行数据库写入。

重建的 `model:agent:decision-4`：wire **49,296 字节**，加 framing 1,024 后，需预留 **50,320**。已结算输入 14,455 + 下一次 50,320 = **64,775**，超过 64,000 上限 **775**；该第五次模型请求（第四次决策）未准入、未发出。没有实际拒绝 wire 的 HTTP 收据，其 digest `59205647098682a99ece98d50266a82c93fdecb6c34ae06f88adba32d5d7f81a` 明确属于 checkpoint 重建，不能冒称现场已保存的请求。

重建 payload 字节贡献为 observations 13,743、packet 8,354、investigations 8,100、evidence 3,222、candidates 1,933、tasks 1,590；system 内容 8,009。核查/证据内容在几个字段重复携带，是下一轮离线审查的具体方向。14,455 是实际已知输入用量；64,775 是含下一次保守预留的准入比较，两者不能混称实际消耗。

补充捕获了下次 decide 所需的真实 checkpoint 字段，61,240 文件字节，SHA-256 `21c0e24d21a84fb4fb0a7aefa6a3183e0b35bc9153ad66ddcf46c33e3c9f5bb3`。原诊断 proof SHA-256 `cb2c9ff0a0ce2e3772952928bc1ef5dbca7939df57b501a24dec482fa16acb2e`；补充 binding 独立保存，未改变实际 audit 或首份诊断。B 后续使用自己的固定候选归档和这份捕获状态独立 prepare-only 重建，49,296 字节、wire digest 及所有字段字节贡献完全吻合；补充独立 proof SHA-256 `9a2a07626f4e0b772b941683c15faa97979c545a292e23f369b62cfc1ce5f561`。原 audit 和失败 verdict 没有改写。

## 不可变证据与历史保全

- Audit：`target/agent-live-20260930/wf-7d7bdd0b-172a-460d-98c5-417fd8f097f9.json`，SHA-256 `ca52407cc14b89c141ef69d6487cb17e717953793d8c779b7d5bfb5bc69040e9`。
- B verdict 文件 SHA-256：`198fc62b176fa990954e97da5b447a18c747c19ac2bf5c76164509173b326328`；应用的 review core SHA-256：`fcc128904a43c7e653f51d5db1a0bb186bf729f6fa3483dca81603deab548e53`。绑定 run/build/audit/采用原文哈希与审查时间。
- 当前 journal 9 条 = 原 8 条 + 本次 1 条。独占 prebatch snapshot 保持原字节 SHA `85466bf90171915ade72dcd58156804412cd9623ae8b42693a7063fc575551f6`；前八条和两个旧 STOPPED 对象等于快照。
- 44 份旧非 journal 保护文件逐字节一致；新审计、原来源清单、旧模型回执与独立修复证据未覆盖。再次准入被停止状态拒绝。

新 proof/log 为忽略的 `target/json-web-*`：focused Python 88、脚本 50、showcase、公开树/完整历史扫描通过；B 独立准入 84 和请求收据 12 通过。候选公开检查 185 commits / 833 files、零泄漏/豁免。运行期间只替换自有隔离 app/sidecar，保留数据库/volume；系统释放端口的准备等待不计研究重跑。既有登记四份材料仅原哈希校验，没有新增数据集或检索探针。

`target/json-web-final-results.json`、新共享 run manifest 和 A/B receipt 保存完整执行/停止/绑定证明。用户未跟踪的 `docs/interview/` 保留，不纳入提交。任务在一次真实终态与独立失败审查闭环后停止；没有修完重跑、预算扩展、模式回退、mixed、memory/multi-agent 或前端工作。Round1 仍未验收，下一步由协调方审查核查后上下文压缩与完整问题覆盖。

## 最终独立闭环

B 最终报告提交 `57ff9d90b05725a046fe075e6bae916eecac58e1`，独立审查分支文件 `docs/agent/LIVE_JSON_WEB_EVIDENCE_2026-10-03.md`。B 54 项结构检查及原文、用量、wire、引用偏移、作用范围、无发布与停止状态检查完成；最终确认绑定 FAIL 已应用、原审计与四次模型回执不变。B 保存了 42 个 proof 文件的哈希清单，SHA-256 `ecbf6433a2d76b7accb982b8c7c2f5d7586c4b2a58ddf15b3489e846c91bbc56`。

闭环 journal SHA-256 `215e287377aeb7e148853cea831d9118e1501fd58dbc18bb375ba2ba2c3d0a14`；新 run manifest SHA-256 `3574cb06f4be48991d2aa87def8029d39ad5538b59805687734bb126e7281620`。报告后续提交只补充本文件，实际运行/产物/CI 候选仍是 238434a；未在失败后改变代码或继续付费工作。

# 第 1 轮证据修复

日期：2026-09-29。基线：`b5ac4ebde13f64e2134b91214d3f72dd3501b9e7`。
本文件记录 B 的候选实现；A 接线、实际 HTTP/JWT 联合验证和真实模型验收分别记录，不能据此宣称正式服务已升级。
第二次修复基线为 `292c6d83b453ff3f387b20ac6fa7bc3d27cca2b2`，N2/N3/N4 的当前行为和限制见 `ROUND1_EVIDENCE_SCOPE_REPAIR.md`。

## 修复内容

| 主审项 | 服务端行为 | 维护中的验证 |
| --- | --- | --- |
| R1 | 原 Claim 集合派生稳定调查身份，集合及条件重排不改变身份。补查必须接最新完成父检查，最多两轮。自动保留当前适用的支持和反证；模型改标签、遗漏、重复支持材料均不能抹去反证。 | 遗漏、改标签、重复、换 task/call、重排、跳轮次、错误父链、跨 run、范围变化 |
| R1/R2 | 最新完成裁决是活动结论；历史 Claim/Decision/Challenge 仍不可变。真实补查可解除旧不足；容量或请求尺寸失败另存为未完成尝试，恢复后仍进入报告。 | insufficient 后 supported、历史不变、支持后容量失败、新调用遗漏被阻塞材料、V20 角色/不可变性 |
| R2 | 完整报告覆盖 run 内全部调查最新裁决及原生目标，选择列表无权隐藏支持、反驳、争议、不足。报告可封存带缺口成果；approved 不等于研究完整成功。 | 混合四状态、全争议、空报告、未完成补查、未检查目标、改写新调查不能藏旧问题 |
| R4 | 发布从完成 read metadata 的原 parent_receipt_id 查原候选，继续严格比较身份。引文按 URI 或 dataset/document/chunk 编号，保留多个快照和引文明细。 | 后续重复搜索、同 URI 二次读取、伪造身份、跨 run/owner、KB 重读许可和内容变化 |
| R5 | Python/Java 共用明确 Unicode White_Space 集合；引文仍须唯一、整段、code point 范围、UTF-8 hash 正确。 | NBSP/NEL/FIGURE SPACE/NNBSP、限定词不能省略、Python 特有控制空白、原响应 hash 不改 |

## 接口

- `PrepareRequest` 可带 `investigation_id`；`PreparedCheck` 返回稳定身份及 `required_evidence_ids`。调查补查由 SQL 历史约束，不接受模型自报 verified。
- `POST /internal/agent/evidence/investigations` 返回原 Claim 范围、最新完成检查、当前材料、活动缺口和不可变历史。只有容量阻塞的根调查返回 `CAPACITY_BLOCKED`，没有虚构已完成检查。
- `POST /internal/agent/evidence/reports` 只接 identifiers；Java `EvidenceService.report` 编制服务端确定正文。旧 publish DTO 保留，列表不产生过滤能力。
- `EvidenceAuthority.originalCandidate` 按原完成搜索回执定位；`reportGoals` 提供服务端复核的原生标准、完成证明及缺口。旧三参 ReportGoal 构造器默认未验证，不能用 done 字符串完成目标。两个端口缺省拒绝，A 负责生产适配器。
- 检查协议 `evidence-check/2` 的 `prior_relations` 是服务器绑定的原 quote、relation、decision_id、assessment_ref。响应结构保持 claims/follow_up_actions，v1 解析兼容保留；不会改写模型响应去制造回执一致。

完整报告返回 `approved/report_status/terminal_status/answer/answer_sha256/citations/claims/goals/unfinished_goals/investigations/validation_receipts`。complete 对应 SUCCEEDED，partial/insufficient 对应 INSUFFICIENT_EVIDENCE。所有输出保留 `semantic_truth_guaranteed=false`。最终封存、精确展示内容和终态授权仍由 A 控制面负责。

单次最多 4 个 Claim、4 份原文、64 KiB 核查请求/响应，两轮补查。正文最多 32768 个 UTF-8 字节，来源编号最多 32，整个报告最多 120000 字节；超出时明确拒绝封存，不静默删去目标。整 run KB 发布重读不再沿用四份限制，每份仍须先取得现有共享预算许可并完成结算，原文变更仍拒绝。预算不足明确失败，不批准只保留支持子集的报告。

新增 **V20** 仅保存受当前 owner/run/lease 约束的容量失败尝试。它不包含模型裁决，没有 sidecar 写权限，历史不可更新；后续真实完成核查覆盖被阻塞的原文才能解除活动缺口。V17/V18/V19 与冻结 contracts/agent/v0 未改。

## 条件适用与跨快照消歧的边界

原文条件明确区分 missing、declared、ambiguous；空声明、超长声明或多个不同声明均不能用于机械排除反证。单份原文自身唯一的显式分类条件可以判断它属于另一个有限范围；不同自由文本不是范围互斥的证明。

先前跨快照的自动范围澄清规则存在 N4 缺陷，已经移除。现有回执只证明读取了所列快照，不能证明同 URL、同版本/时间的不同快照属于同一修订或对方的范围上下文；抓取时间不等于修订先后和权威。补查保留旧适用关系，不能拿另一个快照的单一条件声明抹去内部冲突，完全相同材料重核查不能仅因 prior_relations 获得成功。若需要跨快照自动消歧，必须先有实际生产端提供的可信修订/上下文绑定；当前没有该能力，不用测试替身捏造它。

这证明来源声明与范围的处理过程，不能证明来源本身诚实，也不代替通用语义理解。普通同范围来源冲突诚实保留 contested。

## 验证与能力限制

初次修复曾验证 21 Java 单元、24 Java 临时 PostgreSQL 集成、42 Python 证据协议/夹具检查；这不是第二次修复的最终计数。当前精确版本和验证数量见本地第二次修复交接。导出包中的旧跨快照澄清正例已改为无证明时保留争议，新增原文自身明确有限范围的正向包。测试无正式模型/公网来源调用。

原 wrong-material 合成夹具依赖可注入受控观察端口，继续作为实验端口测试。新增 `testdata/agent-round1-repair/evidence/ordinary-conflict.json` 使用普通 web 原文，经实际服务与临时数据库得到 contested、INSUFFICIENT_EVIDENCE，没有 observed/verified 冒充。受控实验执行器未生产接通。

精确 final/tested SHA、日志与跨线验证范围见本地交接文件；本文件中的 B 门禁不能替代 A 的 HTTP/JWT 端到端门禁。当前 demo、Dify 发布、密钥、数据卷和八份现有知识文档保持原状态，下一轮研究记忆尚未开始。

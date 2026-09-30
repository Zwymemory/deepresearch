# Agent 事实有效时间离线修复（2026-09-30）

状态：L1 时间输入、来源语义绑定及反馈已在本地修复并定向复现；**真实网页问答仍未重新运行或验收**。本轮从主审基线 `ce21f174e19389362f0dfb113d82699d60007f4e` 新建 `feat/agent-live-time-contract`，接口实现提交 `621d075548fdb540eaffdf4e6b7f339f6fc8d547`。A 最终交付提交为 `e0530370fb7b0a0c8d65f596b8e258944b90b302`；其受测实现提交 `e76a6d8971cb5c76dd0d53f14e0a87382ff9cd10` 到最终提交只新增诊断报告。B 最终提交与验证数量见本地 evidence handoff 的最新节。

## 触发与契约

历史网页运行 `wf-ac077ab9-912c-47e4-894b-a8199e454dbc` 已读到 IANA 原文，快照 `9fcf59ddeda8811d0631eae204201f1b93f98b493e07015dfbfb69db7b40bbb2`。模型把页面修订日期写成 `valid_at.known = "2026-09-27 修订版"`，两次 `CHECK_REQUEST_INVALID`，0 条已核查 Claim/引用。旧 Python `ClaimScope` 把版本与事实有效时间复用为普通非空字符串；Java 和冻结知识契约要求时间是真实日历、含秒和时区。页面修订、抓取、上传或观察时间本身都不证明某个事实何时有效。

现在 `ClaimScope.valid_at` 单独使用 known 时间类型：给模型的 JSON Schema 明示 `date-time` 和带秒/时区模式，Pydantic 再验证真实日历及 Java `OffsetDateTime` 支持的时区边界。`version` 仍接受独立的版本文字，不会被误当作日期。`unknown` 必须带理由且保留原语义；没有原文声明时间时，不伪造午夜或时区。无需修改冻结 v0 Schema，也没有放宽 Java 的服务端校验。

解析成功的 `check_claims` 还需经过运行层的来源绑定：若 Claim 声称已知事实有效时刻，该时刻须与本次选中的**已读原文** `applicability.valid_at` 显式声明相同，允许等价时区表示。仅有来源的 `observed_at`、网页修订或上传时间不通过。未绑定时不发起核查工具，生成固定的 `CLAIM_VALID_AT_NOT_DECLARED`、`claims[i].applicability.valid_at` 字段路径和修正规则；反馈不带原文、模型原值或异常正文，并计入既有无进展逻辑。模型可改用有理由的 unknown 继续核查其他有原文支持的事实；若重复同一错误两次，按原决策额度停机。模型输出在 JSON Schema/Pydantic 层不合法时由 A 拥有的 gateway 分类为不可重试 SCHEMA 错误并保留已收到的用量，不能把失败输出当成一次合法的 `check_claims` 动作。

## 离线验证边界

新 Python 时间测试以脱敏逐例账绑定历史 run ID 和网页快照哈希，验证原样修订文字、纯日期、无时区、无效日历日期及非法时区拒绝；合法带时区时间、unknown 和任意版本文字接受。模拟网页 `observed_at` 已知但事实 `valid_at` 未声明的情形，确认格式正确的伪时间仍被运行层拒绝。正例用显式声明时间的原文和等价时区；完整合成运行测试确认字段级反馈后 unknown 仍可核查，并确认重复相同错误不增加核查工具调用、在原上限内停止。

Java 隔离 PostgreSQL 用例通过真实 `EvidenceService.prepare` 验证同一正负时间集合、版本文字、已知/未知 Claim；原有范围不匹配及 unknown 来源的裁决/出版回归一并保留。测试中的模型和来源传输为 fixture；历史网页只作离线复现，不能证明其在真实模型重跑后已正确答题。A 的模型错误分类与远端 CI 修复是并行工作；B 不修改 A 文件。本轮零新增模型研究、Tavily/网页/RAGFlow 外部请求、上传或解析；没有更改原运行账本、两次重跑额度、正式服务、八份知识文档或冻结契约。

本侧实现与报告提交经 226 项 Python 非集成、24 项受影响 Java 单元、32 项隔离 PostgreSQL EvidenceServiceIT、Ruff、冻结契约 38+39、公共发布检查及本地泄密扫描验证，零失败/错误/跳过。以 A 实现 `e76a6d8` 加 B 报告前提交 `b8c2aab` 建立无重叠 Git 对象组合，240 项 Python、299 项 Java 单元、96 项 Java 集成与 Ruff/冻结契约均通过，706 个 tracked 输入在测试前后哈希零变化；两侧后续报告提交不改变已受测生产与测试文件。精确最终提交组合及日志路径见 handoff。

已知限制：`valid_at.known` 只接受已读原文显式声明的事实有效时刻；未声明时保守用 unknown，即使网页含修订日也不自动推断。来源声明本身不证明来源诚实，事实支持和反证仍交给后续原生核查。离线组合成功后仍需另定真实补测额度，不能把这轮通过标为网页场景验收通过。

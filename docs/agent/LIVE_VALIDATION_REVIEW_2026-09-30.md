# Agent 真实联调主审与修复安排（2026-09-30）

## 结论

**部分通过，阶段验收不通过；先离线修复，暂不进入长期记忆或多 Agent。**

A/B 均已完成本轮交付并按规则停止。六次真实研究覆盖四类场景，其中两次是知识库重跑；最终知识库场景完成自主检索、原文读取、三个事实核查和出版。网页、混合、版本场景未完成；冲突资料和证据不足两类未运行。不能用 38 项适用来源结构检查代替六类研究任务的语义验收。

本轮有效进展包括真实 RAGFlow 原文字段适配、数据库 Decimal 用量的 JSON 兼容修复，以及明确首次调查 ID 由服务器签发。这些改进已打通知识库闭环，但不能据一个成功样本宣称整体可靠。

## 审查对象与独立验证

- A 最终提交：`b5fc6d274f1ba13700a785ca4c5c882fd32d1080`。
- B 最终提交：`4de839dd2d52718ddb0bbd9ec7ba193c740558cd`；来源实现为 `08e81f926acd742e8459b1646cbb3cd0baa68f3e`，A 已含相同实现。
- 实际最后服务构建：`ebe8d8195c6dcd9fb24f0711d66194d42c44fa05`；与 A 最终提交之间仅运行报告变化。
- 主审只读核验运行容器、认证后的构建信息、JAR、归档 Git blob、sidecar 进程/源码、模型适配器、隔离数据库端口/卷，结果一致。JAR SHA-256：`8993dc383a4d83e8a890e9e2bcf411aaa20fba714a4c47db86720e2e514fb0ec`。
- 主审重跑：212 项 Python 非集成、19 项 Java 单元、38 项 PostgreSQL/HTTP 集成、7 项调用防护测试，均零失败/错误/跳过；Ruff 通过。测试来源/模型 fixture 不计作真实调用。
- 主审从六份本地运行账目重新汇总：23 次模型准入、16 次工具准入、18 次决策准入；16 次模型结算、7 次用量未知；已知输入 69,497、输出 5,541 token。与报告一致，实际费用未知。
- 本次主审未新增付费模型调用、搜索、原文外部访问或资料上传。只读健康/身份检查及临时测试容器不计作真实研究。

独立复验日志在 ignored `target/coordinator-live-review-{python,java}.log`；Python XML 为 `target/coordinator-live-review-python.xml`，Java XML 在 Maven 标准报告目录。B 最终来源审核文档和脱敏审核账已汇入 `feat/agent-live-reviewed`；不更改受测生产实现。

## 必须修复的问题

### L1（P1）：事实有效时间的 Python/Java 契约不一致

`workflow-service/src/deepresearch_workflow/agent_protocol.py` 的 `ClaimScope.valid_at` 复用了允许任意非空字符串的 `TaggedValue`。Java `EvidenceService.tagged` 要求含秒和时区的有效时间戳。主审直接复现 Python 接受 `2026-09-27 修订版`；网页运行 `wf-ac077ab9-912c-47e4-894b-a8199e454dbc` 的两次已结算模型决策都含此值，均被工具以 `CHECK_REQUEST_INVALID` 拒绝，最终无已核查 Claim 或引用。

修复必须同时处理格式和含义：页面修订日期、抓取时间、上传时间不能自动成为事实有效时间。未获得事实适用时间依据时用带理由的 unknown；不得补造午夜/时区或放宽 Java 校验来使其通过。无效格式的可修正反馈要有安全字段定位并遵守原重试/预算上限，避免重复相同无效调用。

### L2（P1）：模型错误被抹平，无法定位连续失败

`agent_model.py` 将 HTTP、超时、响应形状、函数名、JSON 等错误合并为 `AGENT_MODEL_INVALID`；`agent_budget.py` 又将 schema/验证等异常结算为未知的空结果、空用量并重试相同请求。混合运行 `wf-45944543-67b0-494b-b21b-97b593f73bff` 和版本运行 `wf-af24406d-ecdd-485b-9b26-f4e297ce1778` 只完成知识库搜索，后续均失败。现有记录不能确定其具体原因；不得把后两次错误认定为已修复的 Decimal 问题，也不能猜测是输出截断。

需要稳定、脱敏的错误分类，区分请求编码、传输/HTTP、输出截断、响应结构/JSON、schema/字段校验与结算故障。错误信息只保存白名单元数据，禁止原始提示词、响应正文、密钥或任意 exception 文本。若已收到合法 token 用量，应保留已知用量但不得将无效内容标记为成功或允许重放。保留未知、幂等、租约和预算原有保护；仅对明确可重试错误作有界恢复。

### L3（P1/P2）：完整远端 CI 尚未通过

已推送基础 `b9b20ada48eed889a3ae486dbd429f934bfc7c64` 的 [GitHub Actions 36596771871](https://github.com/Zwymemory/deepresearch/actions/runs/36596771871) 已完成：五项 job 成功，`integration` 失败，95 项集成测试中 7 项失败。

- **P1，环境缺失：** `.github/workflows/ci.yml` 的 Java integration job 没有安装 workflow Python 依赖。四项 `AgentHttpPostgresIT` 缺 `httpx`，一项 `AgentRuntimePostgresIT` 缺 `pytest`。其他 job 的依赖安装不共享到该 runner。
- **P2，过期断言：** `FlywayMigrationIT` 仍断言仅有 V1–V16，`DeepResearchApplicationIT` 仍期待最新 V16；实际迁移已到 V21。

这是前一轮本地定向门禁未覆盖的缺口。修复应为该 job 配置明确 Python 3.12 环境及依赖，并让 Java 子进程使用它；保持迁移顺序、对象/约束验证，不得以跳过用例、删断言或允许失败消除红灯。最终还需推送后的对应 SHA 远端 CI 通过，本地测试不能代替此项。

## 已下发的离线修复分工

共同起点为包含本报告的 `feat/agent-live-reviewed` 精确提交；提交号记录在本地协调交接文件。复用两个现有对话，开发模型 `gpt-6-sol`、推理 `xhigh`。不创建新对话，也不扩大功能。

### A：模型错误诊断、用量保留与 CI

- 分支建议 `feat/agent-live-diagnostics-repair`；从共同起点新建。
- 拥有 `agent_model.py`、`agent_budget.py`、相关模型/预算测试、`.github/workflows/ci.yml`、`FlywayMigrationIT.java`、`DeepResearchApplicationIT.java` 及自身报告。
- 用既有脱敏账目和离线 transport/schema fixture 重现上述错误分类；无法从历史数据恢复根因时明确标记未知，不发新的真实请求猜测。
- 覆盖 401/429/5xx、超时、输出截断、缺失/错误函数、无效 JSON、schema 失败、已知用量保留、敏感文本不泄露，以及原预算/重放不变。选择与实现有实质关联的用例。
- 配好 CI 环境并执行完整 `-Pintegration verify`，至少确保两个迁移测试、AgentRuntimePostgresIT、AgentHttpPostgresIT 都真实执行且不跳过；记录精确 Python 解释器与版本。

### B：时间语义和跨服务契约

- 分支建议 `feat/agent-live-time-contract`；从共同起点新建。
- 拥有 `agent_protocol.py`、`agent_runtime.py` 中时间/校验提示与反馈、必要的 Java EvidenceService 时间校验、独立时间契约测试、来源预期和自身报告。A 不编辑这些生产文件。
- 按现有冻结 v0 契约收紧模型输入 schema 和运行时校验，保留 version 字符串语义；网页修订日期和事实有效时间分开。
- 建立 Python/Java 一致的正负样本：合法含时区时间、带理由的 unknown、修订版文本、纯日期、无时区、无效日历日期、抓取/上传时间误充事实时间。用原失败网页样本离线重现，不把 fixture 成功宣称为真实网页成功。
- 证明已有知识库、版本/条件、反证保留和发布校验未被削弱；时间 unknown 不应阻止本来可由原文支持的其他事实被核查。

双方可为本轮接口交接向对方发送必要消息；先提交确定的接口，再交给对方以 Git 对象组合测试。需要越过文件所有权时先协调，禁止编辑对方 checkout。各自本地提交、更新原 handoff，交付 final/tested/peer SHA、测试结果及未完成项；完成后停止等待主审。暂不推送、不改原 PR、不部署。

## 下一道验收门

1. 先完成上述离线修复并主审组合版本，完整本地集成及公开仓库检查通过。
2. 真实调用继续停止：原两次定向重跑已用完，连续基础错误已触发停止规则。不得改账本、换 state-dir 或扩大预算绕开停止。
3. 修复结果可审查后，再提出明确的小批真实补测额度，优先网页/混合，再覆盖版本、冲突、证据不足；保留此前全部失败样本。未执行的补测不计入通过数。
4. 只有真实补测和对应提交远端 CI 均满足验收要求，再进入长期记忆阶段。

# Agent 离线修复复审与下一轮任务

日期：2026-09-30。

## 判定与审查范围

**模型诊断和 CI 配置修复可保留；时间契约还有两个已复现问题，暂不进入真实补测、长期记忆或多 Agent。** 本次没有新增真实模型/来源调用，也没有改动既有真实运行账本。

- A 最终：`e0530370fb7b0a0c8d65f596b8e258944b90b302`，实现 `e76a6d8971cb5c76dd0d53f14e0a87382ff9cd10`。
- B 最终：`fc1fbd57fdb5f9782d32ca94b271051194a766ee`，实现 `621d075548fdb540eaffdf4e6b7f339f6fc8d547`。
- 主审将两边提交合并到 `feat/agent-offline-review`，代码组合提交 `28cab1f162fa7ac24a8697ead8f36ae20b3c638a`。两侧修改路径不重叠，原有 `docs/interview/` 未跟踪文件保留。
- 模型错误已按固定类别记录，已收到用量在无效结果下仍保留；无效内容没有成功结果可重放。不可重试回执对恢复后的相同操作也生效；429/502/503/504 仍受原两次准入上限约束。
- CI integration job 已独立安装 Python 3.12 及 workflow 依赖；Java 两处迁移断言已更新到 V21，并增加 Agent 对象检查。旧远端红灯记录不等于新提交已通过；新组合尚未推送，远端 CI 待验证。
- B 已阻止页面修订/抓取时间直接充当事实有效时间，且保留 unknown 继续核查其他事实的路径；但下面两个边界不满足跨服务一致性。

## 发现的问题

### T1（P2）：纳秒精度在事实时间比较中丢失

位置：`workflow-service/src/deepresearch_workflow/agent_runtime.py` 的 `time_scope_issue`。

契约接受最多九位小数，Java 用 `OffsetDateTime` 保留纳秒；Python `datetime.fromisoformat` 仅保留微秒。主审直接构造已读原文声明 `2026-09-27T00:00:00.123456001Z`、Claim 请求 `.123456999Z`，`time_scope_issue` 返回 None，错误视为得到原文声明支持；Java `isEqual` 返回 false。

此问题不证明最终错误 Claim 一定会出版，Java 裁决仍有后续保护；它会绕过本应出现的时间修正反馈，使同一事实在两个服务中被不同对待。需使用无损的整数秒/纳秒或等价表示进行比较，不能以 float、静默截断或全局拒绝合法纳秒规避。等价时区、尾零和跨日归一化仍应一致。

### T2（P2）：非法时区分钟被 Python 自动归一化

位置：`workflow-service/src/deepresearch_workflow/agent_protocol.py` 的 `_VALID_AT_PATTERN` 和 `KnownValidAt.real_offset_datetime`。

主审复现 `2026-09-27T00:00:00+17:60`、`2026-09-27T00:00:00+00:99` 均被 ClaimScope 接受。Python 把非法分钟归一化成合法总偏移，现有“总偏移不大于 18 小时”检查无法发现。Java `OffsetDateTime.parse` 会拒绝同样输入。

需在归一化前严格检查时区小时/分钟及 Java 支持的边界：分钟 00–59，绝对偏移至多 18:00，18 小时下分钟必须为 00。JSON Schema 可表达部分和运行时规则都应保持一致；不要只修测试或放宽 Java。

## 主审验证

合并代码的 Python 非集成 240 项、Java 单元 299 项、完整 Java 集成 96 项均零失败、错误、跳过；`mvn -o -q -Pintegration verify` 返回 0。Ruff、冻结运行契约 38 项和知识契约 39 项通过。Java 数量只计本次新生成的 XML，排除一次旧定向测试遗留在 surefire 目录中的 7 项集成报告；不重复计数。T1/T2 使用实际生产方法及本机 Java 21 `OffsetDateTime` 直接复现，未请求模型或网络。

日志保留在 ignored `target/coordinator-offline-review-python.log`、`target/coordinator-offline-review-python.xml`、`target/coordinator-offline-review-maven.log`；Java 使用 Maven 标准 XML 目录。本轮测试全部为离线模型/来源 fixture 与临时测试基础设施，不能据此宣布旧网页、混合或版本真实场景成功。

## 任务分工

继续使用现有两对话，`gpt-6-sol / xhigh`。共同基线为本报告提交所在 `feat/agent-offline-review`，精确 SHA 写入协调交接文件。不另建对话，不扩大功能范围。

### B：修复 T1/T2

从共同基线建立 `feat/agent-time-precision-repair`。负责 `agent_protocol.py`、`agent_runtime.py` 的时间校验/比较、相关时间测试及必要 Java 时间边界回归。复用或增加一个小型纯函数，统一严格解析和精确比较，避免 schema、运行时、原文时刻各自解析产生偏差。

验收至少覆盖：九位小数只差最后一位必须拒绝；同一瞬间的等价时区/尾零通过；合法 1–9 位小数不丢精度；非法 ±00:60/±00:99/±17:60/±18:01 拒绝，合法 ±18:00 接受；原页面修订文本、unknown、反证保留和无进展停机回归不退化。以生产方法及实际 Java 解析/比较核对，不能只测试另写的近似实现。

先提交可供 A 组合的确定接口提交，通过本地协调文件通知 A。不得改 A 的模型/预算/CI/验收脚本，不编辑 A checkout。完成本地提交和精确测试记录后停止，等待主审。

### A：最终组合验证与真实补测准备

从同一基线建立 `feat/agent-live-retest-readiness`。B 修复期间，完善既有验收回执对新诊断的展示：固定错误类别、数字状态、安全字段路径；区分“模型结果不可用”与“实际 token 用量未知”，因为 UNKNOWN 操作现在可以携带已知用量。仅从明确白名单导出，不复制原始响应/异常正文。用离线账本样例证明计数与脱敏正确，不改旧六次实测事实。

给出可执行的补测计划：优先网页和混合，成功后才运行版本、冲突、证据不足；每例保留预期、失败停止规则、每次 8 决策/16 模型/16 工具/180 秒，明确最多需要多少次真实运行。旧阶段已停止，计划准备不等于调用授权。不得通过新 state-dir 或清空账本绕过停止，也不预先把余量当成新授权。

取得 B 的精确提交后，在自己的分支或 ignored Git 对象组合中验证两侧最终代码：Python 非集成、完整 `mvn -Pintegration verify`、Ruff、冻结契约、公开仓库/泄密检查；明确运行所有迁移和 HTTP/PG 用例，不跳过。只测最终组合，除具体失败或源码变化外不重复铺开全量测试。记录提交和解释器，并给出推送后对应 SHA 的 CI 核对步骤，不能把本地通过写成远端通过。

完成本地提交，更新原 runtime handoff，交付 final/tested/peer SHA、日志与仍未解决项。等待主审后再推送或执行补测；当前不部署、不改 PR、不开始长期记忆。

双方仅为本轮接口交接可互发必要消息。原八份文档、正式服务、`.env`、密钥和卷保持原状。离线修复与准备可以并行；真实补测必须在时间修复和组合审查之后串行执行。

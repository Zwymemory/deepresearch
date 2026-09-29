# 第 1 轮修复复审

日期：2026-09-29。复审结论：仍需修复后复验，暂不进入研究记忆与多 Agent 开发。

## 版本与审查方式

| 对象 | 精确提交 |
| --- | --- |
| A 运行与发布修复 | `8e5612bf4906144f4a2c0a8de4c681768e3fbb5d` |
| B 证据与裁决修复 | `28ef1b436c0f3353b1f828e0b0f54cc75eafff76` |
| 主对话合并并独立测试的候选 | `70540dabcd1be7b787af9139023aef76501377eb` |
| 审查分支 | `feat/agent-round1-repair-reviewed` |

原候选分支保留，合并无冲突。当前演示、Dify 发布、正式配置与知识文档未改，未推送 GitHub。

用户允许过长对话新建接续对话。本次为两个开发任务新建了独立只读审查对话；两条旧开发对话保留历史并停止编辑，主对话继续完成此次集成与汇总。新对话均使用 GPT-6-sol / xhigh，先读取精确版本与交接说明。工具未提供准确的剩余上下文容量，因此没有声称即将发生压缩。

- 运行与发布复审：`01a0ed44-fd2c-78e1-b9d5-d749250e8d28`。
- 证据与裁决复审：`01a0ed44-ff0a-7cb0-b42f-fc134aa127d6`。
- 交接说明：`../.codex-handoffs/deepresearch-agent-round1-recheck.md`。

## 主对话独立联合验证

| 检查 | 结果 |
| --- | --- |
| Python 运行、调查、模型、证据协议与既有图/引用/HTTP/repository | 170 通过，0 失败/跳过 |
| Java 所选 Agent/Workflow/认证/来源/引用单元测试 | 153 通过，0 失败/跳过 |
| Java 临时 PostgreSQL 集成 | 40 通过，0 失败/跳过 |
| 其中真实 HTTP/JWT/完整 Spring 应用 | AgentHttpPostgresIT 3 项，已包含于 40 项 |
| 内嵌 Python SQL/恢复检查 | 7 项，包含于一个 Java 测试包装，不重复计为 Java 测试 |
| v0 冻结合约 | 38 + 39 项与跨线门禁通过 |

数据库执行真实 V1–V20 迁移、存储、租约、预算和发布证明。模型回执与来源传输仍为可注入替身；没有执行真实模型或公网来源验收。新增边界复现的通过表示确认缺陷，不能计入修复通过数量。

执行命令：

```sh
# workflow-service 目录
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=src .venv/bin/python -B -m pytest \
  tests/test_agent_runtime.py tests/test_agent_investigations.py tests/test_agent_model.py \
  tests/test_evidence_client.py tests/test_evidence_check.py tests/test_evidence_repair.py \
  tests/test_evidence_acceptance.py tests/test_runner.py tests/test_domain.py tests/test_graph.py \
  tests/test_citation_mapping.py tests/test_query_fidelity.py tests/test_http_contracts.py \
  tests/test_repository.py -q --junitxml=../target/root-recheck-python.xml

# 仓库根目录
AGENT_PYTHON="$PWD/workflow-service/.venv/bin/python" PYTHONDONTWRITEBYTECODE=1 \
  mvn -q --offline -Pintegration -DskipTests=false \
  '-Dtest=Workflow*Test,Agent*Test,*Auth*Test,DifyContextInputsTest,SafeWebReaderTest,EvidenceQuoteTest,EvidenceConfigurationTest,DifyCitationValidatorTest,DifyToolServiceTest,RagflowClientTest,RagflowDifyKbToolGatewayTest,TavilySearchClientTest' \
  -Dit.test=AgentRuntimePostgresIT,AgentHttpPostgresIT,McpKnowledgeLoopIT,EvidenceServiceIT,DifyWebSourcesIT verify
PYTHONDONTWRITEBYTECODE=1 workflow-service/.venv/bin/python -B contracts/agent/v0/validate.py
```

## 上轮直接场景的改进

- 补查会恢复服务端保留的原证据与原关系，简单删除反证、改标签不再直接消除争议。
- 发布改为完整报告，支持部分、争议、被反驳内容及未完成目标均可保留；partial 的正文和终态经过服务端封存，不能改成 SUCCEEDED。
- 不同调查分别维护父检查与结果；核查错误不再覆盖旧 packet 或直接令当前任务 done。
- 发布使用原完成搜索回执；后续同来源搜索不再替换旧证据身份。
- Python/Java 的四种 Unicode 边界空白已有一致回归。

这些改进已覆盖上一轮最小复现，但不等于更广泛的目标完成性与反证连续性已经充分成立。

## 本次剩余问题

### N1：共享调查出现新争议后，旧任务仍为 done（P2）

位置：`workflow-service/src/deepresearch_workflow/agent_runtime.py:445–459`。

同一调查绑定任务 A、B；两者先处于 done。在 B 补查得到 contested 后，代码只将 B 设 blocked，未同步撤回 A 的 done。任务 C 如果依赖 A，仍可通过依赖检查并执行。当前逻辑仅在成功时将 done 广播给相关任务，缺少反方向的同步。

主对话使用合并候选上的真实 `AutonomousResearchGraph.act` 独立复现：`after_new_conflict={A:done,B:blocked,C:pending}`，随后 C 的核查实际执行并成为 done。ledger/repository/backend 为隔离替身，状态转换和依赖检查为实际代码。

此场景的全局报告仍保留 contested，尚未证明它单独造成整份报告误报成功；确定影响是后续任务依据失效的完成状态被放行。

需要撤回受新争议或未完成补查影响的关联目标完成状态，并处理已依赖这些目标的后续结果。保留历史证明；不能通过换任务保持旧完成前提。修复回归应同时覆盖 supported→contested、补查失败及恢复后的依赖判断。

### N2：任务的多个完成标准未逐项核对，仍可只答一部分便成功（P1）

位置：`agent_runtime.py:445–459` 的 done 判定，以及 `AgentEvidenceAuthority.reportGoals` / `EvidenceService.report` 的目标完成状态消费。

任务已保存“核查版本”和“核查每分钟速率”两个明确完成标准。仅版本 Claim 得到支持，速率没有证据，当前代码仍将整个任务设 done；完整报告因只看已有 Claim 与 task.status，能够封存为 complete / SUCCEEDED。

新的运行审查对话已通过实际运行节点、HTTP/JWT 与临时 PostgreSQL 的组合复现。模型和来源内容是隔离替身；未把真实模型语义能力作为该缺陷的前提。

需要建立每个既定完成标准到已核查结果/未解决缺口的可追溯对应，只有所有必需标准满足且依赖有效才可完成。不能靠模型单独自报 done；没有结论映射的标准必须继续显示未完成。标准覆盖校验本身也不能被宣传为语义真值保证。

### N3：多调查合并后，报告仍按全局四份知识库原文限制收尾（P2）

位置：`src/main/java/com/deepresearch/evidence/EvidenceService.java:342–343`。

每次调查可分别合法完成，完整报告却把所有调查的知识库原文计入同一个最多四份的集合；第五份触发 PUBLICATION_TOO_LARGE，即使共享工具预算仍有余量。这个隐藏的汇总限制与当前多调查报告路径没有对齐。

修复应在既有统一预算内处理整份报告所需的原文复核，或提前给出明确且可封存的不足状态。不要提高全局预算或静默删除结论来规避该问题。单次核查的四份材料限制与整份报告的复核总量需要分别定义。

### N4：冲突的条件声明可被较早快照当成“范围澄清”排除（P1）

位置：`EvidenceService.declaredConditions` 与 `EvidenceAdjudicator.scopeClarification`（后者第 161–175 行）。

较新原文中存在不同的条件声明时，条件解析把它退回与“没有声明”相同的状态。补查便可用同文档较早的单一 mode=legacy 声明排除该反证；使用与首轮完全相同的三份材料，也能从 contested 变为 supported，并生成成功报告。新的证据审查对话已用真实服务和临时 PostgreSQL 复现。

必须区分条件缺失、明确条件与内部冲突；范围澄清须证明适用于被排除的具体原文和修订。重复材料、较早快照或同 URL 不能自动解决声明冲突。抓取时间也不等于来源修订或事实权威；无法建立适用关系时保留 contested。

## 新对话的定向审查记录

- 运行审查：`../.codex-handoffs/deepresearch-agent-round1-recheck-runtime.md`。
- 证据审查：`../.codex-handoffs/deepresearch-agent-round1-recheck-evidence.md`。

本报告 N1–N4 是下一次修复的统一编号，各独立报告编号可能不同。针对本次确定缺陷修复后，再固定新候选复验；不重新扩大本轮功能范围。

## 能力和阶段边界

超过单次证据容量后明确保留不足，本轮可以作为受限停止策略，不据此要求无界批量核查。不能静默截断反证或把容量失败当作成功。

真实模型语义、公网来源、受控实验生产适配、研究记忆和多 Agent 协作仍未在本次联合验证中完成。修复复审通过后，先做固定候选的小规模真实验收，再决定是否进入研究记忆。

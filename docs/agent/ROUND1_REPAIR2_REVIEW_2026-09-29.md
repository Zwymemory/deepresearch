# 第 1 轮第二次修复：主审复验

日期：2026-09-29。

## 判定

上轮 N1–N4 的定向修复及联合回归通过，是有效升级。最终联合放行仍需补上一项已复现的报告状态校验缺口（F1，P2）。项目现有 Python 静态检查另有 49 项诊断（F2，P2，历史债务），不能宣称 CI 全绿。

本轮范围到此固定：补全整份报告的当前状态校验，清理现有静态门禁；完成后固定候选复验，再做小规模真实模型与来源验收。尚不进入长期记忆、多 Agent 或发布。

## 精确版本

| 对象 | 提交 |
| --- | --- |
| A 完成标准与依赖修复 | `468922688db75a1cc4d904249409b6926244234b` |
| B 范围裁决与完整报告修复 | `6155a4f245f25b071e3bcec0242ae2ae569dd55c` |
| 主审独立合并并测试的候选 | `d7177b4545298f09c50742552a0cc0d214e02f0d` |
| 集成分支 | `feat/agent-repair2-reviewed` |

两方 final_sha=tested_sha，且相互验证了对方精确最终提交。主审重新合并，无冲突，旧分支和用户文件保留；没有修改生产实现、正式服务、配置或知识库，没有 push 或部署。

## 主审独立执行

| 检查 | 结果 |
| --- | --- |
| Python 全部非集成测试 | 211 通过，0 失败/跳过 |
| 所选 Java 单元测试 | 156 通过，0 失败/跳过 |
| 所选 Java 临时 PostgreSQL 集成 | 60 通过，0 失败/跳过 |
| 其中真实完整 Spring HTTP/JWT | AgentHttpPostgresIT 7 项，已包含在 60 项中 |
| 其中 Python SQL/恢复检查 | 7 项，包含于一个 Java wrapper，不重复计数 |
| v0 冻结合约 | 38+39 与跨线门禁通过 |
| Python 静态检查 | 失败，49 项；新增诊断 0 的差分由 A 提供，主审独立确认当前 49 项 |
| 主审新边界复现 | 1 个独立 PG 方法通过，表示确认 F1，不能计为修复通过 |

Java 数量按本次指定测试类统计，排除 target 中早先运行遗留的 XML。模型判断、来源传输仍为替身。测试执行了真实 V1–V21 迁移、存储、预算、身份和封存，但不证明真实模型规划/语义、公网来源或线上界面已验收。

执行：

```sh
# workflow-service
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=src .venv/bin/python -B -m pytest tests -m 'not integration' -q --junitxml=../target/root-repair2-python.xml

# 仓库根目录
AGENT_PYTHON="$PWD/workflow-service/.venv/bin/python" PYTHONDONTWRITEBYTECODE=1 \
  mvn -o -q -Pintegration -DskipTests=false \
  '-Dtest=Workflow*Test,Agent*Test,*Auth*Test,DifyContextInputsTest,SafeWebReaderTest,Evidence*Test,DifyCitationValidatorTest,DifyToolServiceTest,RagflowClientTest,RagflowDifyKbToolGatewayTest,TavilySearchClientTest' \
  -Dit.test=AgentRuntimePostgresIT,AgentHttpPostgresIT,McpKnowledgeLoopIT,EvidenceServiceIT,DifyWebSourcesIT verify
PYTHONDONTWRITEBYTECODE=1 workflow-service/.venv/bin/python -B contracts/agent/v0/validate.py
workflow-service/.venv/bin/ruff check workflow-service/src workflow-service/tests --output-format json
```

本次日志：`/tmp/deepresearch-root-repair2-python.log`、`/tmp/deepresearch-root-repair2-java.log`、`/tmp/deepresearch-root-repair2-freeze.log`、`/tmp/deepresearch-root-repair2-lint.json`。

## 上轮四项验收

- N1：共享调查争议/失败能撤销关联标准的满足状态；已消费失效前提的下游保持 stale，前提恢复后仍需重新核查。Python 状态和 Java 独立证明均有维护回归。
- N2：原生标准有稳定身份、显式 Claim 绑定和服务端当前证明。实际 HTTP/JWT/Python/低权限 PG 链路中，缺一项只能 partial/INSUFFICIENT_EVIDENCE；全部覆盖和合法 refuted 有成功正例；legacy 无绑定不默认完成。
- N3：多调查 2+3 知识库原文可在原 16 次工具预算内完整封存，实际用 14 次；预算不足仍拒绝封存支持子集，历史保留。
- N4：条件缺失、明确条件与内部冲突分开处理；较旧/重复/较晚重抓的快照均不能在无可信修订证明时抹除反证。同一原文自身明确范围的正向路径保留。跨快照自动澄清目前保守受限。

## F1 [P2] 只重验目标完成性，旧报告仍可遗漏未绑定调查的新补查

位置：`src/main/java/com/deepresearch/workflow/WorkflowRepository.java:93–97`；同类校验在 `AgentPublicationController.java:96–97`。

新实现封存和 finalize 时比较 `goals`，这能拦住已绑定标准所依赖调查的变化。然而系统明确允许额外未绑定标准的研究 Claim，它们同样进入整份报告。此类调查的新检查不改变任何 ReportGoal，旧报告的整份证据状态便没有被核对。

主审实际复现：

1. 先形成一个合法的未绑定标准的研究调查 U（版本结论），再形成覆盖原生标准的独立调查 C（速率结论）。两项完成核查均 supported。
2. 完整报告包含两项 Claim，封存为 complete。
3. 同一有效 run/lease 下，U 开始合法的第 1 轮补查：更新当前调查操作、实际调用 EvidenceService.prepare，补查等待模型完成。C 的标准证明未变化。
4. 此时直接请求新的完整报告，会得到 partial，并明确列出 U 的未完成 check_id；原报告的 goals 与当前 goals 完全相同。
5. WorkflowRepository 仍认可旧 SUCCEEDED 报告；实际 WorkflowService.finalizeRun 在事务中接受旧正文与引用，数据库最终状态为 SUCCEEDED。

最终输出：`fresh_report=partial old_seal_accepted=true actual_final=SUCCEEDED`。

证明边界：使用精确候选的独立 Git archive、真实 V1–V21 临时 PG、实际 authority/service/report/seal/repository/finalize；模型回执、来源传输及 service-auth 沿用隔离 fixture。本例不是完整 HTTP/JWT 或正常顺序执行的全图端到端复现；它确认的是有效调用在封存与最终提交之间改变研究状态时，服务端允许旧报告成功。不能据此声称普通串行每次都会触发，也不能借本轮其他 HTTP 测试为该用例背书。

修复方向：封存和最终提交需绑定整份研究状态，覆盖所有调查的当前检查、待完成/失败尝试与保留缺口，包括未绑定标准的调查；或在明确状态转换处禁止继续改变研究状态。不能只比较 goals、删掉未绑定结果、删除待完成记录、忽略争议，或在 finalize 重跑模型/公网读取。正常报告和精确重放必须仍可完成；出版操作本身的写入不可使自己的证明失效。

维护回归至少覆盖：未绑定调查在报告封存后产生 pending/failed/contested；封存期间变化；无变化的成功与重放；部分报告的状态一致性。失败/未知尝试在报告生成之前已经存在时也应明确呈现其当前缺口。

复现资料：

- `target/root-repair2-probe-60wk46oe/combined/src/test/java/com/deepresearch/workflow/AgentRuntimePostgresIT.java`，方法 `reviewSealedReportCanIgnoreNewPendingSupplementOfAnUnboundInvestigation`。
- `/tmp/deepresearch-root-repair2-probe-java-final.log`。
- `target/root-repair2-probe-60wk46oe/source-verification.json`：685 个归档输入中仅上述隔离 IT 文件追加复现，生产实现与候选逐一一致。

复现命令在 combined 目录（先将 AGENT_PYTHON 设置为已安装项目依赖的 Python 解释器；本地 target 产物与交接文件不随仓库发布）：

```sh
AGENT_PYTHON="$AGENT_PYTHON" \
PYTHONDONTWRITEBYTECODE=1 mvn -o -q -Pintegration -Dtest=__RootReviewOnly__ \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dit.test=AgentRuntimePostgresIT#reviewSealedReportCanIgnoreNewPendingSupplementOfAnUnboundInvestigation' verify
```

## F2 [P2，历史门禁] 49 项 Python 静态诊断仍阻止既有 CI

`.github/workflows/ci.yml` 的 sidecar 检查会先执行 `ruff check workflow-service/src workflow-service/tests`，当前独立执行返回 1，导致后续该步骤无法全绿。

分类：E501 33 项、RUF001 11 项、I001 3 项、ASYNC240 2 项。A 的精确差分材料记录基线 50 项、最终 49 项、新增 0；本主审没有把历史问题归因于此次修复，也没有独立重新扫描该历史基线。

清理应保留现有规则，不整体忽略、删除测试或改变业务协议。范围为现有 10 个 Python 文件的格式、明确 Unicode 注释/文本处理和必要的异步文件访问处理；完成后执行既有非集成与相关 PG 回归。

## 接续安排

继续使用现有两个接续对话，不创建新对话：

- A「DeepResearch Agent 运行与发布复审」`01a0ed44-fd2c-78e1-b9d5-d749250e8d28`：负责 F1；独占 Java workflow/evidence 的必要状态证明、报告及测试；如确需向前迁移，可用尚未使用的 V22，V17–V21 和冻结 v0 不改。
- B「DeepResearch Agent 证据与裁决复审」`01a0ed44-ff0a-7cb0-b42f-fc134aa127d6`：负责 F2；本次独占 Python 静态清理，不编辑 A 的 Java 实现。若 A 需要 Python 变更，先说明确切文件并协调，不能并行覆盖。

以包含本报告的后继文档提交为共同基线。A 使用 `../deepresearch-github` 新分支 `feat/agent-report-state-repair`；B 使用 `仓库根目录` 新分支 `feat/agent-static-gates`。保留旧分支、用户 `docs/interview/`、复现资料及现有服务。技术任务继续 GPT-6-sol / xhigh。

更新各自现有 repair2 交付文档中的 phase、final_sha=tested_sha、tested_peer_sha、修复与验证边界即可，不新增一套交接文档。完成后停止，等待固定组合复验。禁止部署、push、改正式配置/密钥/卷/知识文档、真实付费调用和下一阶段功能开发。

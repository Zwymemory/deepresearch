# DeepResearch Agent 层评测报告与复现手册

> 评测对象：Python/LangGraph Durable Workflow（`langgraph-pwrs`）
> 数据集：`project-knowledge-gold`，契约版本 `KB-PROJECT-1.1`
> 数据快照：2026-08-26
> 报告状态：**v1.1 全量在线评测待填入；当前文档只定义口径、证据边界和复现步骤**

## 1. 技术摘要

Agent 质量不能只用一个“成功率”概括。本项目固定同时报告四类结果：

1. **交付结果**：正样本是否得到 `SUCCESS`，负样本是否正确进入 `NO_EVIDENCE`；
2. **严格质量**：一次执行配置的所有断言是否全部通过；
3. **证据与工具**：引用能否唯一解析、预期事实是否由被引证据支持、工具选择是否符合契约；
4. **运行可靠性**：Reviewer 是否打回、修订是否挽救任务、故障注入后是否正确恢复。

`project-knowledge-gold` v1.1 有 29 个唯一 case：25 个有项目证据的正样本和 4 个应安全拒答的负样本。v1.1 修改了知识材料粒度和部分 Gold 语义，因此它与 v1.0 是不同 cohort；后续数字必须分别报告，不能把差值全部归因于模型或代码优化。

当前 Harness 已直接汇总严格通过率，以及工具、答案、结束、引用、grounding、事实、安全、状态等按适用 case 计算的指标。Reviewer 修订率需要从事件汇总；真实恢复成功率必须来自明确的 kill/restart 故障注入，普通在线评测或出现 `RUN_RESUMED` 事件都不能替代。

## 2. v1.1 全量结果（运行完成后填）

以下占位符只能用保存的 `/api/eval/agent` JSON 主产物和对应 PostgreSQL 事件填写。不要从控制台截图、单条 demo 或历史 v1.0 数字推算。

### 2.1 运行身份

| 字段 | v1.1 全量值 |
|---|---|
| Evaluation ID | `PENDING_V1_1_FULL_RUN` |
| Dataset fingerprint | `PENDING` |
| 生成时间 | `PENDING` |
| 模式 | `langgraph-pwrs` |
| 模型 ID / revision | `PENDING` |
| 温度及结构化输出配置 | `PENDING` |
| seed / trials | `PENDING` |
| 唯一 case | 29（25 positive / 4 negative） |
| 计划执行 / 实际执行 | `29 / PENDING` |
| Suite 是否完整 | `PENDING` |
| 原始 JSON 产物 | `PENDING_PATH` |

### 2.2 核心结果

| 指标 | 分子 / 分母 | 比率 | 95% CI | 解释 |
|---|---:|---:|---:|---|
| 严格通过率 | `PENDING / 29` | `PENDING` | `PENDING` | 所有已配置断言同时通过 |
| 正样本交付率 | `PENDING / 25` | `PENDING` | `PENDING` | 正样本得到可交付 `SUCCESS` |
| 预期终态正确率 | `PENDING / 29` | `PENDING` | `PENDING` | 正样本成功、负样本正确拒答均计正确 |
| 执行结束率 | `PENDING / 29` | `PENDING` | `PENDING` | 配置了 `requireFinished` 且 `finished=true` |
| 引用契约有效率（case） | `PENDING / 25` | `PENDING` | `PENDING` | 只统计配置引用断言的正样本 |
| 引用标记解析率（marker） | `PENDING / PENDING` | `PENDING` | — | 唯一解析 marker / 全部公开 marker |
| Grounding 通过率 | `PENDING / 25` | `PENDING` | `PENDING` | 确定性事实—引用—证据检查 |
| 事实完整率 | `PENDING / 25` | `PENDING` | `PENDING` | `expectedFacts` 是否出现在答案中 |
| 工具调用正确率 | `PENDING / 29` | `PENDING` | `PENDING` | 期望、禁止、allowlist 与顺序断言 |
| Reviewer 打回率 | `PENDING / PENDING_REVIEWED` | `PENDING` | `PENDING` | 发生 `REVISION_STARTED` / 到达 Reviewer |
| 修订挽救率 | `PENDING / PENDING_REVISED` | `PENDING` | `PENDING` | 打回后最终严格通过 / 被打回执行 |
| 故障恢复成功率 | `N/A` | `N/A` | `N/A` | 普通 29-case 评测不包含故障注入 |

### 2.3 失败分层

| 失败层 | 数量 | 代表性 error / assertion | 处理结论 |
|---|---:|---|---|
| 执行错误 | `PENDING` | `MODEL_*`、timeout、sidecar error | `PENDING` |
| 错误终态 | `PENDING` | `STATUS_ASSERTION_FAILED` | `PENDING` |
| 引用契约 | `PENDING` | `CITATION_ASSERTION_FAILED` | `PENDING` |
| Grounding / 事实 | `PENDING` | `GROUNDEDNESS_ASSERTION_FAILED`、`FACT_ASSERTION_FAILED` | `PENDING` |
| 工具 / 参数 | `PENDING` | `TOOL_ASSERTION_FAILED`、`TOOL_ARGUMENT_ASSERTION_FAILED` | `PENDING` |
| 角色 / 任务 / 修订预算 | `PENDING` | workflow-specific assertions | `PENDING` |

每个失败必须保留 `case id + trial + runId + failureReasons + executionErrorCode`。同一执行可同时属于多个断言失败，失败层数量不能相加后当作失败执行总数。

## 3. 指标字典：分母先于百分比

### 3.1 统计单位和 cohort

Harness 的最小统计单位是一次 `case × mode × trial` 执行，不一定等于一个唯一问题。若 29 个 case、2 个 mode、3 个 trial 全部运行，则严格通过率分母是 174，不是 29。

报告每个百分比时至少同时给出：

- 数据集版本与 fingerprint；
- mode、模型 revision、seed 和 trial 数；
- 分子、适用分母和 suite 是否完整；
- 执行错误是否计入分母；
- 对多 trial 结果给出 Wilson 95% 区间，同时说明同一 case 的 trial 并非完全独立样本。

如果 `complete=false` 或实际执行少于 `plannedExecutions`，只能称为“部分结果”，不能作为最终门禁成绩。

### 3.2 严格通过率

```text
Strict Pass Rate
= CaseResult.passed == true 的执行数
  / 全部实际执行数
```

`CaseResult.passed` 要求该 case 配置的所有断言都通过，并且没有执行错误。它同时受答案、工具、引用、grounding、事实、状态、角色、Worker 上限、修订上限等影响。因此它最严格，但不能告诉我们究竟坏在哪一层，必须与分项指标和 `failureSummary` 一起报告。

### 3.3 三个容易混淆的“完成率”

不要只说“任务完成率”。本项目拆成三项：

| 名称 | 推荐公式 | 用途 |
|---|---|---|
| 正样本交付率 | 正样本中 `finished=true` 且 `status=SUCCESS` 的执行 / 正样本执行 | 衡量有证据问题最终交付答案的能力 |
| 预期终态正确率 | `assertions.status=PASS` / 配置 `expectedStatus` 的执行 | 同时奖励正确成功和正确拒答；本数据集首选总览指标 |
| 执行结束率 | `assertions.finished=PASS` / 配置 `requireFinished=true` 的执行 | 只说明进入受支持终态，不代表答案质量正确 |

负样本正确返回 `NO_EVIDENCE` 是安全成功，不应被“只数 SUCCESS”的指标误判为失败；反过来，错误地对正样本返回证据不足也不能算交付成功。

### 3.4 引用契约有效率

Harness 的 case 级引用有效率为：

```text
Citation Case Validity
= assertions.citations=PASS
  / assertions.citations.applicable=true 的执行
```

引用断言仅在 case 配置了 `expectedCitationMarkers` 时适用。一次执行通过需要：

- 期望 marker 出现在答案中；
- 答案中每个 `[来源N]` 都能解析到恰好一个公开 evidence；
- 没有越界、未知或歧义 marker。

它证明的是“编号到公开 source ID 的映射确定”，不证明来源权威，也不证明每个自然语言论断真实。

为定位“少数 marker 拖垮整个 case”的情况，再报告一个不作为硬门禁的 marker 级诊断：

```text
Marker Resolution Rate
= resolvedCitationMarkers 的不同 marker 数
  / (resolved + invalid + ambiguous 的不同 marker 数)
```

分母为 0 时记 `N/A`，不能记 100%。case 级有效率是主指标，marker 级只用于诊断，二者不能混称“引用准确率”。

### 3.5 Grounding 与事实完整率

当前 Harness 的 grounding 是可复现的确定性断言，不是 LLM Judge：

```text
Grounding Rate
= assertions.grounding=PASS
  / assertions.grounding.applicable=true 的执行
```

通过条件是：引用断言通过、至少一个引用被唯一解析，并且答案中出现的 `expectedFacts` 能在同句附近找到引用，且被引 evidence 的标题或支持文本也包含该事实。`facts` 断言另行检查预期事实是否完整出现在答案中。

边界必须明确：

- `grounding=PASS` 不能证明未列入 `expectedFacts` 的所有句子都被支持；
- 事实未写进答案可能导致 `facts=FAIL`，但未必导致 `grounding=FAIL`；
- 当前是规范化子串检查，对同义改写、否定范围和跨句引用的理解有限；
- 来源真实性、时效性和权威性仍需人工审核或经过校准的语义 grader。

因此报告应同时展示 citation、grounding、facts 三列，禁止只选最高的一列称“回答准确率”。

### 3.6 工具调用正确率

```text
Tool Correctness
= assertions.tools=PASS
  / assertions.tools.applicable=true 的执行
```

一次工具断言通过要求：成功工具包含全部 `expectedTools`，不包含 `forbiddenTools` 和 allowlist 外工具，并满足 `expectedToolSequence` 的有序子序列约束。

以下边界容易被忽略：

- 这里只把成功执行的工具放入 `actualTools`；被策略拒绝或执行失败的尝试保留在 `attemptedTools`、`policyDeniedTools`、`failedTools` 诊断中；
- 参数正确性由 `assertions.parameters` 单独计算，不能由工具名正确推出；
- 工具正确率不等于任务完成率，也不衡量检索到的内容是否相关。

### 3.7 Reviewer 打回率与修订挽救率

Reviewer 指标当前不在顶层 `metrics.assertions` 中，需要按 run 的持久事件汇总：

```text
Reviewer Revision Rate
= 至少出现一次 REVISION_STARTED 的 run
  / 至少出现一次 REVIEW_COMPLETED 的 run

Revision Rescue Rate
= 出现 REVISION_STARTED 且最终严格通过的 run
  / 出现 REVISION_STARTED 的 run
```

分母选择“到达 Reviewer”而不是全部 run，是为了不让 Planner/Worker/模型早期失败机械压低打回率。打回率高不天然是坏事：可能是初次证据覆盖不足，也可能说明 Reviewer 更严格。必须结合修订挽救率、额外 token、延迟和最终 `INSUFFICIENT_EVIDENCE` 一起解释。

`maxRevisionCycles=1` 当前只证明修订次数没有越界；它不是 Reviewer 质量分数。建议额外记录首个 `REVIEW_COMPLETED.safe_payload.sufficient`、`revisionTaskCount` 和 `revisionBlockedReason`。

### 3.8 故障恢复成功率

正常 Harness 运行不能产生恢复成功率。有效的恢复实验必须在预先定义的 kill point 真的终止 runner，并记录注入已触发：

```text
Recovery Success Rate
= 恢复后达到预期终态且全部恢复不变量通过的有效注入执行
  / 注入已确认触发的有效执行
```

至少需要同时验证：

- 新实例使用同一 `runId/thread_id` 从 checkpoint 继续；
- 已完成工具调用不重复执行，receipt 可重放；
- stale claim 不能写进度或 final；
- 预算没有因恢复被重置；
- 最终答案和引用契约仍通过；
- SSE 从保存游标重放时无事件缺失，客户端重复展示数为 0。

只看到 `RUN_RESUMED`，或 `requireDurableResume` 断言通过，不足以证明上述不变量。当前真实外部模型 kill/restart 尚未完成，报告必须写 `N/A / 待故障注入`，不能写 0%，更不能写 100%。

## 4. 代码审计后的证据边界

| 能力 | 当前证据 | 可以说 | 不能说 |
|---|---|---|---|
| 断言适用分母 | `AgentMetricsCalculator` 使用 `applicable` 计数并给 Wilson 95% CI | 分项率不会被未配置 case 稀释 | 所有分项都有同一个分母 |
| 严格 case 门禁 | `AgentAssertionEngine` 将所有配置断言合并为 `passed` | 严格通过需要全项通过 | 严格通过率就是任务完成率 |
| 引用映射 | 公开 citations 索引、evidence receipt 和 invalid/ambiguous 诊断 | marker 必须唯一映射 | 自动证明来源权威和全文事实正确 |
| Reviewer 次数限制 | `REVISION_STARTED` 事件和 `maxRevisionCycles` per-case 断言 | 可核对是否超过一次修订 | 顶层响应已直接提供 Reviewer 打回率 |
| checkpoint / fencing | Python/PostgreSQL 与 Java/Testcontainers 分层测试 | 恢复机制和安全约束已有测试 | 真实 provider kill/restart 成功率已经测出 |

还有一个报告实现细节：`CaseResult.assertions` 已包含 `workflowRoles`、`workerTasks`、`revisionCycles`、`durableResume` 和 `workflowStatus`，这些断言也会影响严格通过；但当前顶层 `Metrics.assertions` 只聚合到 `status`。因此 v1.1 报告必须从 `cases[].assertions` 或数据库事件补算工作流专项指标，不能把顶层缺失误写成 100% 或 0%。

## 5. v1.1 全量复现步骤

### 5.1 固定运行前提

1. 记录 Git commit、Docker image digest、Java/Python 依赖锁、DeepSeek 模型 ID/revision 和温度；
2. 确认 workflow 已启用，Java、workflow、PostgreSQL、Elasticsearch、reranker 均健康；
3. 运行 `scripts/import-project-kb.sh`，确认 8 份 `docs/kb-project` 文档均为当前版本；
4. 保存 Gold 文件及其 fingerprint，不在一次评测过程中修改文档、索引或 Gold；
5. 使用 ADMIN Bearer token；密钥和 token 不写入报告或命令历史产物；
6. 默认先跑 1 trial 的全量门禁，再在预算允许时以相同配置跑 3 trials 稳定性评测。

### 5.2 一次 29-case 门禁

```bash
mkdir -p reports/evaluation

curl --fail-with-body --silent --show-error \
  -X POST http://localhost:8080/api/eval/agent \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "dataset":"project-knowledge-gold",
    "limit":29,
    "modes":["langgraph-pwrs"],
    "trials":1,
    "seed":20260827,
    "suiteBudget":{
      "maxCases":29,
      "maxExecutions":29,
      "maxDurationMs":1800000,
      "maxTotalTokens":500000,
      "maxTotalCost":2
    },
    "qualityGate":{
      "minPassRate":0.80,
      "minToolAccuracy":0.90,
      "minAnswerAccuracy":0.80,
      "minFinishedRate":1.00,
      "maxAvgRounds":4,
      "minCitationAccuracy":0.90,
      "minGroundedRate":0.90,
      "minSecurityRate":1.00,
      "requireEachMode":true,
      "criticalFailuresZero":true
    }
  }' > reports/evaluation/project-knowledge-gold-v1.1.json
```

这条请求会调用在线模型并产生费用。不要同时请求 `/api/eval/agent/report`，因为该接口会重新执行一遍评测，而不是渲染前一个 JSON。

### 5.3 产物完整性检查

```bash
jq '{
  evaluationId,
  schemaVersion,
  datasetFingerprint,
  complete,
  uniqueCases,
  plannedExecutions,
  actualExecutions: .totalCases,
  strict: {passed: .passedCases, total: .totalCases, rate: .passRate},
  assertions: .metrics.assertions,
  failureSummary,
  warnings
}' reports/evaluation/project-knowledge-gold-v1.1.json

jq -r '.cases[]
  | select(.passed == false)
  | [.id, .trial, .runId, .status, .executionOutcome,
     (.failureReasons | join(",")), (.executionErrorCode // "")]
  | @tsv' reports/evaluation/project-knowledge-gold-v1.1.json
```

验收时先检查 `complete=true`、`totalCases=plannedExecutions=29` 和 fingerprint，再读比率。执行错误、断言失败、预期证据不足必须分开统计。

### 5.4 Reviewer 事件补算

先从 JSON 提取本次 run ID，再只对这些 run 查询事件：

```bash
jq -r '.cases[].runId | select(. != null)' \
  reports/evaluation/project-knowledge-gold-v1.1.json \
  | sort -u > reports/evaluation/project-knowledge-gold-v1.1-run-ids.txt
```

在 PostgreSQL 中按这份 run ID 白名单汇总 `agent_workflow_event`：以出现 `REVIEW_COMPLETED` 的不同 `run_id` 为打回率分母，以出现 `REVISION_STARTED` 的不同 `run_id` 为分子；再把这些 run 与 JSON 的 strict `passed` 连接，得到修订挽救率。不要查询整个数据库后把其他 demo run 混入本次 cohort。

### 5.5 Bad case 归因顺序

对每个失败按以下顺序定位，避免只改 prompt：

1. **执行层**：`executionOutcome=ERROR`、稳定错误码、模型 schema/timeout；
2. **终态层**：正样本意外 `NO_EVIDENCE`，或负样本错误 `SUCCESS`；
3. **检索层**：receipt 是否包含目标 fact card，精确 identifier 是否在最终 TopK；
4. **引用层**：marker 是否越界、未知、歧义，public citations 是否完整；
5. **答案层**：事实缺失、否定误判、同义改写导致的 literal assertion；
6. **预算层**：Worker 总任务数、修订次数、token/费用是否触顶；
7. **可靠性层**：仅在专项故障注入中判断恢复，不从普通失败反推。

修复 Gold 或知识材料后必须升级 cohort/契约版本并重建索引；修复代码后保留原始失败产物和新产物。两者都变更时，不得写成纯模型能力提升。

## 6. 报告发布规则

对外可安全使用的最小表述模板：

> 在 `project-knowledge-gold` `KB-PROJECT-1.1` 的 29 个 case 上，固定模型 revision、seed 和单次 trial，Durable Workflow 的严格通过率为 **X/29**，预期终态正确率为 **Y/29**，正样本引用契约有效率为 **Z/25**；结果来自 evaluation ID `...`。该评测验证项目知识问答与确定性引用/grounding 契约，不等于生产准确率。真实 provider kill/restart 恢复成功率尚未测量，因此单独标为 `N/A`。

发布前必须同时保存：原始 JSON、dataset fingerprint、模型/镜像清单、失败 case 表和 Reviewer 事件汇总。若只有一次 trial，应称“评测快照”，不称稳定线上水平。

## 7. 下一步门禁

1. 完成 v1.1 29-case 单 trial 全量评测并填入第 2 节；
2. 对失败按执行、终态、检索、引用、答案、预算分层，不把同义词断言问题误判成 Agent 崩溃；
3. 预算允许时跑 3 trials，报告均值、case 波动和 Wilson 区间；
4. 增加可确认触发的 runner kill/restart Harness，再首次发布恢复成功率；
5. 为 Reviewer 指标增加正式顶层聚合字段，避免长期依赖手工事件查询；
6. 最终把通过门禁的数字同步到简历和面试介绍，未通过或无证据的指标继续标为待验收。

## 8. 证据索引

- Gold 与 cohort 说明：`testdata/eval/project-knowledge-gold.jsonl`、`testdata/eval/project-knowledge-gold.README.md`
- 项目事实材料：`docs/kb-project/01-architecture-and-trust-boundaries.md` 至 `07-agent-evaluation-fact-cards.md`
- 汇总口径：`AgentMetricsCalculator`
- 单 case 断言：`AgentAssertionEngine`
- Workflow 收据与公开 citation 映射：`WorkflowHarnessAdapter`、`AgentEvaluationArtifact`
- Harness 编排与 suite 完整性：`AgentHarnessService`
- 恢复证据边界：`docs/kb-project/02-checkpoint-and-crash-recovery.md`、`06-verification-boundaries.md`

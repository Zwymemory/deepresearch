# 统一版本现场基线：RAGFlow + Dify（2026-09-28）

本次 Java 代码包含 Dify/Java 固定提交 `0a75f5f`，在本评测分支 merge `d6b9d5b` 上打包运行；Dify 已发布的 Evidence v1 Workflow 在整个 37 题基线期间保持不变。项目语料是八份公开合成 `docs/kb-project` 文档，Java registry 中 8/8 的 RAGFlow 映射与本地文档状态均为 `DONE`。本机忽略文件 `.env` 的 dataset ID 指向另一知识库，首轮正例探针因此返回 0 条；正式采样进程只覆盖为这八份映射所属的 dataset，未改动 `.env`。该首轮配置失败不计入质量分数。

[去敏原始采样](../../integrations/ragflow/showcase_dify_baseline_capture_2026-09-28.json)与[机器计分](../../integrations/ragflow/showcase_dify_baseline_score_2026-09-28.json)包含每题的条件、状态、检索探针、答案、引用回查和延迟。运行命令：

```sh
python3 integrations/ragflow/showcase_eval.py collect \
  --mode ragflow-dify --base-url http://127.0.0.1:8080 \
  --conditions /tmp/showcase-dify-conditions.json \
  --output /tmp/showcase-dify-full.json
python3 integrations/ragflow/showcase_eval.py score \
  --capture /tmp/showcase-dify-full.json \
  --output /tmp/showcase-dify-score.json
```

Token 与 RAGFlow API Key 仅从本机环境传入；条件文件记录代码、模型、语料哈希、预算和主机信息，不含凭据或 dataset ID。采集器在持久化前把来源 ID 稳定哈希，并遮蔽常见 JWT、邮箱、手机号。公开采样仍须人工复查自由文本。

## 质量与调用量

| 指标 | 固定版本观察 |
|---|---:|
| 完整集合 | 37/37 题各一次（原有 29 + 新题 8） |
| 终态 | `SUCCEEDED` 26、`FAILED` 6、`INSUFFICIENT_EVIDENCE` 5 |
| 正例检索探针完整事实 | 29/31；平均事实覆盖 0.978 |
| 正例最终成功终态 | 25/31；事实准确率仍待逐条人工审阅 |
| 成功发布引用的直接 RAGFlow chunk 回查 | 26/26 组全通过；不等于所有论断都被来源支持 |
| 六道负例的自动严格契约 | 2/6；四道既有边界拒答无引用，纯无证据新题的检索探针仍有 3 条候选 |
| 端到端 p95 | 29.206 秒，样本数 37；检索探针与答案调用分开计时 |
| Dify 报告的总 token | 37/37 有值，合计 246,865；模型调用次数、工具次数与费用未可靠取得 |

31 道正例中，两个新多事实问题的独立检索探针各漏一个事实短语；这只是另一次 debug 检索，不代表对应工作流实际工具回执。正例失败中，`project-kb-009` 和 `project-kb-016` 被 Dify 最终引用校验拒绝；另外四道正例在 Dify 业务输出成功后被 Java 的五来源上限挡住（模型列出 6–7 个来源）。这些失败均未发布候选答案。对两个 Dify 校验失败，模型引用的来源集合与正文所指证据序号集合相同，但顺序不一致；可在逐项验证后按证据首次出现顺序确定性压紧编号。四道既有安全边界题的检索探针均找到边界证据，Dify 却选择无引用的 `INSUFFICIENT_EVIDENCE`；这属于证据展示和答复质量缺口。新边界拒答题 `showcase-holdout-008` 则成功给出引用。

## 真实前端与恢复场景

- 浏览器在 `demo.html` 签发 USER 身份，勾选知识库工具并发起真实 Dify 工作流。一次正例因引用门禁失败，页面显示 `FAILED` 与零结构化引用；浏览器控制台无错误。纯无证据题显示“可信证据不足”、零引用和完整事件游标，未向用户暴露候选答案。
- SSE 客户端读取两条事件后断开，携 `Last-Event-ID` 重连，收到后续 46 条事件；没有重复 ID，所有 ID 前进且顺序正确。该 run 最终因答案校验失败，不影响持久事件重放的观测结论。
- 在已绑定且执行中的 Dify run 上取消，Java 立即返回 `CANCELLED`、答案为空；远端停止状态先为 `REQUESTED`，约 3 秒后变成 `CONFIRMED_STOPPED`。
- 已绑定 run 执行时重启 Java，重启后重新对账并进入 `FAILED`，错误码 `DIFY_WORKFLOW_FAILED`，未发布答案。该场景证明失败状态可以收敛，尚未证明任意重启点都能成功恢复答案。
- 停止 Dify API 后创建 run，约 7.9 秒内 Java 进入 `DISPATCH_UNKNOWN`；恢复 Dify API 后仍保持该终态，远端没有绑定 run，也未盲目重发。
- 对执行中 run 用同一 `callId`、相同参数两次请求 Java Dify 工具接口，安全结果完全一致且只有一条 receipt；同一 `callId` 改参数得到 `CALL_ID_CONFLICT`。

这些是真实链路的定向故障样本，不是可靠性成功率或容量压测。文档失效时的在线引用撤销、长时运行、跨实例和任意断点恢复仍未在本轮完成。下一步用另一固定版本验证确定性引用压紧与边界拒答，再对照旧路径重跑完整集合；不能拿 2026-09-27 的旧 46.9 秒延迟替代该对照。

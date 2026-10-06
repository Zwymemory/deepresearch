# 阶段三：研究进度自动积累（初版）

自主研究的新运行冻结自动保存开关。进入终态后，Java 定时器每 2 秒发现并处理保存任务；每轮最多发现 20 个、处理 5 个，不调用模型。旧运行不补开开关。

- 快照沿用 `research_progress_memory`。V25 的每运行唯一 outcome 记录 `PENDING/SAVED/FAILED/DELETED`，重建保存服务可接续处理，重复执行不重复保存。
- 完成事项只来自原生完成证明和可访问的已采用证据。旧待办只有 **goal 与全部 criteria 文本完全一致** 才能被当前已核实事项关闭；旧未知范围的整题缺口仍保留。
- 旧已完成事项存入 `historical_completed_work`，带来源，只作为历史；`user_correction` 随最新快照继承，进入下次实际规划以及阶段二的来源摘要。
- 保存异常回滚该快照及相关写入，单独记录失败；已经提交的研究报告不回滚。所有写入按运行、outcome、快照的顺序加锁。
- 删除保留 tombstone；自动扫描不会恢复。源纠正、删除或证据改变，会让依赖的聚合快照失效并退出列表与新会话选择。

接口：

| 方法与路径 | 行为 |
| --- | --- |
| `GET /api/research/agents/{runId}/progress-save` | 只读状态，含 `enabled/saved/save_origin/error_code`；公开状态另含 `WAITING/NOT_ENABLED/UNAVAILABLE` |
| `PATCH /api/research/projects/{projectId}/progress/runs/{runId}` | `{note: string}`，最多 2000 字符，空串清除；只改纠正说明，不改核验结论 |
| 原有 PUT、DELETE、resume-context | 手动重试/删除/续研仍需显式操作；全部按当前用户与项目主权校验 |

开关：`deepresearch.memory.auto-save.enabled`（新运行默认 true）、`scheduler-enabled`（默认 true）、`interval-ms`（默认 2000）。工作流关闭时不注册自动保存服务及新状态接口。

初版边界：每次依赖校验最多 20 个不同快照；共祖缓存并且单条校验最多 59 次记录/证据/结论查询。超长或依赖不可用时明确保存失败，研究结果仍可读；这不是无限历史存储。源撤回影响后续读取与调用前校验，不撤回已经发出的模型请求。阶段四语义召回、通用长期记忆和大幅前端创意修改未实现。

验证：`MemoryAccumulationIT` 的三项独立方法覆盖原生证据的部分→完整更新、纠正后的新进程实际规划、保存失败及删除排除，以及合成共祖图的 20/21 节点边界。HTTP/JWT/Flyway/SQL/生产 Python 规划路径真实，模型与检索运输受控，不冒充真实模型效果。可用 `mvn -Pintegration -Dit.test='MemoryAccumulationIT#autoSaveUpdatesNativeCompletionCarriesPendingAndCorrectionEntersNewPlanner+failedSaveNeverRollsBackRunAndPreSaveDeletionAndDisabledRunsStayExcluded+sharedAncestorValidationHasGlobalQueryBoundAndRevokesChangedOrDeletedSource' test-compile failsafe:integration-test failsafe:verify` 运行；`AGENT_PYTHON` 指向安装所需依赖的 Python。保活演示另打开真实定时器，最终证据、截图和精简中文验收记录位于项目 `.codex-handoffs/memory-m3-20261006/`。

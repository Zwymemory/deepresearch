# 第 1 轮：自主研究运行候选

## 这轮改变了什么

显式 `POST /api/research/agents` 运行单个主 Agent：观察之后选择检索、读原文、核查、改计划、结束或保留缺口。原题保留在检查点中；任务记录目标、依赖、完成标准、状态、证据与计划版本。原有 workflows 和 Dify 入口继续使用其配置。

LangGraph 每个节点同步保存检查点。运行租约和 SQL 预算是跨进程的共同依据。Java 认证身份产生项目和归属映射，正文没有 owner/tenant 自授权字段。

## 可复验边界

每个 run 最多 8 次主决策、16 次模型调用（核查和有限重试一起计数）、16 次外部工具、180 秒，输入/输出分别设 64000/16384 准入上限。SQL 先预留后结算；结果未知仍占额度。缺少 provider token 元数据时返回 `null/unknown`，保守准入量单列。价格目前未知，**没有可承诺的人民币费用上限**；兼容预算里的 `maxCostCny` 不构成已实现的付费限额。

新路径的 Java MCP 执行需要本次 Agent 工具预留。Java 将原文读取、MCP 搜索的确定结果与预留结算一起保存；恢复可复用完成回执。外部调用已经开始、结果却不能确定时停止并要求对账。它不是对所有网络副作用的 exactly-once 保证。

最终答案只能是 B 服务从已支持 Claim 生成的确定文本。A 保存 Java 独有的发布证明，并在 finalize 核对完全相同的答案和引用。给任意新文字附上合法引用不能通过此门。未解决冲突或没有新证据时保留待查事项。

## 文件与兼容

- A：运行图、预算/模型网关、HTTP 证据适配、Java 运行/授权/发布控制、V17、最小页面。
- B：`com.deepresearch.evidence`、V18、`evidence_check.py`。A Java 适配器依赖这些公共端口，单独 A 源码不是可部署的组合。
- `contracts/agent/v0` 及冻结 hash 不改。原文/结论记录使用 B 的冻结记录。运行任务、事件和 ledger 是本轮服务 DTO，不能冒充完整 v0 Task/Run 对象。这是显式的兼容提案。
- 新 usage 的实际 token 可以为 null，并附 `inputTokensStatus/outputTokensStatus/costStatus`；旧 Java 构造器和旧历史 JSON 的 `summaryUsed` 别名保留。`modelUseVerification` 始终是 unknown。
- 页面“自主研究（候选）”显示计划、行动简述、变化原因及成本未知；可恢复运行仍使用 workflows GET/events/cancel。

## 验证方式与解释

`scripts/verify-agent-round1.py` 在 ignored target 中建立一次性源码资料包：A 当前文件加**精确、已提交**的 B 自有文件。它不合并分支、不读取 B 未提交实现、不部署。传入完整 peer SHA 和可用 Python 3.12 路径；Testcontainers 启动临时 PostgreSQL，Flyway 执行真实迁移。记录 source_bundle 与 peer_sha，以免把历史测试算到新候选。

共同四场景通过实际 WorkflowRunner/LangGraph 路径，模型/检索/证据 transport 可注入。动作来自观察和正文变化，不读 fixture_id 来选择成功答案。模型替身是合成语义 oracle，只证明控制流。单独桥接测试用真实 Java 授权、证据服务/记录/核查/发布和 SQL，原文 reader 与 verifier 提案仍为替身。

恢复测试覆盖模型已结算、工具已结算和发布已结算三个中断点；另有真实 PostgreSQL 检查点、数据库角色和新连接池的恢复用例。它们是受控中断模拟，未声称做了线上进程 kill 或真实模型正确率测量。

真实模型验收脚本只准备最多四个隔离场景；本轮未执行。主对话需固定 A+B 候选后安排，逐条查看动作、引用原文、适用范围和保留的争议。任何 mock 通过均不能称为“Agent 真实查证通过”。

## 本轮以外

长期研究记忆、委派、多 Agent、摘要维护 worker 未在本轮完成；现有 pending 摘要维护请求仍需后续统筹。没有修改当前线上 demo/Dify 发布、正式配置、知识文档或数据卷。

当前接口联调候选是 B `d530fd573075c0687a9d347f44eb00023c3dfa22`；B 在交接中公布了新增 publicationRead/completePublicationRead 许可端口，待其精确提交后继续对齐。当前测试不代表后续未提交接口已兼容。

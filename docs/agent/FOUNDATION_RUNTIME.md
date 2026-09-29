# 第 0 轮 A：执行契约、上下文与基线

日期：2026-09-29。共同基线：`60e0290c0670ad3f2f8da303b0b1513e056670c8`。本地分支：`feat/agent-foundation-runtime`。用途：学习和开源展示；长期记忆优先跨会话续接与成果复用。

## 1. 本轮状态

| 事项 | 状态 | 已验证的范围 |
| --- | --- | --- |
| Project / Task / Budget / Event / Context | proposed 契约；implemented 离线 Schema / 规则 | 合成对象与负例；尚待主对话冻结 |
| Dify 近期对话与记忆接线 | implemented_local | Adapter 参数、远端确认阶段、实际安装模板渲染；未发布 |
| selected / injected / used | implemented_local | 诊断与 Schema 分离；模型使用仍 unknown |
| Workflow 摘要完成挂钩 | implemented_local | 消息与待维护事件同事务、幂等、回滚 |
| 旧压缩器失败和并发进度 | implemented_local | 失败不推进；旧 / 重复完成不覆盖最新 watermark |
| LangGraph 本机兼容 | verified_local_library | 锁定依赖、内存小图暂停续跑、API 存在性 |
| Dify 本机兼容 | verified_local_schema_template | 1.17.0 / graphon 0.7.0 的 Start 与模板；未调模型 |
| 新 Agent、项目记忆、协作和统一预算执行 | proposed | 后续轮次；本轮没有实现这些运行行为 |

## 2. 运行时决策

采用 Java 控制面 + 代码管理的 LangGraph Agent Runtime，保留 Dify 固定流程作质量对照与未来受控子流程。Java 继续负责认证、工具授权、持久化回执、预算、取消、最终发布和 SSE；Python 负责未来决策与检查点，不直接绕开 Java 授权访问数据。RAGFlow 负责检索 / 分片，证据与记忆服务由 B 线设计。

这是架构决策，不是新增 Agent 入口已上线。`WorkflowService` 目前只接受 `langgraph / dify` 两种 engine，现有 `DurableResearchGraph` 仍是 planner → worker → reviewer → synthesis / 一次修订的固定图。第 1 轮应增加独立 agent 路径和决策循环，统一工具协议后才接入；不能把现有图改名视为自主性。

本机已安装 Python 3.12.14、langgraph 1.2.10、checkpoint-postgres 3.1.1、langchain-openai 1.4.1、psycopg 3.3.2、pydantic 2.12.5；pyproject 所有运行依赖均与安装版本一致。`uv.lock / pyproject / graph / runner` 的 SHA 保存于 local-langgraph-compatibility.json。内存小图在 work 前暂停，再用同 thread_id 续跑，输出 1 → 2；Send / Command 及 PostgresSaver setup API 存在。

没有连接检查点 PostgreSQL，也没有新重启 / 崩溃恢复验证。不能把库兼容测试称为新 Agent 恢复实现。现有 Java fencing / durable budget / Python run_lock / graph_thread_id 继续作为第 1 轮接入约束。

本机 Dify API 镜像为 `langgenius/dify-api:1.17.0`。只读检查保存 image ID、graphon 0.7.0、安装代码 SHA、Start schema 和模板结果。经典 Agent 源文件存在；没有验证策略插件、工具调用或完整 Agent 行为，因此保留为备选，不据在线新版文档假定本机具备全部能力。

## 3. 契约与现有状态的对应

| 新对象 / 语义 | 现有基础 | 本轮差距 |
| --- | --- | --- |
| ResearchProject | session / run | 无项目表或项目授权入口；不能以 session 冒充项目 |
| Task pending / running / blocked / done / cancelled | 固定图 WorkItem、Java run stages | 新任务树、版本与验收 criteria 只做契约和规则 |
| Budget 预留 / 结算 / unknown | Java durable reservations；Dify 固定上限 | 尚无统一主 / 子 Agent / Dify 子流程账本 |
| AgentEvent | run 级幂等事件与 SSE | 新 Task 状态与协议只有离线夹具；上下文和维护事件有本地代码 |
| AgentContext | 认证后摘要 / recentConversation / memories 快照 | 原 Dify 丢近期消息与记忆，本轮补齐本地传参 |
| 使用验证 | 旧 selectedMemoryCount / last_used_at | 原 selection 不是模型使用；修正标记，真实使用验证留后续 |

Task 与 Run 状态不能混为一类。QUEUED 可对应 pending；已有执行阶段对应运行中；SUCCEEDED 只有经过当前发布校验、满足任务标准才能对应 done。INSUFFICIENT_EVIDENCE 可以是满足“正确停止”标准的 done，也可能是尚待补查的 blocked，不能机械映射。FAILED / TIMED_OUT / BUDGET_EXCEEDED / DISPATCH_UNKNOWN 保留失败或结果不确定原因；cancelled 不自动恢复为 running。

未来委派必须共享父级预算、限制深度，取消向子任务传播；任务依赖必须存在且无环，running/done 的前置依赖已经完成。恢复须重新核验授权、deadline、版本及已成功回执，不重新派发结果未知的副作用调用。只有主角色发布最终报告，记忆写入由统一服务做幂等与版本检查。这些执行行为属于 proposed；本轮校验器只检查记录一致性。

当前 principal 提供 tenantId / userId；旧存储 owner 为 `storageUserId`。新契约 tenant_id / owner_id 应由认证适配产生，不能从 project/task/run 的不透明 ID 或模型文本推导。legacy context 的 project_id 为空，不自动获得任意项目 MemoryItem 的权限。

## 4. Dify 上下文断点修复

旧链路：AgentContextService 校验 session owner 后读取三类上下文；WorkflowService 保存快照；DifyWorkflowAdapter 只传 sessionSummary。新 DifyContextInputs 将三类内容放入现有 `session_summary` paragraph：有近期消息 / 记忆时为包含 schema_version、trust、session_summary、recent_conversation、memories 的 JSON envelope；没有这些行时保持旧纯摘要 / 空字符串输入。

不增加新的 Start 变量。安装版模板在缺省的新变量上会留下变量名称；复用已有字段避免给旧 caller 增加新的必填 / 缺省变量。Start 的四字段、HTTP 工具权限、节点 / 边拓扑、来源与发布 Code、失败分类及 4 模型 / 4 Worker / 一次修订预算不变。仅 Planner 的 system/user prompt 增加上下文说明，其他事实核验节点不接收这些历史材料。

输入限额：摘要 4000 Unicode code points；最近 8 条、每条 1200；最多 5 条记忆、每条 1200。封装后的 Java 字符长度最大 24000。JSON 转义导致超限时先移除末尾记忆，再移除最旧消息，最后缩短摘要；不截断序列化 JSON，不切 surrogate pair。truncated 显式记录；项目 / 工具 / 用户身份等快照额外字段不会进入 envelope。

记忆 / 文档中的指令仍是待分析数据。Planner 只能用它理解指代和选检索线索，原问题、固定要求、allowed_tools 和 Java actor 都来自独立可信字段。不能引用记忆字符串作为本 run 证据；仍需本 run 已完成回执、原文绑定、语义核验和 liveness 检查。离线恶意任务 / 记忆自造来源负例被原 Code 拒绝；这不是模型抵抗所有指令注入的保证。

### 4.1 阶段诊断

- CONTEXT_INPUT_PREPARED 保存 selected / submitted 数量、截断标记、input_sha256 与长度，不保存消息或记忆正文。
- 请求失败或没有远端 workflow_started/task ID，不产生 CONTEXT_INJECTED。
- 收到两种远端 ID 的 workflow_started 后，记录该请求已被远端接收的 injected 数量 / digest。它不证明 Planner 实际使用全部字段。
- used 始终 unknown；本轮没有新模型运行或使用验证。安装版模板渲染证明字段进入提示文本，模拟 transport 测试证明参数与确认阶段，不能据此宣称跨会话记忆成功。

MemorySelectionService 不再在 selection 时更新 last_used_at，并增加 owner 复核；现有关键词算法、无正相关时取近期少量记忆的策略本轮保留，列为 B 后续检索改造。历史 last_used_at 没有改写。旧 Diagnostics 的 Java summaryUsed() accessor 兼容保留并 deprecated；JSON 更正为 summarySelected / modelUseVerification=unknown。旧前端代码没有读取旧字段，但外部 JSON 客户端应迁移该名称。

本轮本地 DSL SHA 与 v15 已发布 DSL 不同，尚未发布。旧 16 场景不是新提示的在线质量验证；未来有授权的集成验收必须单独说明模型输入变化和实际 token 用量。24000 字符限额不等于 input token cap，目前没有新增全局 input token 执行上限。

## 5. 摘要维护断点与最小实现

Workflow 两条完成路径共用 insertFinalMessages。本轮在同一事务中写幂等 SESSION_SUMMARY_MAINTENANCE_REQUIRED 事件，表示新增终态消息、generation=deferred、status=pending。固定 run/event_key 防重复，回滚时消息与事件一起消失。Dify 只在成功发布后写最终消息；失败、取消、超时不会因此生成摘要。

现有 ConversationSummaryService 会调模型，未接入 workflow 全局预算；本轮不在完成路径直接调用它。第 1 轮需实现维护 worker：按 session 合并 pending 事件 → 校验 owner/project 与 summary watermark → 预留模型和 token 预算 → 生成成功后以版本 / watermark 条件提交 → 标记已处理；失败保留 pending、记录安全代码，可在预算内重试。未知成本保留 unknown，不以零计费掩盖。

同时修复原压缩链路的实际错误：disabled、provider failure、空输出不算新摘要；Coordinator 不更新旧摘要或已摘要消息数。Repository 只允许水位增加，迟到 / 重复结果不能覆盖较新摘要。Provider 异常仅记录类型，不记录原始错误正文。成功路径仍增量压缩旧消息。并发语义经独立临时 PostgreSQL 验证。

因此本轮**实现了维护请求可靠落盘及旧压缩器失败进度保护，没有实现 Workflow 自动生成摘要**。现有 ReAct 压缩调用仍未纳入新的统一账本；不把它计为新的 Agent 预算功能。Project 记忆自动写入与维护摘要是不同任务。

## 6. 质量 / 用量 / 延迟基线

baseline.py 从不可变共同基线 Git 对象读取 v15 记录与解释，保存源 SHA256、实现 SHA、发布 / DSL hash、16 个 runId 及逐 run 统计。重算 Native token 加总、model/tool 次数和 nearest-rank P95，不调用服务。

| 指标 | 已有 v15 记录 |
| --- | --- |
| 实现 | 3c77d31c3bed4662369ab8622cc9133788972975 |
| 发布 DSL | 87a3e241456c34dbf5dcb772fd0667b08e527650f586ee097411676b0fc05033 |
| 样本 | 连续原题 10 + 固定变体 6，预期终态 / 内容记录验收 16/16 |
| 发布 / 候选结论 | 47 / 56，Codex 自身来源与交叉记录审阅 |
| 模型 / 工具调用 | 62 / 31；没有本轮新采样 |
| 单次总 token 中位数 / 范围 | 14913 / 2735–16735 |
| Native elapsed 中位数 / P95 | 6.086612 / 13.909347 秒 |
| 实际扣费 | unknown；未配置价格不能当免费 |

这是经过多版修复的固定案例基线，非独立 held-out 集合、人工独立标签或生产质量保证。搜索摘要不是全文；3.6.15 存档的单线程正文不证明当前版本的普遍限制；原生省略保持原样。v13 更正、v14 15/16、最初 length/空输出及所有旧记录保留。新动态循环、跨会话 / 重启记忆、多 Agent 协作与新的故障注入没有本轮指标。

## 7. 离线验收与复现

总入口使用标准 draft2020-12 引擎，禁止网络 Schema 解析；双方正例、A 的全部负例与 B 独立规则测试均可运行。本机没有可选日期 / URI 格式包，因此显式注册标准库受限检查，而不依赖 FormatChecker 静默跳过：真实日历、RFC3339 时区 / 形状（排除闰秒）、绝对 ASCII URI、合法百分号转义、HTTP(S) 非空主机；未知 format 拒绝。它不是完整 RFC URI/IRI 实现，来源 URL 政策另行校验。B 的轻量 Schema evaluator 不是通用引擎，标准引擎补充验证它的全部正例与双方引用登记；事实裁决标签仍只是合成期望。

交叉桥接提供完整 A Project / Task / Context，引用 B 的现有合成 MemoryItem / Packet，双向校验存在性与范围。source_memory_id/version 还绑定 memory_type/version/freshness/progress/result 的规范 JSON 投影，最多 4000 code points；不能拿正确 ID 配另一段正文。版本错、投影错、跨 owner、待复查成果按 result_reuse 注入、deleted 记忆均真实失败；同一待复查材料按 research_lead 仍可作为未核实线索。本轮尚无模型消费这些记录。

```sh
python contracts/agent/v0/validate.py --peer-checkout /path/to/B-checkout --java
mvn -q -Pintegration -Dit.test=DifyWebSourcesIT,ConversationCompressionPostgresIT -DskipTests=false -Dtest=DifyContextInputsTest,MemorySelectionServiceTest,AgentContextServiceTest,ConversationCompressionCoordinatorTest,ConversationSummaryServiceTest verify
```

Java 门禁包含真值 / 空值 / 旧输入、Unicode、转义与总量边界，近期窗口，owner 隔离，角色 / tools 独立取值，只有远端确认才报 injected，失败不报注入，记忆伪造来源拒绝，以及现有引用、取消和失败语义回归。PostgreSQL 门禁仅使用隔离临时数据库，覆盖维护事件幂等 / 事务回滚、摘要水位单调；没有操作现有数据卷。

本轮结果：79 项 Java 单测、5 项隔离 PostgreSQL 检查、38 项 A 离线检查、39 项 B 独立检查通过；另有 5 个交叉负例被指定规则拒绝。11 份 B 正例全部经过标准 Schema 引擎及引用检查。verification-2026-09-29.json 保存实际结果、报告摘要和被测文件 SHA256，最终 / 被测提交 SHA 在 A 交接文件中记录。

## 8. 交接及下一轮必需工作

本轮本地提交，由主对话整合双方候选、核对依赖并冻结 0.1.0。本对话只写 A 文件；升级计划、knowledge schema、B 夹具与验证器只读。旧分支、docs/interview、私有配置、知识文档和现有服务保留。

第 1 轮需落实动态 Agent 入口、受控工具 / 原文读取、全局预算与取消恢复、摘要维护 worker / 状态、可验证模型使用轨迹；B 的项目记忆 CRUD、版本 / 删除失效及证据裁决后续接入。不得把旧人工 memory 行自动升级为项目成果。新记忆入库与删除后 checkpoint 重验尚未实现；当前旧字符串快照没有 memory ID / version，无法实现逐条删除失效传播，本轮在契约与交叉负例中定义边界并准确报告缺口。

本轮没有 Dify import / publish、服务重启、.env / 密钥 / 数据卷 / 语料修改、模型采样、push、PR 或 main 合并。

# 第 0 轮：证据与研究记忆基础

共同基线：`60e0290c0670ad3f2f8da303b0b1513e056670c8`；契约候选 `0.1.0`；JSON Schema draft 2020-12。原批准方案见 [升级方案](../AGENT_UPGRADE_PLAN_2026-09-29.md)，原文保留。

## 1. 本轮的真实状态

| 内容 | 状态 | 已验证范围 |
|---|---|---|
| 六个实体、API、存储与迁移设计 | proposed | 仍需主对话与 A 统一冻结 |
| 独立结构/关联/范围校验器、生成器 | implemented | 读取真实 schema 的已用关键词；纯标准库、无网络解析 |
| 四研究 + 七记忆合成夹具及负例 | verified | 可重复的结构、hash、引用、声明的裁决关系和资格规则 |
| 原文工具、事实裁决、长期记忆 API、自动失效、删除缓存 | proposed | 未接入运行时、未执行数据库迁移 |
| 改计划、补查、模型真正复用记忆、服务重启续接 | expected_only | 夹具定义未来动作，本轮没有 Agent/模型/服务实测 |

当前部署仍是已验收的 v15。B 不修改运行代码、UI、配置、知识库、数据卷或 Dify 发布；没有在线模型/搜索调用。源文完全合成，不是网上发现的真实资料。

## 2. 实体与身份

[knowledge.schema.json](../../contracts/agent/v0/knowledge.schema.json) 的 `$defs` 提供 Evidence、Claim、DecisionRecord、Challenge、ResearchPacket、MemoryItem；根 `oneOf` 只接受这六类。辅助 Measurement/KnownTime 供离线未知量校验。实体不透明 ID；`tenant_id / owner_id / project_id` 显式记录，**不能从 ID 前缀推断权限**。知识实体要求非空项目；A 的旧上下文可为 null 项目，不能因此自动消费某个项目成果。

全部对象拒绝额外字段。时间是带时区 ISO-8601；未知时间、版本、分数、成本用 `{status: unknown, value: null, reason: ...}`。已测得的零使用 known；未测得不填零。`schema_version` 表示格式版本，Evidence/MemoryItem 的 `version` 是记录修订版本，来源软件版本是独立的 `source.version / applicability.version`。

候选契约中 Claim 与 DecisionRecord 的内容按不可变 ID 保存，更正产生新 ID；它们的依赖 `version` 固定为 1。Evidence 与 MemoryItem 才使用递增修订号，快照 hash 只绑定 Evidence 原文。状态失效另留审计，不能覆盖旧说法/裁决后继续沿用原 ID。这个存储约定仍需主对话冻结。

| 对象 | 关键约束 |
|---|---|
| Evidence | 来源类型/定位/题名/版本/发表与观察时间/原始来源组/衍生关系；原始 UTF-8 快照和 SHA256；run/task/完成回执；相关性分数与时效/有效性分离 |
| Claim | 原子说法及条件/时间/版本；逐来源 supports/refutes/insufficient；原文精确范围；裁决 supported/refuted/contested/insufficient；时效独立 |
| DecisionRecord | 同 run 的 claim；采纳/弃用（附理由）/未解决证据完整分区；裁决、策略版本、缺口与方法；未解决冲突不能改成 supported |
| Challenge | 同 run/task 的异议；引用证据、请求补查动作、回应的 decision 与剩余缺口；动作仅是请求 |
| ResearchPacket | 同 run/task 的候选 claim/evidence/decision/challenge 列表；complete/partial、局限和缺口；可引用 A 的 context_id |
| MemoryItem | progress/result 分型；原 run/session/packet；证据与裁决闭包、修订号、依赖版本/hash、复查、状态和删除墓碑 |

每条 Evidence 在独立回执注册表中匹配 run、task、scope、completed/authorized，以及 source_id、快照 hash 和全部来源元数据 hash。实际回执必须由服务端验证；合成注册表的 authorized=true 只是受控标签。原文引用使用 **Unicode codepoint** 的 `[start,end)`，SHA256 使用 UTF-8；Java/JavaScript 的 UTF-16 下标须显式转换，不能直接搬用。夹具含中文和 emoji，负例实际拒绝 UTF-8 字节/UTF-16 下标错用。

搜索摘要、文档分片、全文、受控测试结果分型；搜索摘要不能被标成读过全文。source_group/parent_source_id 保留转载关系，既有相关性分数和三个来源编号均不能当三票真相。元数据 label 与原文 hash 不证明网站身份或材料事实。

## 3. 证据与裁决边界

校验器验证原文存在于**给定**快照、引用范围/hash、同 run 回执、scope、分区和声明的支持关系。它不理解自然语言、不会决定谁是权威，也不会自行发现资料错误。

特意保留一个语义边界测试：把 Claim 的句子改成明显错误的自然语言，结构和引用仍保持合法，规则校验可以通过，结果仍是 `semantic_verification=false`。这说明原文定位、supports 标签、裁决字段不能冒充事实判真。未来第 1 轮需独立调查/反证/范围核对，模型建议仍须保存可审阅依据。

Evidence 与 Memory 的 `freshness` 是 fresh/needs_recheck/expired/superseded；有效性、可用性另行记录。收到资料错误通知先使依赖成果需要复查，不能自动把所有关联结论判为 refuted。版本差异可能允许两个条件性说法同时成立；真正无解的冲突保留 contested 和缺口。

新 run 复用旧成果时：记忆是调查线索/历史裁决，保留原来源；最终发布的引用仍须通过当前授权和当前 run 的完成回执，重新读取/重验证后形成当前 Evidence。旧 memory/run ID 不能绕开既有引用绑定。

## 4. 记忆协议（API 与数据库均为 proposed）

### 两种类型

- `research_progress`：目标、已完成/待办任务、排除路线、未解决 claim 和缺口；可以保存失败和不足，用于新 session 继续项目。
- `reusable_result`：条件性总结 + claim/decision/evidence；全部原 run 与依赖版本/hash 可回溯。支持、反驳和争议均可保存为研究经验，但只有支持且仍适用的成果有资格作为事实候选复用。

### 接口草案

所有项目权限由 Java 从认证身份和项目 ACL 获得，不信任 body 的 owner/tenant。无访问权和不存在的对象用一致的 404，避免暴露存在性。Agent 只能走受控接口。

| 拟议接口 | 输入/行为 |
|---|---|
| `POST /api/research/projects/{project}/evidence/read` | 已授权来源定位、父 run/task、工具预算；由读取工具产生快照/回执，不能直接把客户端文本当可信来源 |
| `POST /api/research/projects/{project}/decisions` | claim、依据/反证/未解决项、策略版本、幂等键；服务验证范围与完整记录，语义核验另行执行 |
| `POST /api/research/projects/{project}/packets` | 完整关联 ID 与 task 版本；结构/权限通过后交给主 Agent 收口 |
| `POST /api/research/projects/{project}/memories/search` | 问题、类型、目标版本/条件、as_of；权限过滤先于关键词/向量；不相关返回空，不回退无关最近条目 |
| `PUT /api/research/projects/{project}/memories/{id}` | `If-Match` 当前版本 + `Idempotency-Key` + 候选；校验全部来源和裁决再提交 |
| `POST /api/research/projects/{project}/memories/{id}/recheck` | 新证据与修正后的裁决；重查完成才能使需要复查成果重新 fresh |
| `DELETE /api/research/projects/{project}/memories/{id}` | 授权与 If-Match；先建立墓碑/撤销读写资格，再清理内容、索引与缓存 |
| `POST /api/research/projects/{project}/evidence/{id}/invalidate` | 经过核验的失效事件、原因与新修订；事务 outbox 传播 needs_recheck |

创建 expected_version=0 产生 v1；更新必须匹配当前 v，产生 v+1，旧版本不可原地覆盖。幂等域为 tenant/owner/project/operation/key，服务端对规范化候选 payload 做 hash；同 key/同 payload 返回原结果，同 key/不同 payload 返回 409，版本不匹配 409。本轮 `memory_writes` 仅检查给定操作记录的版本/指纹一致性，**没有实现事务 CAS 或真正写入**。

未适配版本、复查超期、依赖 hash/revision 变动、资料不可用/失效 → 只能作为调查线索，不能直接沿用原事实。负例实际拒绝 direct reuse。规则不运行补查任务，也不自动更改数据库状态。

离线规则同样拒绝“依赖 Claim 已需要复查”或“依赖的另一条 MemoryItem 已删除/过期，而当前成果仍 fresh”；依赖修订号相同也不能绕过生命周期和时效检查。

删除协议包含：(1) 撤销当前及历史可读取内容；(2) 删除关键词/向量条目；(3) 驱逐检索和上下文缓存；(4) 保留不含原内容的墓碑和操作审计；(5) 检查点恢复与每次注入前按 memory_id/version 重新查当前状态。备份恢复须重放删除账本后才能对外服务。删除 session 和删除 memory 是两种操作。

删除夹具保留一份**合成的旧检查点候选**，离线读规则拒绝当前已删除 ID。它没有执行真实索引删除、备份恢复、跨进程 checkpoint 或模型重新注入；这些是第 2 轮验收事项。legacy 纯字符串记忆缺 ID/version，不能宣称有同样的删除保证。

### 存储与索引草案

新增表名/列为提案，不预占 Flyway 编号、不提供部署迁移：

- research_project 与 project_acl：显式 tenant/owner/project、授权与项目版本。
- agent_evidence_revision：复合 scope/evidence_id/revision 主键，来源及快照 immutable，回执身份；状态变更单独留审计。
- agent_claim / agent_decision / agent_challenge / agent_research_packet：同 scope 的复合外键、原 run/task、裁决版本与关联记录。
- research_memory / research_memory_revision：当前头版本 + immutable 修订；分型内容、复查与墓碑。删除需清除所有内容版本，墓碑不保留原文。
- research_memory_dependency：复合 scope 外键、record type/ID/revision/hash，支持反向检索；禁止循环依赖。
- memory_operation_receipt：幂等键、规范化 payload hash、最终版本/结果；与写入在同一事务。
- memory_outbox / memory_context_reference：失效传播与缓存/检查点撤销，消费者以事件 ID/目标版本去重；模型复查必须计入统一预算。

索引：scope + type + freshness + updated_at；scope 下倒排与 pgvector 召回；dependency 反向索引；操作幂等唯一键。向量模型名、修订、维度、归一化和来源状态记录在索引元数据，过滤/版本不一致时重建，不混合不同维度。没有在本轮选择 embedding 模型或建立索引。

## 5. 当前实现差距（固定基线 60e0290）

| 已有能力 | 差距与迁移要求 |
|---|---|
| Java/Dify 已完成回执、web 快照 ID、KB live chunk 校验和 INDEXED_V1 | 共用 Evidence 读取/发布服务未建立；网页仍是 Tavily 摘要，不是全文；新路径必须沿用授权、完成回执和当前 run 绑定 |
| Dify claim_support 原文/有限连续上下文及覆盖检查 | 还没有原文调查、时间/版本冲突裁决、结构化 Challenge 和跨 Agent 实际响应 |
| `user_memory` 按 user_id CRUD，字段 type/content/source/confidence | 无 project、证据/裁决外键、版本、复查、操作幂等和依赖传播；默认 confidence=1.0 不代表事实可信 |
| MemorySelectionService 最多最近 100 条关键词筛选，无匹配回退最近 | 先权限过滤、显式项目、语义召回、没有相关项返回空仍待实现；选择阶段 markUsed 不能证明模型真正使用 |
| AgentController 创建时覆盖 userId 为认证用户，删除按 memory_id + user_id | 基础用户隔离已存在；无项目 ACL、墓碑、历史内容/索引缓存/旧 checkpoint 撤销闭环 |
| Workflow 保存摘要/最近对话/记忆，Dify 只接摘要 | A 负责第 0 轮最小输入补接线；传参只证明 injected，真正 used 需审计证据 |

对应：[记忆存储](../../src/main/java/com/deepresearch/service/AgentMemoryRepository.java)、[记忆选择](../../src/main/java/com/deepresearch/service/MemorySelectionService.java)、[认证写入/删除](../../src/main/java/com/deepresearch/web/AgentController.java)、[引用发布](../../src/main/java/com/deepresearch/workflow/DifyCitationValidator.java)、[支持规则](../../integrations/dify/claim_support.py)。

保留 user_memory 原表与 API 兼容。旧 source 任意文字、无 claim/evidence 的记录只保留 legacy 手动备注/线索，不自动晋升 reusable_result。scope 映射必须来自可靠身份/授权记录，不能拆解 opaque user_id 猜 tenant。项目迁移需要显式选择项目、核查来源和授权、双读/回滚设计；批准前不转换用户数据。

## 6. 可复现离线验收

```bash
python3 testdata/agent-foundation/evidence/build_contract.py
python3 testdata/agent-foundation/evidence/build_fixtures.py
python3 testdata/agent-foundation/evidence/run_checks.py
```

需要 Python 3.10+ 的标准库；本机实际版本与方法数见 [验收记录](../../testdata/agent-foundation/evidence/verification-2026-09-29.json)。无密钥、无包下载、无网络 resolver、无数据库。生成器只重建 B 的 schema/合成夹具；A 的 runtime schema/README/总验证入口不被编辑。

本机默认/捆绑 Python 与捆绑 Node 无 jsonschema/Ajv，本轮没有下载依赖。独立校验器**只实现这份 schema 使用的关键词子集**，未知关键词/远程引用拒绝；格式仅覆盖本契约的时区 ISO 时间和 URI，再以 HTTP/S 无凭据规则限制 web 来源。它不是通用 draft2020-12 引擎或完整 meta-schema 合规证明。主对话集成若已有标准引擎，可另做独立 schema 校验；这个缺口明确保留。

研究夹具：空检索 → 期望改查/不足；错误资料 → 受控反证标签；版本差异 → 两个条件性结论；真正未解决冲突 → contested。记忆夹具：新 session 续接、同版本复用、版本变化重查、依赖失效、删除墓碑、scope 隔离与操作幂等。每例都有可读合成来源、问题、预期动作与不允许的推断。

负例在 unittest 中实际构造并拒绝，机器记录保存 expected/observed code：缺来源引用、伪 supported 掩盖未解冲突、unknown 时间/成本填零、错误原文 hash/范围、跨 run/scope、来源元数据替换、失效依赖仍 fresh、旧/删除/越权记忆、版本与幂等冲突。另保留“合法结构但错误自然语言仍可能通过”的边界检查。

## 7. 与 A 的交接

A 可导入 B 的 `validate_record`、`validate_bundle`、`validate_manifest`，或运行 B 的独立 `run_checks.py`。A 仍独占总验证入口。fixture envelope 为 fixture_id/origin/status/question/records/external_refs/authorized_scope/expected_actions/expected_decision/usage；memory_requests 与 memory_writes 是离线验证输入。

external_refs 的类型映射：ResearchProject.project_id、Task.task_id（含 run/status）、Run.run_id、Session.session_id、Receipt.receipt_id（含 run/task/completed/authorized/source_bindings）、Assessment.assessment_id、可选 AgentContext.context_id；均有显式 scope。引用不在注册表或 scope 不同立即拒绝，缺 A 运行实体不是完整集成通过。

**边界**：B 单独验收使用合成 external_refs。真正跨文件集成需 A 的运行实体注册表/验证先通过，再给 B `external_registry`；服务端授权由独立 `authorized_scope` 提供。把 fixture 注册表的布尔值当真实认证、把 selected/injected 当 used、把未来动作当已实现均不成立。schema URI、registry 类型及 legacy null 项目使用政策仍待主对话冻结。

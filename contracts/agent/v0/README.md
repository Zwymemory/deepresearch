# Agent foundation contracts — 0.1.0 candidate

JSON Schema draft 2020-12。共同基线为 `60e0290c0670ad3f2f8da303b0b1513e056670c8`；主对话完成交叉审阅后才冻结。本目录的结构和离线规则已实现，新的 Agent 执行器及服务 API 仍属 proposed。

| 文件 / 对象 | 负责人 | 作用 |
| --- | --- | --- |
| `runtime.schema.json`：ResearchProject、Task、Budget、AgentEvent、AgentContext | A | 执行、预算、上下文与安全事件协议 |
| `knowledge.schema.json`：Evidence、Claim、DecisionRecord、Challenge、ResearchPacket、MemoryItem | B | 证据、裁决与项目记忆协议；集成时加入 |
| `validate.py` | A | 总离线入口，包含标准 Schema 引擎和双方规则 / 负例 |
| `validate_runtime.py` / `validate_bridge.py` | A | 引用、权限范围、任务依赖、预算和上下文阶段，以及双方交叉夹具 |
| `testdata/agent-foundation/{runtime,evidence,memory}` | 按分工 | 明示 synthetic / expected_only 的夹具；不是已实现 Agent 行为 |
| `docs/agent/FOUNDATION_RUNTIME.md` | A | 本地接线、运行时决策、验证和剩余缺口 |
| `docs/agent/FOUNDATION_EVIDENCE_MEMORY.md` | B | 证据 / 记忆设计；集成时加入 |

## 共同字段

- `record_type` 区分对象；`schema_version` 为 `0.1.0`。所有完整对象禁止未知字段。
- ID 是 1–128 字符、不含空白的不透明值；不得解析 ID 来授予权限。`tenant_id / owner_id / project_id` 明确保存，授权范围由调用方独立提供。
- 时间为带时区 ISO-8601。未知时间由知识契约显式记录 unknown；不能用时代起点或当前时间代替。
- 未知计数或金额：`{"status":"unknown","value":null,"reason":"未测量的原因"}`。已实际测量的零值可以是 known，未配置价格不能填零。
- 金额的 known 值包含十进制字符串 `amount`、三字母 `currency` 和计价依据 `basis`；不把未配置或估算的字段解释为真实扣费。
- 检索渠道、相关性、引文绑定、证据关系和事实裁决分别保存。Schema / 引用校验不证明事实真实。

## 上下文阶段

`AgentContext.entries` 中每项显式区分：

| 字段 | 允许的结论 |
| --- | --- |
| `selected` | 该项选入候选上下文 |
| `injected` | 该项交给执行路径；传入不能冒充模型使用 |
| `used.status=unknown` | 注入后，尚未验证模型是否实际使用 |
| `used.status=confirmed` | 必须 injected，并有同 owner/project/run/context/entry 的使用轨迹引用 |
| `used.status=not_used` | 只允许该项没有 injected；不从“输出没提到”推断未使用 |

记忆引用同时带 `source_memory_id / source_memory_version`。`research_lead` 可保留需要复查的旧材料并明确作为线索；`result_reuse` 要求 reusable_result、active、fresh 和版本匹配。deleted 一律不能重新注入。带 ID 的 content 必须等于登记版本的有界投影：memory_type、version、freshness、progress、result 按键排序、紧凑 JSON、保留 Unicode，取前 4000 code points；truncated 与投影是否超长一致。这防止借用正确 ID 注入另一段内容，不代表内容的事实已核实。来源 ID 缺失的旧字符串只能作为非证明的线索；不能自动转成有来源的复用成果。legacy_unscoped 的 project_id 与 source_memory_id 必须为空，避免自动跨项目继承权限。

`FixtureReference` 是双方合成夹具的有类型引用登记：Project、Run、Task、Session、Receipt、Assessment、Context。它不是完整运行记录，也不是生产授权；完整对象仍必须满足相应 Schema。Run 引用不含 tools/agents 时，A 的任务校验不会默认授予任何权限。夹具内的授权声明只是测试输入；生产调用方必须从认证与授权服务提供范围。

## 验证入口

需要现有 Python 3.12 和 `requirements.txt` 中的已锁定依赖。本轮使用本机已有环境，没有下载依赖。标准引擎是 `jsonschema==4.26.0`、`referencing==0.37.0`，拒绝网络 Schema 解析。本机缺少可选格式依赖，因此显式注册两个标准库格式检查：date-time 要求真实日历、RFC3339 形状和时区，排除闰秒；uri 要求绝对 URI、ASCII 合法字符、有效百分号转义，以及 HTTP(S) 非空主机。它们是本契约使用的受限格式，不声称覆盖完整 RFC URI/IRI 标准。未登记的 format 必须失败；日期无效、错时区、错网址负例真实运行。来源 URL 与授权政策仍由独立规则检查。

```sh
python contracts/agent/v0/validate.py --runtime-only
python contracts/agent/v0/validate.py --peer-checkout /path/to/peer-checkout
python contracts/agent/v0/validate.py --java
```

第一条明确只是 A 线。默认完整入口缺少 B Schema / manifest / validator 时必须失败，不能报 integrated pass。第二条只读加载尚未合并的 B 候选，标准引擎校验全部正例及引用登记，并运行 B 的独立结构 / 语义标签一致性负例。集成后第三条在单一 checkout 运行，并包含必要 Java 单测；隔离 PostgreSQL 门禁见运行文档。

交叉夹具把完整 A Project / Task / Context 交给 B 的引用规则，并将 B 真实夹具中的 MemoryItem / Evidence / Decision 引用接入 A Context。不存在、跨 owner/project、版本不符、内容与版本投影不符、待复查成果复用和 deleted 记忆必须拒绝；过期材料可以按 research_lead 提供，不能按结果复用。这里的“真实夹具”指现有合成文件，不是网上事实或模型实测。

```sh
python contracts/agent/v0/baseline.py
python contracts/agent/v0/probe_langgraph.py
python contracts/agent/v0/probe_dify_local.py --container dify-local-api-1
```

基线读取不可变基线 Git 对象，无网络或新采样。LangGraph 核查只运行内存小图；Dify 核查只读已安装包、解析 Start 数据与模板，不导入 / 发布 workflow 或调用模型。reference_published_dsl_sha256 是归档版本的对照值，没有重新请求线上发布内容。总入口的通过是离线结构与规则通过，仍需主对话冻结；不表示部署、自主研究、长期记忆或模型使用验收通过。

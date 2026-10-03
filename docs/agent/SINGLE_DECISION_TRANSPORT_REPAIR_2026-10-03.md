# Agent 单决策结果传输修复（2026-10-03）

## 原因和改动

此前 web-only 实际研究在第二次模型调用失败：HTTP 200、deepseek-flash 身份通过，但返回两个 function calls。原有精确单决策校验拒绝它，保存已知消耗为 UNKNOWN，不执行原文读取或发布。没有保留被拒绝的函数名和参数，不能重建其内容。旧批次结论仍然 FAILED，后续四个场景未运行。

本次增加显式 `AGENT_RESULT_TRANSPORT=deepseek_json_object`：仅支持 `MODEL_PROVIDER=openai`、`MODEL_NAME=deepseek-flash`、精确 `OPENAI_BASE_URL=https://api.deepseek.com`。只影响 Agent 内部决策和核验结果，不改变一般工作流的模型适配器、实际工具执行、证据裁决或发布语义。

发送 `response_format={"type":"json_object"}`，不发送 tools/tool_choice；可信 system 指令包含完整结果 JSON Schema 和纯语法示例。来源与历史仍是非可信数据。默认 `function_call` 保持已有兼容路径，仍要求精确一个同名函数；两条路径均拒绝多 choice。没有挑首项、合并、自动切换模式或增加请求。

[DeepSeek 官方 JSON 模式文档](https://api-docs.deepseek.com/guides/json_mode/)支持该配置，并说明可能返回空内容；它协助 JSON 语法，不能替代本地 schema 和领域校验。本修复不依赖 `parallel_tool_calls=false` 的未验证支持。

## 校验、预算和恢复

内容只允许一个最多 65,536 UTF-8 字节的对象；重复键、NaN/Infinity/指数溢出、嵌套键和值中的非法 Unicode、尾随文本、数组、空内容、截断、外来工具信号均拒绝。随后仍经本地 JSON Schema 和原有 Pydantic/领域/授权校验。拒绝记录固定分类与每个有效的已知 token 字段，不记录模型原文或补造未知值。

适配器在准入前冻结实际请求字节。输入预留为实际 UTF-8 wire 字节数加 1,024 framing allowance（保守预算，不宣称为实际 token 数）。版本 `agent-result-wire/1`、模式、endpoint、实际 wire 摘要、原始请求及 binding 共同绑定 SQL request_hash。schema、指令、模型、endpoint 或模式变化不能复用已结算调用；旧版本 hash 也失败关闭。准入后发送相同字节，避免设置变化产生预算外内容。

保留既有最多两次、仅明确可重试 HTTP 故障的策略；协议/结构错误不重试。取消、deadline 和 claim 保护保持原有行为；未获得完整回执的在途调用不能当成可用结果重放。

## 离线证据与状态

实现固定提交：`46bb238f6567ab081873260187dc46c52c9fe43e`。独立 B 审查结论在下文记录；本报告的后续提交仅补充解释与结果，没有声明真实研究成功。

新增 MockTransport 用例覆盖实际适配器 → 身份观察器 → 预算网关 → 一次 UNKNOWN 结算、部分/零/未知 usage、安全导出。真实隔离 Flyway PostgreSQL 测试覆盖相同 wire 的无额外调用重放、模式/请求变化拒绝、无工具副作用、在途取消与持久恢复。

生产 runner/graph 的离线语义模拟包含搜索后多个候选、单次读取决策、原文读取、核验和发布；实际 JSON 传输进入原有运行时。来源和语义裁决使用标明的合成替身，不能证明实际 RAGFlow、搜索供应商或模型的研究能力。

八条真实 journal 记录、两个 STOPPED 批次、历史 audit/authority/ready/review 原样保护。本轮无真实模型/搜索/检索/研究调用，无环境修改、服务重启、push、PR 或部署。

## 后续配置与验收

后续另行授权的隔离验收在准备工具显式选择 `--model-name deepseek-flash --agent-result-transport deepseek_json_object`，sidecar 身份记录结果模式和传输版本。参数省略保持 function_call。这个选择不会重开既有停止批次，也不授权当前运行准备工具。

独立离线审查完成后，由协调方决定新额度和固定候选的完整 web-only 原题验证。一次 tiny API probe 不能替代研究验收。实际通过后才能考虑 mixed 和余下场景；Round1 当前仍未验收。

## 固定实现的检查结果

| 检查 | 结果 |
| --- | --- |
| workflow 非集成回归 | 330 passed；此项不计 22 个集成用例，下方 gate 单独执行相关的 16 个 Agent SQL 用例 |
| Agent 脚本 | 41 passed |
| 冻结契约 validator | runtime 38、evidence 39 passed |
| Ruff | PASS |
| Flyway V1–V21 隔离数据库 | AgentRuntimePostgresIT 24 Java 用例通过，其中启动 Agent Python SQL 文件的 16 个用例 |
| 公开树与全部可达历史扫描 | 181 commits / 831 files；零泄漏、无豁免 |
| 历史保护 | 45 份文件逐字节一致，包括八条记录的完整 journal 与两个 STOPPED 批次 |

仅有已有 Starlette/httpx 依赖弃用提示，不影响上述通过结果。首次 SQL 启动把 IT 放到了 Surefire，未使用 Failsafe 的 Docker API 设置；随后新增 SQL 断言列名拼错，扫描也曾误识别错误分类参数。以上测试启动/断言/格式问题在固定实现之前解决，未改数据库契约或真实历史。

复现需项目 Python 3.12 测试环境和 Docker；始终显式设置本仓库的 `PYTHONPATH`，避免另一个 checkout 的 editable install：

```sh
PYTHONPATH=workflow-service/src python3.12 -B -m pytest workflow-service/tests -m 'not integration' -o addopts='' -q
python3 -B -m unittest discover -s scripts/tests -p 'test_agent*.py'
PYTHONPATH=workflow-service/src python3.12 -B contracts/agent/v0/validate.py --peer-checkout /path/to/deepresearch-ragflow
ruff check workflow-service/src workflow-service/tests
AGENT_PYTHON=/path/to/python3.12 mvn -B -Pintegration -Dtest=NoUnitRequested -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=AgentRuntimePostgresIT verify
bash scripts/verify-public-release.sh
```

公开发布检查在固定 SHA 的干净 detached worktree 中执行，避免把用户未跟踪的 `docs/interview/` 纳入提交。Java/API 生产代码与契约未改，只运行相关 PostgreSQL 集成 gate。新增证明日志保存在忽略的 `target/single-decision-pinned-*`，历史绑定文件未覆盖。

## 独立审查

B 的报告提交：`968b32987094f86a09b01f24b93d9d3e5388cb7e`，报告 `docs/agent/SINGLE_DECISION_TRANSPORT_REVIEW_2026-10-03.md` 位于独立审查分支。结论：**固定实现 46bb238 的离线修复 PASS，无未解决实现阻塞**。

B 在最终提交独立执行 55 项协议/请求/重放/取消/运行图反例，并以 MockTransport 穿过身份观察器、适配器、预算网关和真实隔离 SQL，额外验证 5 个实际存储用例（零跳过）。拒绝结果为 UNKNOWN、safe_result=null，已知、零、部分已知和无效 usage 分别保留；同一拒绝结果不重发，无 TOOL 记录。有效结果重放无额外调用，切换模式/schema/指令/binding 拒绝。

B 独立复核最终源码、330 项回归、Ruff、公开/历史扫描与历史字节，并核验 A 最终 gate 收据。旧 cef2210 与最终 46bb238 仅一处错误构造参数顺序不同；B 明确区分旧提交上的脚本/冻结/完整 Java gate 与最终提交上重新执行的检查，不把旧结果冒称新执行。

报告提交只记录证据，不改变固定实现的源码、脚本、测试或契约；真实 web-only 的恢复仍需下一次单独授权验收。

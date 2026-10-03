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

候选实现、测试命令和独立审查将在共享 receipt 绑定固定提交。本文件随候选提交保存；没有声明真实研究成功。

新增 MockTransport 用例覆盖实际适配器 → 身份观察器 → 预算网关 → 一次 UNKNOWN 结算、部分/零/未知 usage、安全导出。真实隔离 Flyway PostgreSQL 测试覆盖相同 wire 的无额外调用重放、模式/请求变化拒绝、无工具副作用、在途取消与持久恢复。

生产 runner/graph 的离线语义模拟包含搜索后多个候选、单次读取决策、原文读取、核验和发布；实际 JSON 传输进入原有运行时。来源和语义裁决使用标明的合成替身，不能证明实际 RAGFlow、搜索供应商或模型的研究能力。

八条真实 journal 记录、两个 STOPPED 批次、历史 audit/authority/ready/review 原样保护。本轮无真实模型/搜索/检索/研究调用，无环境修改、服务重启、push、PR 或部署。

## 后续配置与验收

后续另行授权的隔离验收在准备工具显式选择 `--model-name deepseek-flash --agent-result-transport deepseek_json_object`，sidecar 身份记录结果模式和传输版本。参数省略保持 function_call。这个选择不会重开既有停止批次，也不授权当前运行准备工具。

独立离线审查完成后，由协调方决定新额度和固定候选的完整 web-only 原题验证。一次 tiny API probe 不能替代研究验收。实际通过后才能考虑 mixed 和余下场景；Round1 当前仍未验收。

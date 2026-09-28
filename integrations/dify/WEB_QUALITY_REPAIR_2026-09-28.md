# 网页论断支持与模型输出修复

## 已保留的故障

wf-37517854-e59b-4ee4-88d0-f364a9968c1c 的两次网页检索均成功、各有五条摘要证据。Reviewer 的 finish_reason=length、4096 completion tokens；闭合思维块之后没有最终 JSON。Java 安全结束为 FAILED/DIFY_MODEL_OUTPUT_INVALID。脱敏诊断见 model-output-failure-2026-09-28.json，不改变历史终态、不公开模型思维链。

此前 v8.1 的首条网页答案在被引摘要没有说明时加入了“单线程”。其他运行曾得到有该表述的摘要，不能替代这条失败样本。

## 具体机制及边界

- 本地官方 DeepSeek 插件 0.0.24 将 thinking=false 转成 type=disabled 并删除 reasoning_effort；manifest 支持 JSON object，尚未声明原生 JSON Schema。所有有限 JSON 节点使用非思考模式，保留严格字段、类型、重复键等本地验证。[官方思考模式说明](https://api-docs.deepseek.com/guides/thinking_mode/)。
- 最多四次模型调用：Planner 1536、Reviewer 1536、Synthesizer 4096、独立支持核验 2048 completion tokens，总上限 9216，低于旧三节点 12288。最多四次 Worker、一次检索修订，未启用模型或 HTTP 重试，也不重新派发整单。Java 120 秒截止不变；额外核验调用仍受它约束。
- Planner 在检索与生成之前固定最多六个原问题要求，Code 验证为原问题片段；之后的模型不能重新定义这个列表。Code 从每个真实快照确定生成最多 16 条、每条至多 300 字符的连续原文选项。Synthesizer 只能提交最多六条短论断，每条选最多两个来源的 sourceId+quoteId，总论断文字最多 700 字符、最多八个来源。Prepare 从同一 run 的实际 content 重新计算选项，拒绝模型自造 ID，不接受模型自抄引句或外部 quoteOptions 替代原文。
- Code 先验证被引原文来自确切来源。独立模型仅查看各论断自己的引句，判断全部限定、数字、单位、版本、否定等是否得到支持，并对每个固定要求返回已批准论断索引。Code 验证覆盖数量、顺序和索引一致，所有要求都有批准论断才发布；被拒绝的论断不会残留在自由文本中。
- **原文存在、覆盖映射及模型批准均不等于逻辑证明**。问题分解和语义支持仍由模型判断，必须保留人工验收。开发期间曾观察到正确的并发论断被误认为已回答 I/O 子问；原判断保留。固定要求后的同样部分回答明确返回未覆盖的 I/O 项；少列这一项即使其余映射非空，也被确定性代码拒绝。
- length、空最终正文、非法 JSON/schema、模型服务异常分为 DIFY_MODEL_OUTPUT_TRUNCATED、DIFY_MODEL_OUTPUT_EMPTY、DIFY_MODEL_OUTPUT_INVALID、DIFY_MODEL_PROVIDER_ERROR。引句来源错误为 CLAIM_EVIDENCE_INVALID；支持/完整性不足为空答零引用的 CLAIM_SUPPORT_INSUFFICIENT。仅允许安全节点名和代码进入诊断，provider 原始错误及思维链不进入前端。
- 网页快照的 run 级授权、完成回执、URL/标题/内容身份复核，以及原 KB 实时文档/chunk 验证保留。KB 展示元数据取自本 run 成功完成且已授权回执，kind=KNOWLEDGE_CHUNK，无伪造网页 URL。取消、截止、无证据与 SSE 规则不变。
- 用户明确要求官方/一手来源时，Planner 与 Reviewer 的网页查询必须有合法 site:DOMAIN。Java 将其作为 Tavily include_domains/restrict，并对每条实际 URL 检查 host 等于域名或是以点分隔的合法子域；凭据 URL、伪后缀、伪前缀、非 HTTP(S) 均不通过。无匹配结果不回退博客、不增加重试。[Tavily 官方搜索参数](https://docs.tavily.com/documentation/api-reference/endpoint/search)。**候选域限制不认证官网身份**；这次 Python 域的判定单列冻结于 web-quality-source-scope-2026-09-28.json，以 [Python.org 文档入口](https://www.python.org/doc/) 直接链接 docs.python.org 为依据，逐条审阅实际 URL；其他实体需各自核实。
- DOCUMENTED_BOUNDARY 的完整覆盖是有确切公开资料范围依据的拒答，不要求补出被排除的私密值。核验仍逐条检查全部请求对象是否属于所引排除类别；JWT 拒答不能覆盖无关银行账户请求。metadata 与否定范围结构验证不会自动批准语义。

## 已完成的定向开发验证

实际 DSL 的计划/修订权限、四 Worker 上限、网页/混合来源、引句结构、固定要求漏项、模型失败分类、边界否定及索引验证通过。25 项相关 Java unit 与四项真实 PostgreSQL 测试通过；26 项评测测试、历史 11 份 score 精确复算和八文档 dry-run 通过。

claim-support-fixtures-2026-09-28.json 使用保留的真实摘要，覆盖无单线程/有明确单线程、过强结论、错误/正确数字、错误/正确环境。固定要求版本真实核验八例全部达到人工标注的支持及覆盖结果，原始模型判断的脱敏记录和实际 Code 确定性重放见 claim-support-model-audit-2026-09-28.json。开发中的调试输入错误和一次缺少 JSON 提示字样造成的 provider 400 均保留，不混进最终队列。插件价格为未配置的零值，token 是用量记录，不能据此声称免费。

## 最终冻结队列

web-quality-cases-2026-09-28.json 在最终运行前冻结：连续十次用户原问题、两次改写、KB、混合、边界拒答、零证据，共十六次。每次保留终态、耗时、模型 finish reason/token、工具回执、逐句支持与要求覆盖结果；不得以新成功样本替换失败。最终发布身份、代码 SHA、镜像与全部结果在队列完成后填写。当前尚未把开发样本计作最终验收。

### 第一冻结版 37a6f18：验收失败，完整保留

evidence-v9-quality-live-2026-09-28.json 保留该版 16 次真实执行与全部引用。原问题十次：9 SUCCEEDED、1 FAILED/CLAIM_EVIDENCE_INVALID；全部 16 次中 14 次达到预先冻结的终态。所有已执行模型节点 finish_reason=stop，未复现旧 length 故障；这不能消除本轮其他缺口，也不是稳定性保证。

- original-08 / wf-278c3a29-3ba7-4e2f-a0d2-40926f99cb2c：模型自抄摘要时改写 Markdown/文字，原文来源 gate 拒绝，未调用第四核验模型。
- 多数原题、rewrite-2 与 mixed-kb-web 的答案引用了博客/教程。摘要直接支持事实，也不能算满足“Python 官方资料”。13 个需要官方网页范围的用例仅 rewrite-1 的发布引用全部符合；加上 KB 正例与零证据例，第一版严格整体验收仅 3/16，不把 14 个预期终态记作质量通过。
- kb-boundary / wf-e5304903-b53c-4d54-b45e-d91f8ceb1b31：核验批准了 JWT 资料边界论断与最小长度事实，却将索要三个密钥原文的要求标成未覆盖，Java 返回空答零引用 CLAIM_SUPPORT_INSUFFICIENT。未改变该历史结果。
- 采样前验证 token 过期的一次 401 未创建任务，单列 evidence-v9-quality-auth-setup-2026-09-28.json。按原用户、权限和时长刷新验证 token，用户私有 .env 未修改；未用重抽样隐藏模型失败。

记录审阅由 Codex 完成，并明确不是独立人工裁决。保留“被引摘要支持”与“事实真实/官网范围”各自的限制。

### 第二版开发验证

上述失败驱动 quoteId、实际域过滤和拒答覆盖修复。claim-support-options-audit-2026-09-28.json 单列十次真实独立核验：原八个限定/数量/环境/部分覆盖样本，以及全部 JWT 类别拒答和 JWT 拒答但银行未回答两例；十次均符合预先期望，finish_reason=stop。实际响应通过最终 Code 重放，银行项仍为空覆盖并返回不足。六项 Tavily 和十三项 Dify 工具 unit 通过，未增加模型/Worker/时间预算。

第二版将保持原 16 个问题、次序与期望不变，另存独立结果；在队列和逐项来源/论断审阅完成前不声称已稳定或最终验收通过。

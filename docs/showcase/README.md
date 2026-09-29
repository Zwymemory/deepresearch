# DeepResearch 展示入口

DeepResearch 是一个个人学习与开源作品项目：Java 保存身份、任务状态、证据入口和持久事件；研究执行器负责规划、工具调用、审阅和合成。默认执行器仍为 LangGraph，RAGFlow 检索和 Dify Workflow 是显式启用的候选路径。先看[可验证结果与时间线](EVIDENCE.md)，再运行演示。

## 简短介绍与可选演示

主线为 2–3 分钟介绍，也可以只展示已归档记录。最新机制与逐版结果见[论断与整问覆盖修复](../../integrations/dify/WEB_QUALITY_REPAIR_2026-09-28.md)，实际标题、网页跳转、正文编号和刷新恢复见[引用呈现验收](../../integrations/dify/CITATION_UI_ACCEPTANCE_2026-09-28.md)。[历史 v8.1 网页与混合验收](../../integrations/dify/WEB_SEARCH_ACCEPTANCE_2026-09-28.md)保留五项预期终态、严格逐句支持 4/5、额外限定缺口及初版两次超时；历史 V7 的 37 题不是新版本全量结果。现场希望操作时，可只创建一次网页或混合 run，串起提交、引用和续传。

首次运行先看[新检出目录复现指南](REPRODUCE.md)，讲解与可选操作见[讲稿](FIVE_MINUTE_DEMO.md)。[此前 KB 版本的新目录实测](RELEASE_REPRODUCTION_2026-09-28.md)记录了独立空库、新数据集、有引用答案、空证据结果和一次受控 SSE 续传。部署、模型配置和语料解析属于准备工作。

当前 Evidence v15 Query 的固定 16 场景达到预期终态与自身证据/整问覆盖记录审阅，47 条发布论断已交叉复核。Native Dify P95 为 13.909 秒（n=16），与历史全量评测分开；实际输入 token 随分开检索增长，可靠费用仍未知。存档文档、搜索原生省略和模型核验的限制见质量修复报告，不宣称全网页事实或普遍语义保证。

选择网页搜索时，在私有配置设置自己的 `TAVILY_API_KEY`，运行 `python3 scripts/preflight-showcase.py --require-web-search --online --corpus`。页面显示配置状态与真实失败原因；引用展开为实际 URL/标题/摘要。出处来自 run 绑定回执，不代表抓取了全文或证明事实正确。

前提：按根目录 [README](../../README.md) 启动 Java、数据库和演示页，导入仓库内的合成项目知识包。演示页是 `http://localhost:8080/demo.html`；本地 RAGFlow 通常通过 `http://127.0.0.1` 打开，API 通常在 `9380`；本次本机 Dify UI 在 `http://127.0.0.1:8081`。需要走 RAGFlow + Dify 时，先按 [RAGFlow](../../integrations/ragflow/README.md) 与 [Dify](../../integrations/dify/README.md) 指南完成导入、服务密钥、回调地址和显式路由配置。

1. 打开 `demo.html`，签发本机演示 USER 身份，选择 **Durable Workflow**，只保留“知识库检索”。从服务配置核对 `retrieval=ragflow` 与 `engine=dify`，运行后再用安全轨迹中的 `DIFY_STAGE` 事件确认实际走了 Dify；演示页目前没有独立的路由配置标牌。
2. 输入“断线重连时，Last-Event-ID 应如何使用？”观察创建后的 `runId`、阶段事件和最终答案。正确现象是解释 `<runId>:<eventId>`、服务端从数据库中重放 `event_id > cursor` 的事件，并给出可点击/核验的 `[来源N]` 引用。答案措辞可能变化；以事实和真实来源为准。
3. 输入“本项目生产环境使用的银行账户号码是什么？”正确现象是 `INSUFFICIENT_EVIDENCE`，没有号码、答案引用或伪造来源。它检验的是**纯无证据**分支。
4. 输入“线上 Kubernetes 集群的云区域和生产副本数是多少？请引用项目资料解释能否给出数字。”正确现象是指出资料没有这些生产部署数字；若检索到了项目边界文档，拒答应引用该文档。这检验的是**有边界证据的拒答**。
5. 在一次仍运行的任务中关闭页面再打开，检查相同 `runId` 的状态与事件游标能否继续；最后展示取消按钮。此步骤是功能演示，不等于任意断点重启和跨实例恢复的完整故障注入验收。

演示时可以打开浏览器 Network 面板核对 `/api/research/workflows` 的创建、状态、事件和取消接口。Java 对外仍是同一组接口；RAGFlow 与 Dify 不直接暴露给浏览器。不要在录屏或截图中展示 `.env`、Bearer Token、dataset ID、Dify App Key 或本地私有路径。

## 为什么保留三条路径

| 路径 | 适合观察什么 | 已有证据 | 当前限制 |
|---|---|---|---|
| Legacy 检索 + LangGraph | 自建 pgvector/BM25、精排、checkpoint、receipt、fencing | [固定 37 题整体链路对照](LIVE_COMPARISON_2026-09-28.md)：17 成功、20 证据不足；来源 receipt 审阅 | 证据核验器多次超过 15 秒截止时间；两处无结果占位引用属修复前采样 |
| RAGFlow 检索 + Dify | 托管知识检索与可视化 Workflow，Java 保留授权、证据与持久运行边界 | [Evidence v7 固定版本评测](../../integrations/dify/RELEASE_QUALITY_2026-09-28.md)：37 题中 34 成功，29/31 完整事实覆盖，5/6 严格拒答，33/34 组引用支持全部对应论断 | 重试次数矛盾、Reviewer 多余字段拒绝与一次无引用边界拒答仍存在；完整恢复、真实成本和租户级权限验收未完成 |
| RAGFlow 检索 + LangGraph | 固定检索源后比较执行编排 | 已有配置能力 | 尚无同语料同模型的控制组结果 |

这三条路径承担不同学习目标。旧路径展示自己实现检索和可靠执行的代价；RAGFlow 与 Dify 让团队把精力放在证据契约、权限和运行状态上。比较“编排是否更好”时必须让 Dify 与 LangGraph 使用同一检索源、模型、语料和预算。旧路径与新路径端到端对比仍有检索与编排两个变量，报告只称整体效果。

## 复现实验

[评测说明](../../integrations/ragflow/README.md#showcase-答案级评测)列出 29 + 8 题的固定标签、采集命令和审阅方法。[V7 发布质量报告](../../integrations/dify/RELEASE_QUALITY_2026-09-28.md)链接冻结的定向 24 次重复、最终 37 题单次采样、逐题审阅、重计分和原生节点审计。V5 开发结果与 V6 的 13/24 部分采样也保留；失败没有被重抽成功样本覆盖。

[新路径首轮基线](LIVE_BASELINE_2026-09-28.md)、[Evidence v4](LIVE_CANDIDATE_2026-09-28.md)与[旧路径同集合对照](LIVE_COMPARISON_2026-09-28.md)保留历史条件；旧路径占位引用修复发生在该轮采样之后。检索覆盖、答案事实、引用存在与支持、拒答、延迟、观测调用量分别报告。`p95` 带样本数；原生节点统计不是独立计费账本，可靠成本仍未知。旧的 `46.9s` 和三条冒烟耗时不作为当前性能比较。

## 下一轮学习路线

1. **质量闭环**：针对 V7 已保留的重试次数矛盾、Reviewer 多余字段与生产指标无引用拒答做定向修复；按固定标签复测并另记新版本。旧路径的 15 秒证据核验截止时间继续单独诊断。若要隔离编排收益，再加 RAGFlow + LangGraph 同源控制组。
2. **恢复闭环**：验证 Dify 派发未知、取消、超时、重复回调与 Java 重启；验证 LangGraph checkpoint 与旧 claim 的工具结果未知路径。把 Java 的最终发布门禁与数据库回执一起检查。
3. **体验闭环**：用真实浏览器验证页面刷新、SSE 断线续传、取消反馈、引用展开和证据不足文案。保存可复现步骤与去敏截图。
4. **开源交付**：清理私有配置和样本中的标识符，完善一键检查、已知边界及部署说明，然后再决定默认路由或公开发布。

目前的 corpus 与索引仍是共享数据面，没有文档级 tenant ACL；本地 Compose 也不代表已完成云上网络隔离、容量压测或生产 SLA。

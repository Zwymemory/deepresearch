# 原问题综合研究修复验收

日期：2026-10-07。结论：本次原问题研究通过；前后端保持运行。

## 修复内容

- 接通本机项目专用 RAGFlow 知识库，补充研究任务创建幂等的代码、测试与原生接口验证资料。9 份文档全部就绪。
- 把过小分片调整到 512 token，并通过正常重建接口迁移到文档版本 2；当前共 38 个分片。减少机制正文与代码、测试证据被拆散的情况。
- 新运行使用可逆的无损上下文共享，保留问题、范围、历史、证据和未完成项；修正缓存测量与执行循环上限。历史研究仍只是需复查的线索。
- 明确崩溃、断线、任务创建是不同问题；提供当前待办清单，每批核查最多两条论断。后端的引用、原文、预算和发布校验保持严格。
- 本机 DeepSeek 改为已有 function_call 传输，显式关闭思考模式。此次验收没有修改前端代码。

## 实际验收

原问题：请根据知识库，说明本项目如何在服务崩溃或网络断线后继续研究，并避免重复创建任务。请列出相关机制、引用来源，并说明哪些部分还缺少验证证据。

运行：`wf-5c0b351c-e9c1-43b7-b62f-e7929f87e676`。知识库模式、自主研究、计算器开启、历史研究参考开启；真实 DeepSeek 与 RAGFlow 调用。

| 检查 | 结果 |
| --- | --- |
| 后台结果与页面 | SUCCEEDED；页面“研究完成” |
| 报告内容 | 分别回答崩溃恢复、SSE Last-Event-ID 续传、创建幂等，以及尚缺验证的部分 |
| 引用与核查 | 3 个知识库来源引用、4 条论断、2 次完成核查；来源面板可打开摘录 |
| 项目摘要 | READY；105,918 → 61,879 字节，上限 64,000 |
| 创建请求重放 | 原 key、原请求返回 202、原 runId、replayed=true；改问题返回 409；任务数均为 24 |
| 自动保存 | 页面确认已保存研究进度与待办 |
| 自动测试 | Python 806 通过、32 跳过；Java WorkflowServiceTest、ConversationSummaryServiceTest、ResearchRecallAssemblerTest 通过；差异与脚本语法检查通过 |
| 服务及启动复用 | 前端 5173、Java 8080、执行服务 8091、专用数据库 55433 就绪；重复启动未新增文档版本 |

“研究完成”表示这次问题已经回答并引用来源；不是实际崩溃、浏览器断网故障注入验收通过。报告保留了真实恢复测试与未知外部调用结果的验证缺口。单次成功不代表所有研究问题或所有模型调用都稳定通过。旧失败报告是历史快照，刷新不会把它改成新结果。

## 查看与启动

打开 http://127.0.0.1:5173/app/ ，在研究档案查看最新记录；也可点“新研究”，选择“知识库”和“自主研究（候选）”，重新提交原问题。

```bash
bash scripts/local-services.sh start
bash scripts/local-services.sh status
bash scripts/local-services.sh stop
```

证据文件均位于本仓库 `target/local-services/`：

- `knowledge-original-status.jpg`：原问题、研究完成、引用和摘要状态。
- `knowledge-original-report.jpg`：完整报告页面。
- `knowledge-original-source.jpg`：来源面板与知识库摘录。
- `knowledge-original-verification.json`：原生运行、摘要、证据和幂等验收响应。
- `worker-regression.xml`：Python 测试结果。

这些运行证据与私有配置被 Git 忽略。既有研究记录保留。接口配置参考：[DeepSeek 函数调用](https://api-docs.deepseek.com/guides/tool_calls/)；分片配置参考：[RAGFlow HTTP API](https://ragflow.io/docs/v1.0.0-rc1/http_api_reference)。

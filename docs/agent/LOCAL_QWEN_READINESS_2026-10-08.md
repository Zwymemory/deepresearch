# 本机 Qwen3.5-9B 可用性实测

日期：2026-10-08。结论：**可以本地运行，值得作为辅助模型试用；当前不适合直接替换主 Agent。**

## 测试环境与范围

- 设备：Apple M5，24 GiB 内存；前端、Java、Python worker 与数据库同时运行。
- Ollama：`0.34.2`，本机接口 `http://127.0.0.1:11434`。
- 已安装模型：`qwen3.5:9b`，约 6.6 GB，`Q4_K_M`；摘要 `6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7`。本地元数据标为 `9.7B`，本文按用户安装的标签称为 9B，不替换或下载在线新版。
- 共 7 次实际本地推理：5 项初测，2 次针对失败的接口诊断。未调用付费远端模型，也没有切换正在运行的应用模型。
- 原生 API 使用 `think=false`、8,192 上下文、最多 768 输出 tokens、温度 0、串行执行。实际原生输入为 192–1,467 tokens；没有测试长上下文、多并发或完整线上研究。
- OpenAI 兼容接口实测时，Ollama 报告配置上下文为 65,536，实际输入约 2,800 tokens。配置容量不代表已验证同等长度的可靠性。

官方提供工具调用与 thinking 控制接口；本轮采用真实调用检验本地版本，不将模型宣传能力当作项目验收。[Ollama 原生 API](https://docs.ollama.com/api/chat)、[OpenAI 兼容接口](https://docs.ollama.com/api/openai-compatibility)、[Qwen 官方模型说明](https://huggingface.co/Qwen/Qwen3.5-9B)。

## 逐项结果

| 场景 | 实测结果 | 耗时 |
| --- | --- | --- |
| 阅读项目真实 `FileReadTool.java` | JSON 格式、工具名、读取长度、绝对路径及无 shell 能力判断正确；**把符号链接越界保护判断错了** | 20.37 秒，含首次加载 |
| 带条件与版本的去重分组 | 正确合并同义两条，分开来源变化规则及 v1/v2 不同数值；来源 ID 完整 | 8.02 秒 |
| 接收真实失败进程回执后修订方案 | 从按问题缓存改选按问题、项目、来源指纹缓存；保留“待验证”，没有声称修订已经通过 | 12.42 秒 |
| 阅读修订后的验证结果 | 正确说明最小实验通过，同时说明未覆盖生产系统 | 10.27 秒 |
| 项目现有 `OpenAIAgentModel` + `AgentDecision` 合同 | 返回结果未通过 Pydantic 校验，初次保存了失败类型 | 33.01 秒 |
| 相同请求的诊断重跑 | HTTP 200，工具名/动作正确；**漏掉必填字段 `reason`**，校验失败 | 14.31 秒 |
| 候选适配：增加 `reasoning_effort=none` | 单次返回完整的 `search/web_search/query/reason`，通过同一合同校验 | 9.27 秒 |

最后一项是独立兼容接口对照，没有改动生产适配器。单次通过不能证明关闭 thinking 可以普遍解决输出问题，仍需固定任务集的重复验证和失败回退。

原生四次调用的输出速度约 **9.0–10.3 tokens/秒**。Ollama 报告 8K 配置时加载占用约 **5.64 GB**，兼容接口默认配置时约 **7.80 GB**；这不是操作系统整体峰值内存。测试结束后已卸载模型释放占用，Ollama 服务可继续按需加载。

## “失败后调整”到底验证了什么

本轮在独立 Python 进程运行了一个明确标识的最小缓存键夹具，测试三个条件：同项目同来源可复用、来源变化不得复用、不同项目不得复用。

1. 初始 `query_only` 策略：3 项测试，2 项失败，退出码 1。
2. 把真实回执交给 Qwen，它选择预登记的 `query_project_source_hash` 策略，并要求重新验证。
3. 脚本执行该候选策略：3 项通过，退出码 0。
4. Qwen 根据新回执给出限定范围的结论。

模型从两个预定义候选中选择，没有生成任意 shell 命令，也没有自行实现整套缓存。该实验说明这条短反馈链具备试用价值，**不表示 DeepResearch 已经接入验证执行器或生产缓存已经修复**。

代码理解的错误也有确定性对照：现有 `FileReadToolTest` 包含真实符号链接越界测试，本轮单独运行该类，四项测试通过。模型的错误判断没有被当成代码事实。

## 建议接入方式

| 职责 | 首版安排 |
| --- | --- |
| 标签、资料分类、重复候选识别 | Qwen 可试用；先验证结构、来源 ID 与适用条件，失败使用现有确定性规则 |
| 短代码说明、日志归类 | Qwen 可给候选解释，关键结论须结合原文件与真实测试复核 |
| 主计划、关键方案选择、最终收口 | 暂沿用现有 DeepSeek |
| 权限、预算、进程退出码、测试是否通过 | 应用代码与执行回执负责判定 |
| 原始记忆与证据 | 保存原文和来源；辅助模型不直接覆盖原记录 |

实施前还需补齐：按角色选择模型、Qwen thinking 参数和上下文配置、输出合同校验、超时与重试预算、并发限制、回退记录，以及至少覆盖否定、版本、跨项目隔离、来源 ID 丢失和格式错误的固定用例集。首轮建议串行、8K 有限输入，按读取片段处理项目；是否扩大上下文由后续性能验证决定。

现有规则已能完成的摘录和保守去重保持原路径。启用本地辅助模型的收益目标是补充语义判断，并通过实测比较质量、等待时间和远端调用数，不预设它必然更快。

## 复现与证据

在后端仓库根目录，使用已有 Python 虚拟环境运行：

```sh
workflow-service/.venv/bin/python .codex-handoffs/project-agent-20261008/probe_qwen.py
workflow-service/.venv/bin/python .codex-handoffs/project-agent-20261008/probe_adapter_diagnostic.py
ollama stop qwen3.5:9b
```

脚本仅使用回环地址推理和隔离的小测试进程，不读取应用的私有服务配置。重复运行会更新相应结果文件。

- [初测记录](../../.codex-handoffs/project-agent-20261008/qwen-probe.json)
- [接口诊断](../../.codex-handoffs/project-agent-20261008/qwen-adapter-diagnostic.json)
- [初始失败实验](../../.codex-handoffs/project-agent-20261008/experiment-query_only.json) / [修订后实验](../../.codex-handoffs/project-agent-20261008/experiment-query_project_source_hash.json)
- [被测代码指纹](../../.codex-handoffs/project-agent-20261008/source-manifest.json)
- [文件读取测试结果](../../.codex-handoffs/project-agent-20261008/TEST-com.deepresearch.agent.FileReadToolTest.xml)
- [服务与模型清理状态](../../.codex-handoffs/project-agent-20261008/completion-checks.json)

本轮验收是模型与实施准备，不新增前端功能；页面截图安排在分阶段功能接入后，与真实运行回执一起提交。

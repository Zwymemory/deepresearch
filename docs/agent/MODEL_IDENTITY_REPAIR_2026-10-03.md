# 模型身份兼容与失败诊断修复（2026-10-03）

## 结论与证据边界

本轮修复隔离验收 sidecar 的模型身份观察器、异常分类和用量入账。旧观察器将请求别名与响应 `model` 要求为同一字串，在身份拒绝后丢失正常解析前的有效用量，并被适配器归为传输错误。离线模拟 HTTP200 的规范响应可以复现这一缺陷。

旧真实运行只保存了 `UNRECOGNIZED`，没有保存实际返回标识或可用 token 数；不能恢复或推定它们，也不能据此认定模型 API、RAGFlow 或检索链路故障。本轮零真实模型、检索、RAGFlow 或研究请求。模拟通过不证明真实验收通过。

## 官方契约与有限政策

- [DeepSeek Change Log，2026-09-10 / API changes](https://api-docs.deepseek.com/updates/#date-2026-09-10)：旧 V4 Flash / Flash Vision Exp 已退役；旧请求名暂时路由到 V4.1 Flash，推荐请求名为 `deepseek-flash`。
- [Chat Completions API，Request.model 与非流式 Response.model / Example](https://api-docs.deepseek.com/api/create-chat-completion/)：请求枚举包含 `deepseek-flash`；响应 `model` 是必需字符串，表示使用的模型，具体官方示例为 `deepseek-flash`。文档没有保证请求与响应逐字相同。

政策版本为 `deepseek-flash-2026-09-10/1`，仅绑定 `POST https://api.deepseek.com/chat/completions`。请求名必须等于显式配置的期望值。本项目文本研究范围只支持下表两个请求名，不加入 Vision 请求范围。

| 显式请求名 | 唯一可接受的响应 model | 政策原因 |
| --- | --- | --- |
| `deepseek-flash` | `deepseek-flash` | `accepted_canonical` |
| `deepseek-v4-flash` | `deepseek-flash` | `accepted_legacy_route` |

旧请求到规范响应的映射结合了官方兼容路由公告和规范响应示例，是有限兼容政策；不保证所有未来响应名，也不固定底层模型 revision。旧字面响应、Pro、日期后缀、展示名称、未知或缺失身份仍拒绝。观察到的规范标识不替换为请求别名。

`prepare-agent-live-runtime.py --start` 现在要求显式 `--model-name`；这只是未来另行授权准备时的接口。本轮没有执行启动、修改环境变量或迁移当前服务。通用适配器仍支持其既有 provider 设置；这里的特定 DeepSeek 政策由隔离 sidecar 安装，而非全局强制给所有 provider。

## 数据与失败链路

1. 请求最多 128 KiB、响应最多 512 KiB；重复 JSON 键、非有限数、畸形 JSON、无效形状均保守拒绝。有效地址、请求方法和配置身份在发送前检查；关闭自动重定向，检查响应有效地址。
2. 诊断保存版本、固定原因、accept/reject、地址匹配布尔值、请求类别、字段 presence/type。只有有限已知标识能原样出现；未知字符串只保存长度与 SHA-256，不保存原文。字段长度、哈希和枚举在持久化与导出时再次按白名单过滤；提示词、响应正文、密钥、异常文本不进入诊断。
3. 身份拒绝在 observer → `OpenAIAgentModel` → `AgentBudgetGateway` → 安全导出链路统一为 `identity_validation` / `MODEL_IDENTITY_INVALID`，`PROVIDER` 类，`retryable=false`。真正的连接错误、HTTP 鉴权、限流、上游错误继续沿既有分类处理。
4. 对可解析的响应，拒绝前分别读取合法 `prompt_tokens` / `completion_tokens`：整数范围 0 至 int64 最大值；布尔、浮点、负数、溢出、缺失独立记为未知。零是合法用量。不累加 total、cache 或 reasoning 分项。畸形或超限 JSON 整体不可安全解析时不猜测用量。
5. `UNKNOWN` 是内容不可使用的状态，可以同时保存已知用量。结果为空、不自动重试。真实临时 PostgreSQL 的测试确认同一操作只入账一次，后续重放不再发出请求。

## 离线验收

核心测试通过真实 HTTPX MockTransport 驱动 observer → adapter → gateway → 安全 failure export；无外部网络。覆盖规范请求、旧别名、其他模型、缺失/null/数组/对象/超长/控制字符/秘密样式标识、lone surrogate、部分/无效/零用量、重复键、超限正文、地址变体、请求模型错误、重定向、有效响应地址变化和原有 HTTP/传输分类。

lone-surrogate 用例以 `ensure_ascii=True` 生成可传输 JSON 转义字节，确保恶意身份进入解析与校验，而不是在 HTTPX fixture 构造阶段失败。既有断言仍要求明确身份错误和合法用量保留。

复现命令（Python 使用安装了仓库锁定测试依赖的 3.12 环境）：

```sh
PYTHONPATH=workflow-service/src python -B -m pytest workflow-service/tests -m 'not integration'
ruff check workflow-service/src workflow-service/tests
python3 -B -m unittest discover -s scripts/tests -p 'test_agent*.py'
AGENT_PYTHON=/absolute/path/to/python3.12 mvn -B -Pintegration verify
PYTHONPATH=workflow-service/src python -B contracts/agent/v0/validate.py --peer-checkout /absolute/path/to/evidence-checkout
make showcase-check PYTHON=python3
python -B -m pytest reranker-service/tests
bash scripts/verify-public-release.sh
```

提交候选前：身份链路 32 例、脚本 30 例、全量非 integration Python、Ruff、冻结契约、showcase 与 reranker 均通过；Maven 与提交后公共门禁结果在外部修复回执逐命令记录，并绑定完整候选 SHA。公共门禁使用该提交的临时干净 detached checkout，保留本地无关未跟踪文件。独立 B 审查另行绑定候选，不把准备阶段意见当作代码审查通过。

## 保留的历史与下一步

只读哈希核对旧任务授权、7 行停止 journal、旧 HTTP200 观察回执、audit、裁决与 source manifests，共 12 个产物；全部保持原字节。未重启停止批次，未放宽调用或费用上限，未 push 或部署。历史失败保持失败。

真实返回命名、模型决策语义和五项真实场景尚未在修复候选上验证。是否另行进行最小身份诊断或新受限批次，由协调审查后决定。本轮不以离线 fixture 成功宣称真实 provider 可用或完整验收成功。

# 前端本地预览

在不启动 Java、不调用模型、网页搜索或 RAGFlow 的情况下预览 `/demo.html` 的各种状态。所有 API 都由 `mock_server.py` 在同源模拟，正文、标题与链接均为**合成演示数据**，页面底部会显示“本地演示数据”标识。

```bash
python3 scripts/frontend-preview/mock_server.py
# 打开 http://127.0.0.1:8090/demo.html?scenario=success
```

在“连接与身份”中点击“签发 USER Dev Token”即可获得预览专用的假 Token（只被预览服务器接受）。

| `?scenario=` | 展示 |
|---|---|
| `success`（默认） | 规划 → 执行 → 审阅 → 合成 → 完成；勾选网页搜索时为知识库 + 网页混合来源 |
| `insufficient` | 一轮补充检索后证据不足，部分成果 + 未完成目标 |
| `failed` | 合成阶段模型重试后失败（`MODEL_PROVIDER_FAILED`） |
| `slow` | 执行阶段很慢，用于演示取消 |
| `disconnect` | 第一条 SSE 连接在 4 条事件后断开，验证 `Last-Event-ID` 续传 |
| `unknown` | 首次创建返回 502，验证“创建结果未知 → 原请求安全重试” |
| `noweb` | 网页搜索未配置 |
| `langgraph` | 与默认 LangGraph 路径相同：`finalResponse` 不含 `citationDetails` |

运行归属于创建它的 Bearer Token，换一个 Token 读取会得到 404，用于验证前端的身份隔离；幂等键也按 Token 隔离。

“自主研究（候选）”与“Single Agent”模式也有对应的合成响应。

## 截图与自动检查

`capture.mjs` 用 Playwright 驱动上述场景，在浅色/雾夜两种主题、1440×900 与 390×844 两种尺寸下截图，并检查控制台错误、横向溢出、减少动态效果和主题记忆。需要本机已有 `playwright-core`（不在仓库依赖中）：

```bash
python3 scripts/frontend-preview/mock_server.py &
PLAYWRIGHT_CORE=/path/to/node_modules/playwright-core \
CHROME_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
node scripts/frontend-preview/capture.mjs   # 输出到被忽略的 output/playwright/frontend-preview/
```

预览通过只说明页面在这些合成响应下的呈现与交互正确，**不代表**真实后端、模型或检索质量。

## React 预览

React 版本位于 `frontend/`，入口为 `/app/`。经 Vite 同源代理连接本预览服务器，即可用真实适配器跑完整流程：

```bash
python3 scripts/frontend-preview/mock_server.py &
DEEPRESEARCH_API_PROXY=http://127.0.0.1:8090 npm --prefix frontend run dev   # http://127.0.0.1:5173/app/?scenario=success

export PLAYWRIGHT_CORE=/path/to/node_modules/playwright-core
export CHROME_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
node scripts/frontend-preview/journey-react-live.mjs   # 真实适配器旅程 → output/playwright/react-live/
node scripts/frontend-preview/capture-react.mjs        # 示例模式版式与截图 → output/playwright/react-preview/
```

两个版本可以同时对比：V1 在 `http://127.0.0.1:8090/demo.html`，React 在 `http://127.0.0.1:5173/app/`。

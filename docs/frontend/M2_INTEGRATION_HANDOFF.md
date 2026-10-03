# React 前端 · 第 2 阶段集成交接（给 Codex）

日期：2026-10-03。本文件描述前端已交付的内容，以及需要由后端负责人（Codex）实施的打包、路由、CI 与公开数据投影。前端分支只修改了 `frontend/`、`scripts/frontend-preview/` 与 `docs/frontend/`，**没有**修改 Java/Spring、Python、API 契约、数据库、认证策略、后端测试、Maven、Docker 或仓库级 CI。

## 1. 构建产物

| 项目 | 值 |
|---|---|
| 源码目录 | `frontend/` |
| Node | ≥ 20.19（本地验证使用 24.16） |
| 安装与构建 | `npm --prefix frontend ci && npm --prefix frontend run build` |
| 输出目录 | `frontend/dist/`（已被 `.gitignore` 忽略，不提交） |
| 产物 | `index.html`、`favicon.svg`、`assets/index-<hash>.js`、`assets/index-<hash>.css` |
| 资源基路径 | `/app/`（Vite `base`），HTML 中引用均为 `/app/assets/…`、`/app/favicon.svg` |
| 入口 URL | `/app/`（即 `/app/index.html`） |
| 客户端路由 | 无。视图状态只在内存与查询参数中（`?demo`、`?state=`），不需要 SPA 回退到 index 以外的路径 |
| 外部资源 | 无 CDN、无外部字体；`index.html` 有一段内联脚本（首帧前确定主题） |

本地已验证 `vite preview` 在 `/app/` 下返回 HTML、哈希资源与 favicon（均 200，类型正确）。

## 2. 需要 Codex 实施的 Spring 集成

1. **打包**：在 Maven 构建中执行前端构建，并把 `frontend/dist/**` 放到 classpath `static/app/`（方式由 Codex 选择，例如 frontend-maven-plugin 或独立构建步骤后复制）。验收：`target/classes/static/app/index.html` 与 `assets/*` 存在。
2. **路由**：`GET /app/` 返回 `/app/index.html`；`GET /app` 重定向到 `/app/`。其余 `/app/**` 按静态资源返回。
3. **安全规则**：当前 `SecurityConfig` 只放行 `/`、`/demo.html` 并以 `anyRequest().denyAll()` 结尾。需要对 GET 放行 `/app`、`/app/`、`/app/index.html`、`/app/favicon.svg`、`/app/assets/**`；不放宽其他路径、不改变 `/api/**` 的认证要求、不新增 CORS。
4. **缓存头**：`/app/index.html` 与 `/demo.html` 一致使用 `no-cache, no-store`；`/app/assets/**`（文件名含内容哈希）可使用 `public, max-age=31536000, immutable`。
5. **若以后加入 CSP**：需要允许 `index.html` 中的内联主题脚本（建议按哈希放行）以及 `script-src 'self'`；样式全部来自同源 CSS。
6. **同源要求**：页面只会把 Bearer Token 发送给自身同源的 `/api/**`，因此 React 必须与 API 由同一 origin 提供。

### `/demo.html` 的建议过渡

| 阶段 | `/demo.html` | `/app/` | 说明 |
|---|---|---|---|
| A（当前起） | 保持 V1 页面不变 | React 新版 | 两者共存，便于对照验收 |
| B（React 真实联调验收通过后） | 302 到 `/app/` | React | 保留 V1 于 `/demo-v1.html`（需要相应放行规则） |

两页共用浏览器存储键（`deepresearch.console.*`）与格式：在同一 origin 下，一个页面创建或恢复的运行可以在另一个页面继续查看；主题偏好也共享。

## 3. 前端依赖的现有 API（无需修改）

| 路由 | 前端用法 |
|---|---|
| `GET /api/ping` | 纯文本响应即判定为 Java 服务；预览模拟服务器返回 `{"preview": true}`，页面会显示“预览服务器 · 模拟 API” |
| `POST /api/auth/dev-token` | 仅本地开发；请求 `roles: ["USER"]`、`ttlSeconds: 7200`；读取 `token` |
| `GET /api/research/tools/capabilities` | 读取 `webSearch.configured`；`false` 时阻止提交网页搜索 |
| `POST /api/research/workflows` · `POST /api/research/agents` | `Authorization: Bearer`、`Idempotency-Key`；请求体 `{question, requestedTools, sessionId?}`；20 秒超时；网络错误或 5xx（503 除外）视为“结果未知”，只允许用完全相同的请求体与键重放 |
| `POST /api/research/agent` | Single Agent 同步调用；45 秒超时；无事件流、无取消 |
| `GET /api/research/workflows/{runId}` | 权威快照（含 `/agents` 创建的运行） |
| `GET /api/research/workflows/{runId}/events` | fetch 读取 SSE（需要 Bearer，因此不用 EventSource）；`id` 为 `runId:eventId`；重连携带 `Last-Event-ID`；3 分钟服务端超时后按游标续传；401/403/404/409 停止自动恢复 |
| `POST /api/research/workflows/{runId}/cancel` | 服务端取消；关闭事件流本身不会被描述为取消 |

浏览器不调用 `/internal/**`，也不持有委派执行凭据。

## 4. 公开数据投影请求

每项包括：用户需求、当前契约、建议的最小改动、前端验收标准。前端已对缺失情况显示真实的“不可用/未记录”状态。

### R1 · 默认 LangGraph 工作流的来源详情（优先级最高）
- **需求**：点击 `[来源N]` 时看到标题、类型、网页地址或知识库文档名、摘录。
- **当前**：`WorkflowService.finalize` 为非 agent 运行只写入 `answer`、`citations`、`citationContract`、`insufficientEvidence`；只有 Dify 路径与 `/agents` 运行带 `citationDetails`。页面因此只能显示“来源信息不足”。
- **建议**：在 LangGraph 运行的 `finalResponse` 中加入与 Dify 相同形状的 `citationDetails: [{sourceId, kind, title, url?, excerpt}]`，`sourceId` 与 `citations` 一一对应。
- **验收**：同一运行的每个引用 ID 恰有一条详情；`kind ∈ {KNOWLEDGE_CHUNK, WEB_SEARCH_SNAPSHOT, WEB_ORIGINAL}`；知识库条目无 `url`；页面显示标题与摘录且不再出现“详情未记录”。

### R2 · 来源日期与读取方式
- **需求**：区分发布、更新、检索与事实适用日期，以及摘要/全文读取。
- **当前**：`citationDetails` 无日期字段；读取方式只能由 `kind` 粗略区分。
- **建议**：在每条 `citationDetails` 中加入可选 `publishedAt`、`updatedAt`、`retrievedAt`、`applicableAt`（ISO-8601）与 `readMode`（如 `SEARCH_SNIPPET`/`FULL_PAGE`/`KB_CHUNK`）；未知时省略，不填默认值。
- **验收**：字段缺失时页面仍显示“未记录”；存在时逐项显示，检索时间不被当作发布时间。

### R3 · 已记录的分歧与裁决（只读）
- **需求**：比较视图中区分“用户自选比较”和“后端记录的矛盾/适用条件差异”，并显示裁决摘要与引用。
- **当前**：无公开字段（相关调查仅在内部 evidence 接口中）。
- **建议**：经认证、只读的 `finalResponse.disagreements[]`（或 `GET /api/research/workflows/{runId}/disagreements`），每项含 `statement`、`sourceIds[2+]`、`relation`（`CONTRADICTION`/`DIFFERENT_CONDITIONS`/`UNRESOLVED` 等）、`summary`、`checkIds[]`。
- **验收**：仅返回该身份可访问的运行；sourceId 全部出现在 `citations` 中；页面去掉“未来契约示例”标签并显示真实记录。

### R4 · 论断级支持结果
- **需求**：只有在映射经过验证时才显示论断级证据指标。
- **当前**：`/agents` 运行公开 `claims`，但形状未在文档中定义；工作流模式没有论断数据。
- **建议**：为 `claims` 提供稳定的文档化形状（论断文本、`sourceIds`、检查结果码、在答案中的位置或段落标识）；工作流模式如需同类能力，复用同一形状。
- **验收**：每条论断的 `sourceIds` 都在 `citations` 中；前端可以把论断定位到正文段落；没有映射时保持引用级体验。

### R5 · 运行中的证据元数据
- **需求**：“证据随到随显”。
- **当前**：`TASK_COMPLETED` 事件只有 `evidenceCount`。
- **建议**：在事件 payload 中加入已脱敏的 `sources: [{sourceId, kind, title, url?}]`（不含正文或 provider 原文）。
- **验收**：事件中的 `sourceId` 与最终 `citations` 可对应；未提供时页面继续只显示计数。

### R6 · Single Agent 来源详情
- **当前**：`AgentResearchResponse` 没有 `citationDetails` 字段。
- **建议**：按 R1 形状加入可选 `citationDetails`。
- **验收**：同 R1。

### R7 · 服务端研究历史（可选）
- **当前**：“最近研究”只存在浏览器本地（与 V1 相同）。
- **建议**：`GET /api/research/workflows?limit=…` 返回当前身份的运行摘要（runId、问题摘要、状态、时间）。
- **验收**：只返回当前身份的运行；页面在可用时显示服务端列表并标明来源。

## 5. CI 请求

在仓库 CI 中增加前端作业（Node 20.19+）：

```bash
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
```

浏览器旅程脚本（`scripts/frontend-preview/journey-react-live.mjs`、`capture-react.mjs`）需要 Chrome 与 Playwright，可作为可选作业。`frontend/dist` 与 `node_modules` 已忽略；当前提交通过 `scripts/verify-public-release.sh --skip-history`。

## 6. 检查状态

### 已由前端完成（本地 Chrome，合成数据）
- 32 项单元测试：SSE 分块解析、按游标重连与重复事件忽略、致命状态停止、取消为服务端 POST、相同键与请求体重放、跨身份禁止重放、缓存按身份分区、Token 存储规则、各模式响应形状、失败说明、Java/模拟服务识别、示例模式无网络。
- 31 项浏览器旅程（React 真实适配器 + 预览模拟 API，经 Vite 同源代理）：创建、引用、刷新续传、服务端取消、断线游标恢复、结果未知重放、身份切换隔离、失败说明、LangGraph 缺详情、Single Agent、自主研究路由、手机宽度、示例模式零请求。
- 68 项版式检查：两种主题 × 1440/1024/390/320 × 8 个示例状态。
- `vite preview` 在 `/app/` 下正确提供产物。

### 待 Codex 完成或联调
- Spring 打包、`/app/` 路由与放行规则、缓存头；`/demo.html` 过渡策略。
- 在 8080 的真实 Java 服务上验证：创建、快照、SSE 续传（含 3 分钟超时重连）、取消、401/403/404/409/503 文案、Dev Token、能力查询、Dify 与 LangGraph 两种引擎、Single Agent。本阶段 8080 没有运行，以上均未在真实服务上执行。
- R1–R7 公开投影。
- CI 作业。

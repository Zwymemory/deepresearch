# DeepResearch 前端（React）

React + TypeScript + Vite 研究工作台。默认连接**同源**的现有公开 API；也提供明确标识、完全不联网的示例模式。现有 V1 页面 `/demo.html` 在迁移期间保持不变。

```bash
npm ci                 # Node >= 20.19
npm run dev            # http://127.0.0.1:5173/app/
npm test               # 适配器与领域纯函数测试（无网络）
npm run lint && npm run typecheck
npm run build          # 输出 dist/，资源基路径 /app/
npm run preview        # http://127.0.0.1:4173/app/
```

### 连接 API（开发）

页面只会把 Bearer Token 发给自身 origin。开发时用 Vite 同源代理（显式开启）：

```bash
DEEPRESEARCH_API_PROXY=http://127.0.0.1:8090 npm run dev   # 预览模拟 API（scripts/frontend-preview/mock_server.py）
DEEPRESEARCH_API_PROXY=http://127.0.0.1:8080 npm run dev   # 本机 Java 服务
```

未设置代理时，真实模式会显示“API 未连接”。示例模式：`/app/?demo`；固定状态：`/app/?state=running|report|partial|cancelled|inspect|compare|compare-recorded`。

## 结构

| 目录 | 职责 |
|---|---|
| `src/api/` | HTTP、身份与存储、幂等创建、公开路由、SSE 解析与续传 |
| `src/live/` | 真实控制器（TanStack Query 中的单一运行状态、事件流、取消、身份隔离） |
| `src/domain/` | 契约类型、引用规范化、安全 Markdown、事件 reducer、失败说明 |
| `src/demo/` | 确定性示例数据与本地回放（不引用 `src/api/`） |
| `src/features/` | shell、composer、running、report、evidence |
| `src/ui/`、`src/styles/` | 图标与 Airy/Mist 语义令牌 |

文档：[第 1 阶段](../docs/frontend/REACT_M1_2026-10-03.md) · [第 2 阶段](../docs/frontend/REACT_M2_2026-10-03.md) · [集成交接](../docs/frontend/M2_INTEGRATION_HANDOFF.md) · [研究可见性与研究笔记](../docs/frontend/R1_R2_2026-10-05.md) · [研究笔记接入](../docs/frontend/NOTEBOOK_INTEGRATION_2026-10-05.md)。

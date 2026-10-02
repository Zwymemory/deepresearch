# DeepResearch 前端（React）

Milestone 1：React + TypeScript + Vite 基础，使用**带标识的示例数据**演示入口、运行中、报告、证据检查与来源比较。它不连接 Java API、不发送凭据、不发起模型或检索调用。现有可用页面仍是 V1 的 `/demo.html`。

```bash
npm ci                # Node >= 20.19
npm run dev           # http://127.0.0.1:5173/
npm test              # 纯函数单元测试（引用规范化、Markdown、事件 reducer）
npm run lint && npm run typecheck
npm run build && npm run preview   # http://127.0.0.1:4173/
```

可复现的状态链接：`/?state=running`、`report`、`partial`、`cancelled`、`inspect`、`compare`、`compare-recorded`。

## 结构

| 目录 | 职责 |
|---|---|
| `src/domain/` | 依据现有公开契约的类型、引用规范化、安全 Markdown 解析、运行事件 reducer（纯函数，有单测） |
| `src/demo/` | 确定性示例数据与本地回放；“未来契约”示例单独标注 |
| `src/features/` | shell、composer、running、report、evidence（检查面板、比较、全部来源） |
| `src/ui/`、`src/styles/` | 图标与 Airy/Mist 语义令牌 |

设计、能力矩阵与验证见 [docs/frontend/REACT_M1_2026-10-03.md](../docs/frontend/REACT_M1_2026-10-03.md)。

# 研究档案式界面重设计（参考 RhineLabUI）

日期：2026-10-05。依据 `CLAUDE_RHINELAB_FRONTEND_REDESIGN_HANDOFF_2026-10-05.md`。只修改 `frontend/`、`scripts/frontend-preview/` 与 `docs/frontend/`；不改后端、契约、数据库、认证、部署或 CI。

## Stage 0：基线与实施图

### 基线

| 项目 | 值 |
|---|---|
| 基线提交 | `952b472`（研究笔记接入研究进度契约），分支 `claude/deepresearch-frontend-react`，保持不变 |
| 新分支 | `claude/deepresearch-rhinelab-redesign`，从 `952b472` 创建 |
| 返回保留版本 | `git switch claude/deepresearch-frontend-react`（或 `git checkout 952b472`） |
| 基线验证 | `check-r1r2` 63/63、`journey-react-live` 31/31、`check-notebook` 36/36（均为**模拟 API**）；vitest 49 |
| 真实后端验收 | 仍待完成（本机没有运行包含研究进度分支的 Java 服务） |

### 需要保留的契约与行为

- 真实 / 示例模式分离：示例只在 `?demo` 或 `?state=` 下出现，不联网，处处标注。
- 身份隔离：查询键按身份作用域分区；切换身份清除旧内容。
- 创建 / SSE / 取消 / 刷新续传；Durable Workflow、自主研究、Single Agent 的差异说明。
- 引用安全：只渲染契约内的引用编号；不推测链接。
- 研究进度契约：以 `(project_id, source_run_id)` 标识；列表只在打开时请求、最多 20 条候选、空列表不代表没有更早的记录；没有保存日期、文件夹或分页；载入只新建会话并返回上下文（“尚未传入模型；未开始研究”），POST 防重、刷新后只用 GET 恢复；删除两步确认。

### 参考的空间表达（只借用原则，不复制代码、素材或字体）

RhineLabUI：倾斜透视中的卡带阵列（列为类别）；选中时浅抬起，详情时向镜头抽出（左侧物体、右侧文字）；HUD 式细线、角标、位置刻度；粗体几何字标配细线副标；描边强调按钮；淡同心圆背景。

### 实施图

| 区域 | 变更 |
|---|---|
| `styles/tokens.css` | Ivory / Smoked 两种外观；对比度逐项测算；细线、小圆角、平面阴影；时长分级 |
| `app/theme.ts`、`index.html` | 外观名改为 `ivory` / `smoked`；存储键与取值（`light` / `dark`）不变，既有选择延续 |
| `shell/TopBar.tsx` | 字标 + 细线副标；“档案”与“最近运行（本机）”分开入口 |
| `composer/EntryView.tsx` | 问题框即时可用；右侧装饰性卷宗堆叠；研究档案与本机记录两条明确路径；新用户空状态 |
| `archive/ArchiveView.tsx`（新） | 档案全视图：CSS 透视卷宗阵列（原型）+ 同步的可访问列表、搜索、状态筛选、选中面板、阅读层 |
| `archive/RecordParts.tsx`（由 `memory/Notebook.tsx` 迁移） | 快照章节、载入 / 删除、已载入面板；原研究笔记对话框由档案视图取代 |
| `app/App.tsx` | 新增 `archive` 视图与 `?state=archive` / `?state=record`；列表只在档案打开时请求 |
| 报告 | 无衬线标题、17px 正文、编号章节、细线 |
| 检查脚本 | 新增 `check-archive.mjs`；既有脚本改用档案流程 |

### 改动前截图

| 晴空 · 入口 | 雾夜 · 入口 | 晴空 · 研究笔记详情 |
|---|---|---|
| ![](media/rhinelab-before-light-1440-entrance.jpg) | ![](media/rhinelab-before-dark-1440-entrance.jpg) | ![](media/rhinelab-before-light-1440-record.jpg) |

| 雾夜 · 报告 | 手机 · 入口 | 手机 · 研究笔记详情 |
|---|---|---|
| ![](media/rhinelab-before-dark-1440-report.jpg) | ![](media/rhinelab-before-light-390-entrance.jpg) | ![](media/rhinelab-before-light-390-record.jpg) |

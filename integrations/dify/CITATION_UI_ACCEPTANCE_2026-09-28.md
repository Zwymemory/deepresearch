# 引用标题、网页跳转与恢复呈现验收

## 改动与复现

旧页面的正常网页结果在有 `WEB_SEARCH_SNAPSHOT` 元数据时已经有链接；但缺少详情时直接显示内部来源 ID，知识库来源没有标题/摘录，Single Agent 分支也未传递详情。三个已保存网页运行均含可信详情，不能据此断定用户当时看到的页面一定是缓存或一定缺少元数据。

新版卡片主视图展示 `[来源N] 标题`、网页地址和最多 280 字的摘录预览。网页标题在新标签页打开原始 HTTP(S) 地址，正文编号定位对应卡片。知识库显示“知识库文档”、实际文档名与摘录，不伪造公网链接。完整保存摘录和内部 ID 只出现在折叠的来源记录或原始 JSON 中。

详情只按 `citations` 中的来源 ID 精确匹配，保留原编号顺序；缺失、重复、未知类型或 ID 不匹配时显示“来源信息不足”，不按数组位置或模型文字猜链接。只允许绝对 HTTP(S) URL，拒绝其他协议、相对地址、控制字符与内嵌凭据。卡片标题使用 `target=_blank` 和 `rel="noopener noreferrer"`。所有标题、摘录和 ID 以 DOM 文本渲染。

摘录预览会选择首个较长的原始段落并截短，便于跳过短导航段落；它没有语义审阅作用。完整摘录保持原样。网页标记“搜索摘要”，普通网页跳转不承诺定位到摘要对应的原句。

## 准备阶段验证

- 前端 HTML SHA256：`a8176f0879c87731f7cf452a5cf87c7650fb62f16b6074750b20a1ceaf4d28a6`。
- 后端沿用真实验收版本 `182962f9d9be7e30b1188da39b7250c2f212a05e`。新版 HTML 仅在隔离浏览器中替换，未改变应用服务、DSL、凭据或知识库。
- [12 个离线浏览器场景](citation-ui-browser-check-2026-09-28.json)全部通过：单网页、多网页、KB、混合、缺详情、错 ID、重复详情、不安全 URL、无验证契约的历史记录、文本转义、Single Agent 详情及 SSE 重连。
- metadata 故意逆序时编号和标题仍对应正确 ID。知识库不生成外链；不安全地址不生成可点击元素。刷新前后的标题、链接、编号、折叠 ID 记录一致。
- 离线 SSE 场景两次流请求，第二次携带已保存游标，刷新与恢复后来源不变，仅一次 **fixture** 创建 POST。全部 API 都被 stub，未调用模型、网页 provider 或真实创建接口；不是新增真实断线故障注入。
- [三个真实保存运行](citation-ui-saved-check-2026-09-28.json)共 7 条引用：页面标题、URL、编号、内部 ID 与后端 `finalResponse` 精确对应，刷新后保持一致，正文编号可以打开引用面板。本次呈现验证只有 GET，没有新建真实 workflow。
- 实际点击 Python 官方文档卡片，浏览器新标签加载 `https://docs.python.org/3/library/asyncio.html`，并确认 `window.opener === null`。这证明普通网页跳转，不证明历史答案全部正确。

这些是呈现与恢复验证。答案支持和偶发模型空输出的修复结果由另一个固定版本报告记录；历史 4/5、V7 full37 与原失败保留。统一部署后的版本与真实 UI 核对另行补充。

### 截图

真实保存网页结果的新客户端预览：

![真实保存网页引用](media/citation-ui-citations-saved-web-2026-09-28.png)

合成混合来源 fixture（网页可打开，知识库无公网链接）：

![混合来源 fixture](media/citation-ui-citation-fixture-mixed-2026-09-28.png)

缺元数据 fixture（不推测 URL，内部 ID 默认折叠）：

![缺元数据 fixture](media/citation-ui-citation-fixture-missing-metadata-2026-09-28.png)

## 重跑离线浏览器验证

需要 Node/npm、Playwright CLI 和浏览器。从仓库根目录执行，使用独立 session：

```bash
PWCLI="${CODEX_HOME:-$HOME/.codex}/skills/playwright/scripts/playwright_cli.sh"
"$PWCLI" --session citation-ui open about:blank
"$PWCLI" --session citation-ui run-code --filename integrations/dify/check_citation_ui.js
"$PWCLI" --session citation-ui eval '() => window.citationUiReport'
"$PWCLI" --session citation-ui close
```

脚本拦截所有 API，来源与运行均为合成 fixture。生成截图只写入被忽略的 `output/playwright/`。不要把 fixture 成功当作真实 provider 或模型质量结果。

## 既有页面与历史记录

升级服务后，在原演示标签页用 `Cmd+Shift+R`（macOS）或 `Ctrl+Shift+R` 强制刷新，再打开最近运行。`/demo.html` 当前响应已有 `no-cache, no-store`；已打开标签中的旧脚本仍需重新加载。Token 不应复制到聊天或公开记录中。

旧结果若只保存内部 ID，新页面会明确显示信息不足；刷新不会补造历史元数据。恢复读取仍须使用拥有该运行权限的身份。原始 JSON 保留有序来源 ID，供技术核对，不作为默认引用主视图。

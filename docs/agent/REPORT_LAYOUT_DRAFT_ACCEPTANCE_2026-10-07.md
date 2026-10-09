# 研究报告排版初版验收

日期：2026-10-07。前端由 Claude 在现有第二版页面上实现，Codex 复审并查看真实报告。

前端版本：`d43ed55`（本地提交）。

## 本次变化

- 每条已发布结论独立成节，有小标题、正文和相邻来源按钮；提供章节导航。
- 版本、时间、条件及核查详情可展开；未知版本、未知时间和附带条件仍在折叠状态下显示。
- 证据记录默认折叠，保留论断、证据、检查数量；工具名称改为中文。
- 来源检查按引用展示相关结论，避免把整篇报告误当成一条引用语句。

## 验收结果

- 使用已有真实成功运行 `wf-5c0b351c-e9c1-43b7-b62f-e7929f87e676`，未重新发起研究。
- Codex 在正常连接身份的页面中核对：4 条结论、3 个来源、6 个引用按钮保留；来源 2 对应的两条结论分别显示；展开网络断线结论后，原始条件与未知版本、时间可查看。
- Claude 对已有真实运行的页面检查 29/29 通过，覆盖桌面浅色、桌面深色和 319 像素窄屏；最终截图包含原始问题标题。详细材料在 `.codex-handoffs/report-polish-20261007/claude-frontend/`。
- 前端单元测试 81 项通过；生产构建、类型检查、lint 与差异格式检查通过。构建仍提示已有 JavaScript 分包体积超过 500 kB，此次没有进行加载性能优化。
- Claude 的界面模拟回归分别为 63/63、31/31、16/16、37/37，通过；这些结果与真实报告页面核对分别记录，不当作后台功能验收。
- 本次只调整显示，不修改已封存的报告或后台研究记录。格式无法明确识别时，沿用原 Markdown 渲染。

服务地址：<http://127.0.0.1:5173/app/>。从研究档案打开上述成功报告即可查看；无需再次调用模型。

桌面真实报告截图：`target/local-services/report-layout-desktop.png`；正文与展开详情：`target/local-services/report-layout-detail.png`；桌面来源检查：`target/local-services/report-layout-desktop-source.png`。

窄屏复审截图：`target/local-services/report-layout-compact.jpg`；来源复审截图：`target/local-services/report-layout-source.jpg`。

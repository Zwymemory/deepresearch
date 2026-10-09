# 网页网络适配验收（2026-10-07）

- **问题**：本机 Fake-IP DNS 将 Python 官网解析为保留地址 `198.18.0.159` / `2001:2::9e`，原文读取被地址校验拒绝。网页搜索服务本身已能返回结果。
- **修复**：增加应用内 `google-doh` 加密 DNS 模式，本机配置已启用并重启。固定连接 Google 公网 DNS 地址，验证 TLS 域名，取得真实 A/AAAA 后逐一校验，再固定公网地址读取网页。未修改系统代理，未放行内网或保留地址，未关闭证书验证。
- **测试**：28 项相关测试全部通过，覆盖混合公网/私网结果、保留 IPv6、私网重定向、错误 DNS 响应、读取大小与时间限制。原文读取器直接访问英文 Python 官网也成功。
- **真实页面验收**：选择“自主研究（候选）＋网页”，关闭计算器和历史参考，询问 `asyncio.create_task` 的基本用途。1 次网页搜索、2 次原文读取、1 次论断核查均成功；最终为 **研究完成**，发布 1 条结论、1 个官网来源。两次读取来自同一页面，不计为两个独立来源。
- **当前状态**：前端 5173、Java 后端 8080、研究服务 8091 已开启；项目知识库仍正常，9 份文档检索通过。

验收运行：`wf-dc84498f-e4a1-45dc-8043-7cf0d3de38cb`。修复前对照运行：`wf-6fde307e-7117-4d21-acc4-e86e68f8206c`。
真实来源：[Python 官方文档：协程与任务](https://docs.python.org/zh-cn/3/library/asyncio-task.html)。
接口结果、事件、检索回执、证据及源码哈希保存在 `target/local-services/web-network-repair-2026-10-07.json`；页面截图为 `target/local-services/web-network-repair-report-2026-10-07.jpg`，均未包含凭据。

范围：需要网络可访问 Google DoH；网站拒绝访问、正文超限等仍会明确报错。本次未修改原有规划范围规则和模型核查格式校验。来源列表弹窗存在既有“示例数据”文案误标，实际报告和后端证据来自此次真实调用。

配置与启动方法见 [本机服务说明](../../scripts/LOCAL_SERVICES.md)。DNS 协议依据 [Google 官方 JSON DoH 文档](https://developers.google.com/speed/public-dns/docs/doh/json)。

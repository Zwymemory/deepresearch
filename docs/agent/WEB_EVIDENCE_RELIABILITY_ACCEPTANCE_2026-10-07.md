# 网页研究反复“证据不足”修复验收

**结果：已修复并重启服务。原题切换为网页来源后，真实页面显示“研究完成”。**

- **原因**：网页正文混入大量导航；核验时重复输出长引文导致截断；带文档路径的 `site:` 查询被拒绝却显示为空结果；规划器重复读取已经获取的页面。题目写“根据知识库”但只选网页，也会造成来源冲突。
- **修复**：优先提取正文、保留代码与限制条件；模型选择原文段落编号，由程序还原并校验原文及哈希；支持 `site:域名/路径`，保留真实搜索错误且空结果不算证据；向规划器明确已读资料与剩余目标，并阻止重复读取。明确的来源配置冲突在创建研究前用中文提示。
- **真实验收**：选择“自主研究（候选）＋网页”，未启用知识库。1 次网页搜索、1 次原文读取、1 次核验；2 条论断有支持，引用 1 个 Spring Boot 官方网页，未完成目标为 0。答案包含中文解释、生活类比、Java 示例和练习。示例标注为未实际运行。
- **核验开销**：本次核验输出 621 tokens；失败对照核验曾达到 4096 tokens 上限而被截断。仍保留原文、引用、适用范围和模型回执校验。
- **回归测试**：146 项 Python 测试、43 项 Java 测试通过，覆盖引用还原与重放、防篡改、来源限制、空结果、网页正文、重复读取及过期资料可刷新。
- **服务**：前端 5173、后端 8080、研究服务 8091 均已就绪。旧的失败报告保留，新建研究使用修复后的流程。

## 可直接重试的问题

> 请根据 Spring Boot 官方文档，用中文解释 Bean 和构造器依赖注入。先举生活例子，再给最小代码示例，最后出一道练习，并注明来源。

网页模式使用上面的表述；若写“根据知识库中的资料”，需选择知识库或混合来源。

## 验收证据

- 成功运行：`wf-392dc2a4-0257-4fa4-8234-f7710c303f92`，状态 `SUCCEEDED`，核验状态 `COMPLETED`。
- 原始失败对照：`wf-b37544a8-c8ab-4c1f-bc95-dd2aabeaf05d`。
- [官网来源](https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html)
- [运行记录、回执、测试结果和源码哈希](../../.codex-handoffs/web-evidence-reliability-20261007/acceptance-evidence.json)
- [实际报告全文](../../.codex-handoffs/web-evidence-reliability-20261007/actual-report.txt)
- [网页完成截图](../../.codex-handoffs/web-evidence-reliability-20261007/web-success-report.jpg)
- [代码示例截图](../../.codex-handoffs/web-evidence-reliability-20261007/web-success-example.jpg)
- [官方来源检查截图](../../.codex-handoffs/web-evidence-reliability-20261007/web-success-source.jpg)

本次修复的验收范围是上述真实网页问题及相关回归测试；来源确实缺失或不支持结论时，系统仍应保留缺口。页面现有的多段报告排版与代码配色未在本次修改。

# 第 1 轮第二次证据修复

基线：`292c6d83b453ff3f387b20ac6fa7bc3d27cca2b2`。统一问题编号来自 `ROUND1_RECHECK_2026-09-29.md`。

## N4：范围与反证连续性

条件解析分别记录 MISSING、DECLARED、AMBIGUOUS。没有条件、唯一明确条件以及互相不同/无效的声明具有不同结果；歧义不会退回“没有声明”而触发自动排除。该分类写入读取 metadata，Evidence 的冻结 v0 结构不变。

移除仅靠同文档定位、版本/时间相同和原引文重现的跨快照自动澄清。现有生产原文回执不包含可核验的修订/上下文绑定，不能确认一个快照的条件适用于另一份材料；observedAt 仅是抓取时间。较旧缓存、较晚重新抓取和完全相同材料补查均不能消除已经适用的反证关系。

合法的有限范围仍可成立：单份原文自身唯一声明 `mode=legacy`，请求为 `mode=general` 时，该份原文可以作为不同范围的资料保留。自由文本不同不证明互斥。旧不足可由真正适用的新原文补足；旧支持/反证、父检查、两轮限制、容量失败和不可变历史仍保留。真实跨快照修订消歧当前未接通，普通同范围矛盾保留 contested。

维护回归覆盖旧单一条件缓存 + 新多条件快照、原样重复核查、仅 observedAt 更晚、无修订绑定的同 URL、原文自身明确有限范围，以及既有反证保留与有效新材料补足。

## N3：完整报告的原文复核

整份 run 报告不再沿用单次核查的四份 KB 材料上限。两个调查分别使用 2+2 或 2+3 原文时，报告逐份调用 A 的 publicationRead 许可，再读取 managed 原文、核对原搜索回执/候选/快照并结算；按 Evidence ID 去重同次复核。正文、总来源和报告字节容量仍执行原限制。

这没有提高全局 16 工具/16 模型/180 秒上限。A 的实际 SQL authority 决定剩余额度；预算、权限或快照校验失败时不批准完整成功、不删调查或只发布支持子集。已完成读取计入预算，核查历史仍存在。预算不足时的运行终态由 A 处理，不能把 B 的拒绝描述为已封存了部分报告。

## N2：消费独立完成证明

采用 A 的精确接口约定 `685211b0200aafe1381985a96b8fcd973240e435`：ReportGoal 含 completionVerified、criteria 和 gaps；ReportCriterion 含稳定 criterionId、原文标准、resolved/uncovered/blocked/stale、checkIds/claimIds 和缺口。

只有 done、完成证明已独立验证、存在标准、所有标准 resolved 且无目标/标准 gap 的目标才不进入 unfinished_goals。每个未覆盖、受阻或过期标准及目标 gap 都进入可读正文；报告同时返回完整 goals 数据。旧三参构造器默认 false 和 legacy gap，空证明或一个 done 字符串不能完成目标。合法 refuted 可以满足核查标准。

A 拥有原生标准、活动核查映射、依赖快照和真实 SQL 结算的生产复核；B 消费这个受认证服务端端口，不接收模型/body 自报标准完成。authority fixture 的显式 verified 目标只用于隔离 B 的报告规则，不能替代生产适配证明。语义上“某条 Claim 是否适合某项标准”仍有规划模型边界，结构覆盖不等于通用事实真值。

## 版本、测试和限制

B 不新增迁移，不改 V17–V20 或冻结 contracts/agent/v0；A 的 V21 属于独立运行侧交付。维护用例包含条件解析、真实服务/临时 PG 的争议与范围、2+2/2+3 报告、预算拒绝保留历史，以及未覆盖/过期/legacy/完整标准报告。

精确 final/tested/peer SHA、验证命令、结果计数和实际 HTTP/JWT 联合边界见本地交接 `/Users/zwy/Claude/Projects/.codex-handoffs/deepresearch-agent-round1-repair2-evidence.md`。未验收真实模型、公网/TLS、现有 RAGFlow 或生产受控实验；没有升级正式服务、推送、发布或启动研究记忆/多 Agent。

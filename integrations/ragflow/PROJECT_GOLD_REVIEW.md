# Project retrieval gold review candidates

The canonical corpus is the eight sanitized Markdown files imported by
`scripts/import-project-kb.sh`. On the isolated RAGFlow route (129 chunks,
`topK=5`, threshold 0.22), all 25 current positive anchors appeared in a
returned preview, while all four safe-denial prompts returned evidence. These
figures are **candidate-label diagnostics**, not reviewed relevance judgments.

Several current labels match generic passages. The following replacements
were checked against the source documents and the RAGFlow preview for each
question. They still need comparison with the legacy previews before updating
`project_cases.json`.

| Case | Current anchor | Distinctive candidate |
| --- | --- | --- |
| 001 | `checkpoint` | `Python/LangGraph 是私网图执行面` |
| 003 | `finalize` | “数据库触发器拒绝它直接写 \`SUCCEEDED\` 等终态” |
| 005 | `grant` | `每个 Worker 只能绑定一个 scope，且必须同时属于 Java 持久 grant` |
| 009 | `durability=sync` | `不能把第三方模型调用或远端工具动作纳入同一个数据库事务` |
| 012 | `heartbeat` | `默认 lease 为 30 秒，heartbeat 为 10 秒` |
| 016 | `event_id` | “SSE 的 \`Last-Event-ID\` 值采用 \`<runId>:<eventId>\`” |
| 018 | `demo.html` | “在当前页面生命周期内用事件 ID 的 \`Map\` 去重” |
| 019 | `call_id` | “由 \`run_id\`、\`task_id\`、tool 和 query 的规范 JSON 做 SHA-256 后生成” |
| 022 | `kill/restart` | `没有完成真实外部模型参与的进程 kill/restart 全链验收` |
| 023 | `Temporal` | `当前项目没有实现 HITL interrupt、Kafka、Temporal 或完整 Kubernetes 集群` |
| 024 | `source ID` | `引用契约不能自动证明来源权威性，也不能自动证明事实真实性` |

Case 019 asks for both ID generation and the limit on idempotency. The
generation fields occur at rank 4 on RAGFlow, while the primary source's
statement that a deterministic ID only correlates calls and requires
downstream persistence/consumption for deduplication was absent from its top
five previews. A single anchor therefore overstates coverage. The other
single-anchor positives likewise make Recall@K equal HitRate@K.

The four negative prompts in `project-knowledge-gold.jsonl` accept an
evidence-backed safe denial. The current retrieval-only gate requires zero
entries. A correct boundary passage can fail that gate; the answer-level
negative contract and retrieval-only gate need an explicit shared definition
before `goldLabelsReviewed` can be set to `true`.

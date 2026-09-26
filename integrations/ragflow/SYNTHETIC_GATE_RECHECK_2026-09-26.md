# Synthetic fixture gate recheck — 2026-09-26

The [paired capture](synthetic_paired_capture_2026-09-26.json),
[score](synthetic_paired_score_2026-09-26.json), and
[conditions](synthetic_paired_conditions_2026-09-26.json) record a new live
evaluation of the unchanged SHA-256 `3f142361f5f03c6bd1101643cb45d544dca090eda10632706c6383a368288202`
[synthetic fixture](synthetic_knowledge.md). The fixture was the **only**
document in either retrieval corpus. RAGFlow used the existing empty,
dedicated synthetic-evaluation dataset, so the eight-document project corpus
and its [case-closure capture](project_paired_capture_case_closure_2026-09-26.json)
were not altered.

Both routes ran on the same disposable PostgreSQL registry, with separate
legacy Elasticsearch and RAGFlow indexes. Dify stayed running. To fit local
Docker memory, RAGFlow was paused during the legacy phase; legacy
Elasticsearch and the reranker were removed before RAGFlow resumed. A first
legacy probe was discarded because the reranker was still starting. The
committed capture was restarted from question one after the reranker returned
successful `/rerank` responses. Each of 28 questions had one warmup and three
measured calls per route.

| Check | Legacy | RAGFlow |
|---|---:|---:|
| Fixture parse status | DONE, 26 chunks | DONE, 7 remote chunks |
| Positive anchor hit, every sample | 25/25 | 25/25 |
| Zero citable evidence, every sample | 0/3 | 3/3 |
| Retrieval p95, 84 measured calls | 1271.474 ms | 424.202 ms |
| Ranking unstable cases | 0 | 0 |

All seven distinct displayed RAGFlow chunks passed direct dataset/document/
chunk API backchecks. The legacy route returned five chunks on every sample
of all three no-evidence questions. Its current vector search supplies nearest
neighbors without a no-answer cutoff, so the strict zero-evidence contract
fails. The RAGFlow route returned an empty evidence list for all nine negative
samples. No project facts were used to make either result pass.

The current scorer reports `fixtureParsedBoth=true`, `citationVerified=true`,
`p95Within1_5x=true`, and `stableRanking=true`. It reports
`negativeContractsSatisfied=false` and `zeroEvidenceNegativesSatisfied=false`
because those contracts require **both** routes to return no citable evidence.
Project-only gates are false by design for this synthetic manifest. Read this
capture alongside the clean [project case-closure report](PROJECT_CASE_CLOSURE_2026-09-26.md):
the fixture parsing gap is closed, while the combined data-plane decision
remains blocked by legacy zero-evidence behavior. This failure is not converted
to a safe denial or hidden by the project safe-denial score. The default
retrieval provider remains `legacy`.

Reproduce the score:

```sh
python3 integrations/ragflow/paired_eval.py score \
  --capture integrations/ragflow/synthetic_paired_capture_2026-09-26.json \
  --output /tmp/synthetic-gate-score.json
```

The dedicated RAGFlow synthetic document is retained to permit direct
citation backchecks. The disposable Java/PostgreSQL resources can be removed
without affecting the project dataset. Captures contain no API key or dev
token. Passing this strict legacy negative contract requires a separately
validated no-evidence cutoff; choosing a score threshold from these three
known questions alone would overfit the gate.

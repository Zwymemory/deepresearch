# Synthetic retrieval paired evaluation — 2026-09-22

The local collector completed the 28 synthetic questions against both Java
retrieval routes: 25 positive cases and three no-evidence cases. Each
route-question pair had one warmup and three measured calls. This is a parser
and retrieval diagnostic, not the project-knowledge acceptance run. The
application default remains `legacy`; **this result does not authorize a
cutover**.

## Corpus and run conditions

The fixture was uploaded through Java under the distinct filename
`synthetic_knowledge_pair_20260922.md`. Its bytes and SHA-256
(`3f142361f5f03c6bd1101643cb45d544dca090eda10632706c6383a368288202`)
match the committed `synthetic_knowledge.md`. Both routes also contained the
same eight sanitized project documents. The [conditions](synthetic_paired_conditions_2026-09-22.json)
record their hashes, isolated data stores, route settings and test window.

Both fixture uploads reached `DONE`. The legacy ingestion response recorded
26 chunks. The RAGFlow ingestion response did not include a chunk count;
seven distinct chunks from its synthetic document appeared in the capture and
all seven passed direct chunk API checks. This establishes at least seven
parsed, retrievable chunks, not an exhaustive remote chunk count. The project
documents accounted for 57 legacy chunks and 129 RAGFlow chunks before the
fixture was added. The legacy route used Zhipu `embedding-2` and
`BAAI/bge-reranker-base`. RAGFlow used a 128-token naive parser and similarity
threshold 0.22. Both routes used `topK=5` on a host with 7.75 GiB allocated
to Docker.

## Paired results

| Measure | Legacy | RAGFlow |
| --- | ---: | ---: |
| Synthetic positive anchor hit at 5 | 25/25 | 25/25 |
| MRR at 5 | 0.9800 | 0.9700 |
| nDCG at 5 | 0.9852 | 0.9772 |
| Empty retrieval on no-evidence cases | 0/3 | 3/3 |
| Retrieval p95, 84 measured calls | 304.158 ms | 1,196.707 ms |
| Cases with changing relevance rank | 0 | 0 |

All three legacy negative queries returned five entries on every measured
call. All three RAGFlow negative queries returned no entries on every
measured call. RAGFlow p95 was 3.934 times the legacy p95, exceeding the
harness's 1.5-times threshold for this synthetic workload. There were no new
RAGFlow positive anchor misses. The collector resolved all 35 distinct
RAGFlow source IDs it checked against the direct chunk API: seven from the
synthetic document and 28 from the project documents. These checks establish
identifier resolvability, not answer correctness or source authority. The
collector did not call the answer model.

The [completed project-corpus run](PROJECT_PAIRED_RESULT_2026-09-22.md) had
different p95 values: 1,274.302 ms for legacy and 1,456.404 ms for RAGFlow,
a 1.143 ratio across 87 measured calls per route. The synthetic run included
the extra fixture and different questions; its 3.934 ratio cannot replace
the project-corpus latency result. Neither workload supports a cutover by
itself.

## Gate and cleanup

The scored [synthetic report](synthetic_paired_score_2026-09-22.json) has
`readyToSwitch=false`. Its `negativeNoEvidence` and `p95Within1_5x` gates
failed. It also has no reviewed project Gold labels or declared project
mapping IDs, so the project-specific gates are false by design. Both fixture
parse statuses and all attempted citation backchecks passed. The 0.22
threshold was selected after inspecting the synthetic questions in an earlier
smoke run, so these negative results are not an independent threshold
validation.

After the run, the distinct-name fixture was deleted from both Java routes.
The project corpus was restored to eight documents and 57 legacy chunks, and
eight documents and 129 RAGFlow chunks. The earlier smoke document in the
separate synthetic dataset was removed separately after the project route's
dataset allowlist blocked Java deletion. The collector artifacts reflect the
temporary fixture while it was indexed; the cleanup was checked outside the
collector.

## Artifacts

- [Raw paired capture](synthetic_paired_capture_2026-09-22.json) contains all
  56 route-question runs, 168 measured retrieval calls and the citation
  backchecks.
- [Score](synthetic_paired_score_2026-09-22.json) contains the metrics,
  per-case results and gate values.
- [Conditions](synthetic_paired_conditions_2026-09-22.json),
  [case manifest](synthetic_paired_cases_2026-09-22.json) and the
  [distinct-name fixture](synthetic_knowledge_pair_20260922.md) preserve
  the run inputs. The fixture has the same content hash as
  `synthetic_knowledge.md`.

The JSON artifacts contain local route addresses, document and chunk IDs,
questions, and previews of the sanitized corpus. Before commit, they were
checked against configured local API credentials and saved development
tokens, JWT-shaped strings and Bearer headers; none were found. SHA-256 hashes
for the committed bytes are:

| Artifact | SHA-256 |
| --- | --- |
| Raw capture | `b04adc15a7452c1728b253eae8d29fb7185c99986a6dd2b831b802557b8c697c` |
| Score | `c4f7469f154042b08c97e74dc2b06cdfda479a8003cac1aceed6bc0b23f83b99` |
| Conditions | `f09ec5ab9ac2e305b82bd2536298dd8153f93e6a06b4343760f65e71560e87dc` |
| Case manifest | `1b72a83951a824d877f9ca8784602120792eb85ee48aa12d14cefa2e509d73b7` |
| Distinct-name fixture | `3f142361f5f03c6bd1101643cb45d544dca090eda10632706c6383a368288202` |

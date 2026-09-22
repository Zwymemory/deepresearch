# Project knowledge retrieval: completed paired run — 2026-09-22

The local paired collector completed all 29 project questions against both
`legacy` and `ragflow`: 25 candidate positive cases and four safe-denial cases,
with one warmup and three measured repetitions per route and question. The
capture contains 58 route-question runs and 87 measured latency samples per
route. **This result does not authorize a cutover.** The application default
remains `legacy`.

## Conditions and results

Both routes indexed the same eight sanitized files through
`scripts/import-project-kb.sh`. The [conditions](project_eval_conditions_2026-09-22.json)
record their hashes, isolated data volumes, route settings and test window.
The legacy index had 57 chunks and used Zhipu `embedding-2` with
`BAAI/bge-reranker-base`. The dedicated RAGFlow dataset had 129 chunks with a
128-token naive parser and similarity threshold 0.22. Both routes used
`topK=5`. These are local measurements on a host with 7.75 GiB allocated to
Docker.

| Measure | Legacy | RAGFlow |
| --- | ---: | ---: |
| Candidate positive anchor hit at 5 | 22/25 (88%) | 25/25 (100%) |
| MRR at 5 | 0.88 | 0.9733 |
| nDCG at 5 | 0.88 | 0.98 |
| Empty retrieval on safe-denial cases | 0/4 | 0/4 |
| Retrieval p95, 87 measured calls | 1,274.302 ms | 1,456.404 ms |
| Cases with changing top-five order | 0 | 1 |

RAGFlow p95 was 1.143 times the legacy p95, within the harness's 1.5-times
latency gate for this run. The three legacy anchor misses were cases 005,
006 and 009; RAGFlow hit their current anchors. There were no new RAGFlow
anchor misses. Case 020 returned a different top-five order in one of its
three RAGFlow repetitions: the first two sources swapped and the rest of the
set stayed the same. The harness consequently marked `stableRanking=false`.

All eight project document mappings were `DONE`. The paired run verified all
78 distinct RAGFlow source IDs it checked against the RAGFlow chunk API
(78/78). This establishes resolvability of those IDs, not source authority
or answer correctness.

## What the scores do not establish

The positive labels have not been manually approved (`goldLabelsReviewed=false`).
Each current positive has only one anchor, some of which are broad; the
reported Recall@5 is therefore the same as anchor HitRate@5 and does not
measure whether all facts needed for an answer were retrieved. The
[gold review](PROJECT_GOLD_REVIEW.md) lists candidate refinements.

Case 019 shows the gap directly. Its `call_id` anchor hit at rank 1 on both
routes. A subsequent fact-coverage score requires both the fields used to
generate the ID and the limitation that downstream persistence or consumption
is needed for deduplication. The generation fact appeared at legacy rank 1
and RAGFlow rank 4; the limitation was absent from both top-five previews.
Thus `answerableAtK=false` for both routes on the only case with defined
`requiredFacts`. The fact-coverage report records 0/1 fully covered for each
route. The remaining 24 positive cases have no `requiredFacts` yet and cannot
be interpreted as fully answerable from this score.

Both routes returned five entries for each of the four safe-denial questions
in each repetition, so the retrieval-only `negativeNoEvidence` gate failed.
The project Gold permits an evidence-backed safe denial. The two contracts
must be reconciled before these negatives can be used for a release decision;
the [answer-level smoke note](PROJECT_NEGATIVE_ANSWER_SMOKE_2026-09-22.md)
is separate from this retrieval score.

No synthetic fixture was ingested during this clean project-corpus run:
`ingestion=null`, `parseCompletionRate=null`, and `fixtureParsedBoth=false`.
Synthetic ingestion and parse checks are recorded in the separate
[live smoke report](LIVE_SMOKE_2026-09-22.md). The scored project run also
failed `goldLabelsReviewed`, `negativeNoEvidence`, and `stableRanking`.
Accordingly, both score files report `readyToSwitch=false`.

## Artifact provenance

- [Raw paired capture](project_paired_capture_2026-09-22.json) is the original
  collector output. Its embedded manifest has the same 29 case IDs and
  questions as the current project manifest, but was captured before
  `requiredFacts` was added to case 019.
- [Original score](project_paired_score_2026-09-22.json) scores the embedded
  capture manifest and preserves the first result.
- [Fact-coverage score](project_paired_score_fact_coverage_2026-09-22.json)
  was derived later by replacing only the embedded manifest with the current
  one and running the scorer against the same captured responses. It did not
  issue new retrieval requests. The added facts belong only to case 019;
  case IDs and questions are unchanged. The derived JSON exactly matches
  the current `paired_eval.score` output for that substitution.

The artifacts contain local route addresses, document and chunk IDs,
questions, and previews of the sanitized knowledge corpus. Before commit,
they were checked for exact matches to configured local API credentials and
saved development tokens, JWT-shaped values, and Bearer headers; none were
found. SHA-256 hashes for the committed bytes are:

| Artifact | SHA-256 |
| --- | --- |
| Raw capture | `3270d132bc368a7c6c43cf3b9e5f0d864c86e8b5e306c0217f0083debe0392e1` |
| Original score | `d68c46392abc9e7abf9b5979a217e4d29a99e437eb61a0040b79b60503b4fda8` |
| Fact-coverage score | `fc159a54990a7277ec742b4dd2b7d39773e883d9be1df1ae49079cd7e1b44674` |

The [earlier attempt log](PROJECT_PAIRED_ATTEMPT_2026-09-22.md) describes
two incomplete collections before this successful run.

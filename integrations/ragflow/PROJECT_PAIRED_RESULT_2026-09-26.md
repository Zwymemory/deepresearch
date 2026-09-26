# Project knowledge retrieval: live time-sliced paired run — 2026-09-26

The live collector completed all 29 reviewed project cases against both
`legacy` and `ragflow`: 25 positives and four evidence-backed safe-denial
cases. Each route had one warmup and three measured retrieval repetitions per
case, for 87 latency samples per route. Safe-denial answer calls were captured
and scored separately; their generation latency is excluded from retrieval
p95. **`readyToSwitch=false`, and the application default remains `legacy`.**

## Conditions

Both routes used the same eight canonical project documents and `topK=5` on a
macOS arm64 host with 7.75 GiB assigned to Docker. The isolated legacy index
contained 57 chunks. The RAGFlow project dataset contained 129 chunks and used
query expansion off, similarity threshold 0.22, `knn_top_k=32`,
`knn_num_candidates=128`, and 20 rerank candidates. Query rewrite was off on
both Java routes.

The first attempt to run all benchmark dependencies concurrently caused the
RAGFlow Elasticsearch container to restart under memory pressure. For the
successful capture, all RAGFlow containers were paused during the legacy route;
the temporary legacy PostgreSQL, Elasticsearch, reranker, and Java process were
then stopped before RAGFlow was resumed. Dify remained running throughout.

All eight `kb_ragflow_document` mappings were `DONE`. This project run did not
perform a paired fixture ingestion: `ingestion=null` and
`parseCompletionRate=null`. Mapping completion therefore passes while the
separate `fixtureParsedBoth` gate remains false.

## Paired quality result

| Measure, every measured repetition | Legacy | RAGFlow |
| --- | ---: | ---: |
| Reviewed positive ranking-anchor hits | 23/25 (92%) | 25/25 (100%) |
| Fully answerable positive cases | 21/25 (84%) | 24/25 (96%) |
| Atomic required facts in the first/equivalent stable result | 66/74 | 72/74 |
| Atomic required facts across all three repetitions | 198/222 | 216/222 |
| MRR@5 | 0.9200 | 0.9233 |
| nDCG@5 | 0.9200 | 0.9425 |
| Complete evidence-backed safe denials | 3/4 (75%) | 3/4 (75%) |
| Retrieval p95 | 2,705.123 ms | 436.250 ms |
| Cases with ranking or fact-coverage instability | 0 | 0 |

Legacy missed the reviewed ranking anchors for cases 005 and 006. Its complete
fact failures were 005, 006, 012, and 019. RAGFlow had no ranking-anchor miss
and no new miss relative to legacy.

All four answer-text subcontracts passed on both routes in every repetition:
the answers contained an allowed refusal, contained no forbidden assertion,
and included a valid source marker. The complete safe-denial result is still
3/4 because `project-kb-neg-001` did not retrieve its required boundary
evidence on either route. The scorer correctly keeps the combined contract
failed instead of treating a well-phrased answer as sufficient evidence.

### Case 019

Both routes retrieved the call-ID inputs and stable retry identifier, but both
missed two required limitations: the deterministic identifier is only a
correlation key, and downstream code must persist or consume it to enforce
deduplication. Legacy placed the primary anchor at rank 1 in all repetitions;
RAGFlow placed it at rank 4 in all repetitions. Both routes remain incomplete,
and `ragflowRequiredFactsEverySample=false` is preserved.

### Case 020

All three receipt outcomes were present in every repetition on both routes.
The reviewed primary anchor was rank 1 in all three RAGFlow repetitions, so the
previous ranking instability did not reproduce. `stableRanking=true` in this
run.

## Citation and mapping checks

The collector attempted 84 RAGFlow source-ID backchecks and verified all 84
against the RAGFlow chunk API. All eight declared project document mappings
were `DONE`. These checks prove identifier resolvability and mapping state;
they do not establish source authority or factual truth.

## Latency stages and optimization evidence

The paired RAGFlow measurements expose six fixed stage names. Legacy does not
emit stage timings.

| RAGFlow stage, 87 project samples | Mean | p95 |
| --- | ---: | ---: |
| `queryRewrite` | 0.000 ms | 0 ms |
| `registry` | 0.345 ms | 2 ms |
| `upstreamApi` | 353.667 ms | 428 ms |
| `evidenceNormalization` | 0.149 ms | 1 ms |
| `responseAssembly` | 0.000 ms | 0 ms |
| `total` | 355.483 ms | 430 ms |

The client-observed RAGFlow p95 was 436.250 ms. The difference from the
430 ms internal `total` stage is local HTTP and serialization overhead. The
upstream RAGFlow API dominates the measured time.

The compact [performance profile](PERFORMANCE_PROFILE_2026-09-26.json)
preserves configuration, aggregate latency, stage summaries, quality counts,
failure IDs, and source hashes without copying per-sample previews or answers.
The main evidence is:

| Profile | p95 | Quality result |
| --- | ---: | --- |
| Historical synthetic RAGFlow paired run | 1,196.707 ms | 25/25 positives; 3/3 zero-evidence negatives |
| Direct RAGFlow, query expansion on, `256/2048/64` | 1,286.856 ms | 25/25 positives; 3/3 negatives |
| Direct RAGFlow, query expansion off, `256/2048/64` | 403.709 ms | 25/25 positives; 3/3 negatives |
| Java RAGFlow, query expansion off, `256/2048/64` | 466.728 ms | 25/25 positives; 2/3 negatives (`n01` failed) |
| Java RAGFlow, query expansion off, `32/128/20` | 374.769 ms | 25/25 positives; 3/3 negatives |
| Java RAGFlow project, `32/128/20` | 438.638 ms | anchors 25/25; complete facts 24/25; denial evidence 3/4 |

The historical synthetic legacy p95 was 304.158 ms, so its 1.5-times budget is
456.237 ms. The selected `32/128/20` Java synthetic profile is below that
budget. Its internal stage p95 values were 370 ms for `upstreamApi` and 371 ms
for `total`. The project Java profile recorded 432 ms and 434 ms respectively.
Disabling RAGFlow's LLM-backed query expansion produced the main reduction;
the smaller candidate pools added headroom while preserving the reviewed
quality counts in the synthetic and project profiles.

## Gate outcome

The following score gates are false:

- `ragflowRequiredFactsEverySample`: case 019 lacks two required limitations.
- `negativeContractsSatisfied`: safe-denial case 001 lacks boundary evidence.
- `safeDenialsSatisfied`: the same case prevents an all-case pass.
- `fixtureParsedBoth`: this clean project-corpus run did not ingest the paired
  synthetic fixture.

Every other recorded project gate passes, including reviewed labels, both-path
positive hits, relative misses, critical misses, citation checks, latency,
project mappings, and ranking stability. The remaining false gates keep
`readyToSwitch=false`. The configured provider default is still
`DEEPRESEARCH_RETRIEVAL_PROVIDER:legacy`.

## Manifest calibration and reproducibility

The raw capture retains its acquisition-time manifest and records manifest
SHA-256 `e74e506bef24fa6b5edd250463453fefa56268ce7d46c8774fd129b88b3fe0e4`
in the collection conditions. The final score was reproduced byte-for-byte by
scoring that capture with the current reviewed `project_cases.json`, whose file
SHA-256 is `d6b7d698575b5449c8831411af95b766d3ab8c4600b387768511d09726c2ce18`.

During finalization, the manifest changes were limited to safe-equivalent
refusal wording and stronger forbidden-assertion calibration for negatives 003
and 004. The capture already contains part of that calibration. No case
question, positive/negative classification, ranking anchor, required fact,
denial-evidence label, or canonical source hash changed. The score therefore
reuses the captured retrieval results and changes no retrieval label.

Reproduction command:

```sh
python3 integrations/ragflow/paired_eval.py score \
  --capture integrations/ragflow/project_paired_capture_2026-09-26.json \
  --manifest integrations/ragflow/project_cases.json \
  --output /tmp/project_paired_score_reproduced.json
```

## Artifact provenance

| Artifact | SHA-256 |
| --- | --- |
| [Raw paired capture](project_paired_capture_2026-09-26.json) | `d2fb711153949e75c18585c2c0e37cdd95c73eb6b172a0611d31c1b3eadd18d6` |
| [Reviewed score](project_paired_score_2026-09-26.json) | `642737bf9620cca65087afb4509d4c6ae300c536bb2de11cdabb2d618262609a` |
| [Compact performance profile](PERFORMANCE_PROFILE_2026-09-26.json) | `eb7376d2767c0581184cfdcd1ee6dbd6a0aa8d8e26bc11c5c8b1bfc77073d1cb` |

## Sensitive-value scan

The four committed artifacts were scanned against the exact configured
RAGFlow and Zhipu credentials and both saved benchmark tokens: no match was
found. Bearer-header, JWT-shaped, common secret-prefix, and credential-assignment
pattern scans also found no match. No credential value was printed during the
scan.

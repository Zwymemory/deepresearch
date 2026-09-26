# Project retrieval contract and offline rescoring — 2026-09-26

This report applies the reviewed logical-label contract in
`project_cases_baseline_2026-09-26.json` to the unchanged 2026-09-22 project capture. It is an
offline rescore: no endpoint was called and no answer sample was invented.
The result does **not** support changing the default retrieval provider.

## Inputs and provenance

- Reviewed contract SHA-256: `d6b7d698575b5449c8831411af95b766d3ab8c4600b387768511d09726c2ce18`
- Original capture SHA-256: `3270d132bc368a7c6c43cf3b9e5f0d864c86e8b5e306c0217f0083debe0392e1`
- Rescored result SHA-256: `a7a67bbc576e6622a04433d68b209a9a0bc4218eea6ea5ecd4ec8623051ea707`
- Canonical corpus: the eight files and hashes recorded under `review.canonicalSources`
  in `project_cases_baseline_2026-09-26.json`; they exactly match the capture's corpus conditions.
- Review date: 2026-09-26. `goldLabelsReviewed=true` now means the logical
  labels and source hashes were reviewed. It does not imply that retrieval,
  answerability, latency, or safe-denial gates passed.
- The final contract also accepts equivalent explicit refusal wording observed
  in the later live run and rejects additional assertive metric/deployment
  phrases. No retrieval label or canonical source changed; the old capture has
  no answer samples, so its machine score is byte-for-byte unchanged.

Reproduction command:

```sh
python3 integrations/ragflow/paired_eval.py score \
  --capture integrations/ragflow/project_paired_capture_2026-09-22.json \
  --manifest integrations/ragflow/project_cases_baseline_2026-09-26.json \
  --output integrations/ragflow/project_paired_score_contract_2026-09-26.json
```

## Contract

- A ranking anchor is a logical label with one or more equivalent phrases.
  It matches when any alternative occurs in a top-5 preview after Unicode
  NFKC normalization, Markdown backtick removal, and whitespace collapse.
  Matching remains case-sensitive.
- A positive case is answerable only when every `requiredFacts` label is
  present. Anchor hit and answerability are reported separately for the first
  repetition and across every repetition.
- The four project negatives are evidence-backed safe-denial cases. Each
  repetition must retrieve its declared boundary evidence, produce an answer
  containing an allowed denial phrase and no forbidden phrase, and contain a
  valid `[来源N]` marker backed by the returned `sources` array.
- Synthetic negatives retain their separate zero-evidence contract: both
  routes must return no citable entry. The project safe-denial rule does not
  weaken that requirement.

## Offline result

| Metric | Legacy | RAGFlow |
| --- | ---: | ---: |
| First-sample logical anchor hit rate | 23/25 (92%) | 25/25 (100%) |
| Every-sample logical anchor hit rate | 23/25 (92%) | 25/25 (100%) |
| First-sample all-facts coverage | 21/25 (84%) | 24/25 (96%) |
| Every-sample all-facts coverage | 21/25 (84%) | 24/25 (96%) |
| Retrieval p95 | 1274.302 ms | 1456.404 ms |
| Citation backchecks from the schema-v1 capture |  | 78/78 |

The project latency ratio still passes the 1.5× threshold. This old capture
does not contain `stageTimingMs`; stage aggregation is therefore empty. New
schema-v2 captures preserve the six allowlisted stages and keep answer-model
latency separate from retrieval p95.

### Required failures preserved

- **Case 019:** both routes retrieve the call ID derivation and stable retry
  identity. Neither top-5 result contains the facts that a deterministic ID is
  only an association key and that deduplication requires downstream durable
  consumption. Legacy and RAGFlow therefore both fail complete answerability.
- **Case 020:** all three required receipt outcomes are present in every
  repetition. RAGFlow's reviewed ranking anchor changes position in one of the
  three repetitions, so `stableRanking=false`; coverage remains complete.
- **Safe-denial cases:** the old retrieval-only capture has no `/hybrid`
  answer samples. All four cases fail closed as unverified on both routes.
  Project negative 001 also lacks its declared boundary-evidence phrase in the
  saved top-5 previews. No manual smoke-test judgment was copied into the
  machine score.
- RAGFlow also changes the denial-evidence rank for project negative 002. The
  report exposes this second instability instead of reducing it to the case
  020 observation.

Other legacy gaps are retained in the JSON report: cases 005 and 006 miss the
reviewed ranking anchor, and cases 005, 006, and 012 lack complete required
facts. These do not turn RAGFlow misses into passes; they remain baseline
diagnostics.

## Gate outcome

`readyToSwitch=false`. The reviewed-label, relative miss, citation, project
mapping, project latency, and critical-new-miss gates pass. The following
gates remain false:

- `ragflowRequiredFactsEverySample` — case 019 is incomplete.
- `negativeContractsSatisfied` and `safeDenialsSatisfied` — the old capture
  contains no answer samples, and one denial-evidence retrieval is incomplete.
- `stableRanking` — case 020 and safe-denial case 002 have rank changes.
- `fixtureParsedBoth` — parsing was measured in the separate synthetic run,
  not in this clean project-corpus capture.

The next live paired project run must capture answer samples for all four
safe-denial cases. A later task may combine the separate synthetic parsing
evidence with project retrieval evidence, but this rescore intentionally does
not manufacture a passing aggregate gate.

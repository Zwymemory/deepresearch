# Project Gold case closure, 2026-09-26

This run closes the two source-evidence gaps in the 2026-09-26 project
evaluation: `project-kb-019` (tool call ID limits and downstream deduplication)
and `project-kb-neg-001` (the knowledge pack's contact-information boundary).
The [paired capture](project_paired_capture_case_closure_2026-09-26.json) and
[scored report](project_paired_score_case_closure_2026-09-26.json) preserve the
measured samples and the verdict.

## Changes and scoring contract

- The [knowledge-pack index](../../docs/kb-project/README.md) now puts its
  existing information-boundary sentence next to the title, so it survives
  RAGFlow chunking. The [tool-receipt fact card](../../docs/kb-project/05-tool-receipt-and-unknown-result.md)
  now puts the existing correlation-key/downstream-consumption limit in the
  same paragraph as the deterministic call ID inputs. No factual claim was
  invented or removed.
- The project Gold manifest keeps the 25 positive questions, 74 atomic
  required facts, four safe-denial cases, source hashes, and all forbidden
  assertions. It adds one surviving substring of the existing boundary
  sentence because Java redacts `Bearer Token` to `Bearer [REDACTED]` in
  displayed evidence. It also recognizes `不能据此提供` and
  `无法基于参考材料回答` as safe refusal wording after inspecting the captured
  answers and their valid citations. The original acceptable phrases remain.
- Collection used manifest SHA-256
  `b74894aaf35bc5931a4efcedf729e1dd47ee822a2ecaf528db7581b31704ef3d`.
  The final score applies the compatible reviewed manifest SHA-256
  `b276e82585c471377f1055b52883564ea2c20f3caa33848b3d31dbaee2aa6d96`
  to those unchanged samples. The only post-collection edits were the three
  phrase alternatives above. The pre-change historical manifest is preserved
  in [project_cases_baseline_2026-09-26.json](project_cases_baseline_2026-09-26.json).

## Paired live result

The same eight canonical documents were imported into a disposable legacy
database/index and registered against the existing isolated RAGFlow dataset.
Both routes used top five, one warmup, and three measured repetitions per
question. Dify remained running; RAGFlow was paused only during the legacy
phase to fit the local Docker memory limit. Retrieval p95 covers the Java
debug endpoint, excluding answer generation. The capture records the source
hashes, service settings, host, document mappings, and run conditions.

| Route | Positive anchors | Complete required facts | Safe denials, every sample | Retrieval p95 |
|---|---:|---:|---:|---:|
| Legacy | 23/25 | 22/25 | 4/4 | 2348.632 ms |
| RAGFlow | 25/25 | 25/25 | 4/4 | 414.202 ms |

`case019` contains all four required fact groups in the top five on both
routes for all three repetitions. `neg001` has boundary evidence and a cited
safe refusal on both routes for all three repetitions. Its RAGFlow boundary
evidence was rank 1; the initial label failed only because the displayed
`Bearer Token` span was redacted. All 86 distinct RAGFlow displayed citations
resolved to the recorded dataset, document, and chunk; all eight mappings
were `DONE`. No positive regression or ranking instability was observed.

Every automated project gate passed except `fixtureParsedBoth`. This clean
project-corpus run did not ingest the separate synthetic fixture, so
`readyToSwitch=false`. The earlier synthetic run established its three
zero-evidence negatives separately; this project capture does not claim to
repeat that parse gate. Dify end-to-end dispatch, cancellation, restart,
disconnect, and repeated-delivery semantics remain a separate task. The
default retrieval provider remains `legacy`.

## Reproduce the score

```sh
python3 integrations/ragflow/paired_eval.py score \
  --capture integrations/ragflow/project_paired_capture_case_closure_2026-09-26.json \
  --manifest integrations/ragflow/project_cases.json \
  --output /tmp/project-case-closure-score.json
```

The score output is deterministic from the committed capture and manifest.
The two newly parsed RAGFlow documents are retained in the isolated dataset
so their citation backchecks remain reproducible; an unused experimental
upload was removed. Credentials and dev tokens are absent from both artifacts.

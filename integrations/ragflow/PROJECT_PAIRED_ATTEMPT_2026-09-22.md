# Project retrieval paired evaluation attempts — 2026-09-22

The 29 project cases were prepared for an interleaved comparison of the local
`legacy` and `ragflow` Java routes. **Neither paired attempt completed.** There
is no comparable project score or latency p95, and this run does not support a
retrieval cutover. The committed default remains `legacy`.

## Prepared conditions

The [run conditions](project_eval_conditions_2026-09-22.json) record the eight
sanitized corpus file hashes, route URLs and settings, dataset and document
IDs, chunk counts, host memory allocation and test window. Both routes had
imported the same eight files through `scripts/import-project-kb.sh`: the
legacy index contained 57 chunks, and the dedicated RAGFlow project dataset
contained 129 parsed chunks. The legacy route used Zhipu `embedding-2` and
`BAAI/bge-reranker-base`; the RAGFlow route used the test dataset's 128-token
naive parser and similarity threshold 0.22. Credentials were held in ignored
local files and are absent from the conditions.

The project Gold has 25 positive and four safe-denial cases. Its current
positive anchors remain candidate labels, so `goldLabelsReviewed` is `false`.
The [label review note](PROJECT_GOLD_REVIEW.md) records specific labels that
need tightening and the mismatch between evidence-backed safe denial and the
retrieval gate's zero-evidence rule.

## Attempt outcomes

| Attempt | Observation | Result |
| --- | --- | --- |
| First interleaved collection | RAGFlow debug retrieval returned HTTP 500 while its Elasticsearch service was unavailable. | Collector stopped before a complete capture. |
| Second interleaved collection | After reducing the isolated DeepResearch Elasticsearch heap, the collector reached project case 017. RAGFlow debug retrieval again returned HTTP 500 during a RAGFlow Elasticsearch restart. | Collector stopped after 33 completed route-query calls; the next call failed. |

The second attempt's progress log recorded 34 route-query starts out of 58
planned first-pass calls. Warmup and three measured repetitions would require
further calls. The local Docker allocation was 7.75 GiB and also hosted
RAGFlow, Dify, two isolated DeepResearch data stores and the legacy reranker.
The RAGFlow Elasticsearch restarts make the two partial attempts unsuitable
for calculating comparable route metrics. We did not treat the partial calls
as a paired evaluation result.

An earlier RAGFlow-only diagnostic found a candidate anchor in the returned
preview for 25/25 project positives, while all four safe-denial prompts
returned evidence. Those observations are described in the
[label review note](PROJECT_GOLD_REVIEW.md); they are not paired scores and do
not resolve the negative-case contract.

## Remaining gate work

Run the full paired collector with both search services stable and record its
capture and scored report. Review distinctive positive labels against both
routes, settle whether project negatives are judged by answer-level safe
denial or empty retrieval, and complete the parse and citation checks required
by the retrieval gate. Until then, `readyToSwitch` is unestablished.

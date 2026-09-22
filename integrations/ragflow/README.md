# RAGFlow paired retrieval evaluation

`paired_eval.py` measures the existing Java retrieval pipeline and the opt-in RAGFlow pipeline through the same `POST /api/research/hybrid/debug` request. It does not call the answer model. The legacy ranking comes from `rerankResult`; RAGFlow ranking comes from `compressedContext`, which the RAGFlow debug adapter populates from its normalized evidence gateway. Keep `topK`, questions, corpus and host conditions identical for both runs.

## Inputs

- `synthetic_knowledge.md` is an invented, deterministic document. `synthetic_cases.json` has 25 answerable questions and 3 no-evidence questions. This is a parser/search smoke test; its scores cannot satisfy the project-knowledge gate.
- `project_cases.json` copies the 25 positive and 4 negative questions from `testdata/eval/project-knowledge-gold.jsonl`. The selected answer anchors are *candidate* snippet labels. Review each label against the actual corpus before setting `goldLabelsReviewed` to `true` in conditions. In particular, a common word appearing in an unrelated chunk must not count as relevance. Edit the labels to a unique exact phrase where needed.
- `conditions.example.json` is intentionally incomplete. Copy it into a private run directory and record the two Git/config versions, retrieval thresholds, dataset IDs (no API keys), corpus hashes/counts, machine, database snapshots, and wall-clock test window. For the project run, put **all** Java IDs of the synchronized project documents in `projectDocumentIds`. `goldLabelsReviewed` is only for a human-confirmed project gold set. Never put credentials in this file.

The gold match is an exact substring in the debug `preview`, not a title guess or cross-provider document ID match. A preview truncation can therefore create a false miss; inspect the saved per-case capture when this occurs. The two systems use different chunk IDs, so their keys must not be compared directly. For queries with several distinct relevant chunks, place one unique anchor per chunk in `relevantAnchors`; each anchor is one relevant item for Recall@K and NDCG@K.

## Run

Prepare **two Java instances or sequential runs over equivalent isolated database snapshots**, one with `DEEPRESEARCH_RETRIEVAL_PROVIDER=legacy`, one with `DEEPRESEARCH_RETRIEVAL_PROVIDER=ragflow`. The latter also needs `RAGFLOW_BASE_URL`, `RAGFLOW_API_KEY`, and `RAGFLOW_DATASET_IDS`. When Java runs in a container, `RAGFLOW_BASE_URL` must resolve from that container; `127.0.0.1:9380` only works for Java on the host. Both instances must contain the same relevant project documents. Keep the RAGFlow service at its existing `9380` port and do not remove its data volumes.

The harness needs Java credentials to upload the fixture (`ADMIN`) and call debug. It reads them from `EVAL_LEGACY_TOKEN` and `EVAL_RAGFLOW_TOKEN`. It reads the RAGFlow API key from `RAGFLOW_API_KEY` only for independent citation backchecks. No token is written to capture or report files.

```sh
cp integrations/ragflow/conditions.example.json /tmp/ragflow-eval-conditions.json
# Fill every null and review the gold labels before the project run.
python3 integrations/ragflow/paired_eval.py collect \
  --manifest integrations/ragflow/synthetic_cases.json \
  --conditions /tmp/ragflow-eval-conditions.json \
  --legacy-url http://127.0.0.1:8080 \
  --ragflow-url http://127.0.0.1:8081 \
  --ragflow-api-url http://127.0.0.1:9380 \
  --ingest-fixture --warmup 1 --repetitions 3 \
  --output /tmp/ragflow-synthetic-capture.json
python3 integrations/ragflow/paired_eval.py score \
  --capture /tmp/ragflow-synthetic-capture.json \
  --output /tmp/ragflow-synthetic-report.json
```

Repeat `collect` and `score` with `project_cases.json` and separate output names. The project run also uploads/polls the identical synthetic fixture to prove parse completion on both routes; the 25 project queries additionally require the project corpus to have been synchronized in advance. `projectDocumentIds` are checked through `GET /api/kb/documents/{docId}/ragflow-sync`, which reconciles the remote job and reports mapping status. A `kb_document` row marked DONE without a DONE RAGFlow mapping does not pass. Each route is warmed once per query, then measured three times. Route order alternates by query. The first measured ranking supplies the retrieval score; all measured calls supply latency samples, and `unstableCases` reports relevance-rank changes between repetitions. The p95 estimator is the nearest observed rank, `ceil(0.95 × n)`.

## Reading the report

The report includes per-case ranks, HitRate@K, per-query Recall@K, MRR@K, NDCG@K, negative no-evidence rate, parse completion rate, p95, new misses compared with legacy, critical identifier misses, and a record of every RAGFlow chunk API backcheck. A missing `RAGFLOW_API_KEY` or `--ragflow-api-url` makes citation verification unverified and prevents the gate from passing. A missing ingestion run similarly leaves parse completion unknown. `readyToSwitch` is an **automated gate result for the retrieval data plane only**; it does not authorize changing the default route or complete the Dify and end-to-end migration gates.

The migration plan's retrieval thresholds are encoded as gates: at least 25 reviewed project positives, at most one new miss relative to legacy, zero critical new misses, no evidence for negative questions on both paths, every displayed RAGFlow citation resolved to the same real dataset/document/chunk, and new p95 no more than 1.5 times legacy p95 on the same machine. The collector also checks each declared project document mapping is DONE and that each debug endpoint reports the intended provider; it preserves the new `similarityScores` diagnostics in its capture. Review failures and the complete capture before making a cutover decision. A direct citation lookup validates existence and identifier match; it does not establish source authority or factual truth.

No live retrieval numbers are included in this repository. RAGFlow health alone is insufficient to run the comparison; both populated datasets and server credentials are required.

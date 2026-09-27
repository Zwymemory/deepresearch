# RAGFlow paired retrieval evaluation

`paired_eval.py` measures the existing Java retrieval pipeline and the opt-in RAGFlow pipeline through the same `POST /api/research/hybrid/debug` request. The legacy ranking comes from `rerankResult`; RAGFlow ranking comes from `compressedContext`, which the RAGFlow debug adapter populates from its normalized evidence gateway. For the four project safe-denial cases only, each measured retrieval is followed by `POST /api/research/hybrid` so the evidence-backed refusal contract can be scored. Answer-model latency is captured separately and never enters retrieval p95. Keep `topK`, questions, corpus and host conditions identical for both runs.

## Inputs

For the local RAGFlow instance, open `http://127.0.0.1` (UI, port 80), then
use the account menu's API page for an existing API key. A dataset URL such as
`/dataset/files/<id>` exposes its dataset ID; the HTTP API is on port 9380.
Set `RAGFLOW_API_KEY` and `RAGFLOW_DATASET_IDS` in an ignored `.env` for
Compose. Host-run Java uses `RAGFLOW_BASE_URL=http://127.0.0.1:9380`; the
Compose app defaults to `http://host.docker.internal:9380`. Leave
`DEEPRESEARCH_RETRIEVAL_PROVIDER=legacy` for normal use until the paired
project gate passes.

- `synthetic_knowledge.md` is an invented, deterministic document. `synthetic_cases.json` has 25 answerable questions and 3 no-evidence questions. This is a parser/search smoke test; its scores cannot satisfy the project-knowledge gate.
- `project_cases.json` contains the 25 positive and 4 negative questions from `testdata/eval/project-knowledge-gold.jsonl` plus the reviewed 2026-09-26 evidence contract. Each `rankingAnchors`, `requiredFacts`, or `denialEvidence` item is one logical label whose `evidencePhrases` are equivalent alternatives. Review provenance and canonical corpus hashes are stored under `review`.
- Import the same eight sanitized `docs/kb-project` Markdown files on both routes with `scripts/import-project-kb.sh`. A project label counts only when a reviewed phrase occurs in a saved top-K preview after the declared normalization. Broad candidate words from the earlier schema are no longer labels.
- The four project negative prompts use `kind=evidence-backed-safe-denial`: retrieval must supply every declared boundary-evidence label, the answer must contain an allowed denial phrase and no forbidden phrase, and at least one valid `[来源N]` marker must resolve through the answer's `sources` array. The three synthetic negatives remain `kind=zero-evidence` and still require both routes to return no citable entry.
- `conditions.example.json` is intentionally incomplete. Copy it into a private run directory and record the two Git/config versions, retrieval thresholds, dataset IDs (no API keys), corpus hashes/counts, machine, database snapshots, and wall-clock test window. For the project run, put **all** Java IDs of the synchronized project documents in `projectDocumentIds`. The project `goldLabelsReviewed` gate requires the manifest's reviewed provenance and an exact match between its canonical hashes and these recorded corpus hashes; a stale conditions boolean cannot override a hash mismatch. Never put credentials in this file.

Gold matching applies Unicode NFKC normalization, removes Markdown backticks, and collapses whitespace on both the phrase and debug `preview`; comparison remains case-sensitive. It does not use a title guess or cross-provider document ID. A logical label matches when any of its phrase alternatives occurs in any top-K preview. Preview truncation can still create a real measurement miss, so inspect the saved per-sample capture. The two systems use different chunk IDs and their keys must not be compared directly.

`requiredFacts` is the all-of evidence contract for a positive question. At least one alternative for **every** fact must occur in top-K, possibly in different chunks, for that sample's `answerableAtK` to be true. The report preserves the first-sample metrics for comparison and also reports every measured sample, every-sample coverage, and rank instability. Case 019 now distinguishes call-ID inputs, stable retry identity, identifier-only semantics, and downstream durable deduplication; an anchor hit cannot conceal missing limitations. `goldLabelsReviewed` means that the contract and canonical source hashes were reviewed. It remains true when an evidence, stability, latency, or safe-denial gate fails.

## Run

Prepare **two Java instances or sequential runs over equivalent isolated database snapshots**, one with `DEEPRESEARCH_RETRIEVAL_PROVIDER=legacy`, one with `DEEPRESEARCH_RETRIEVAL_PROVIDER=ragflow`. The latter also needs `RAGFLOW_BASE_URL`, `RAGFLOW_API_KEY`, and `RAGFLOW_DATASET_IDS`. When Java runs in a container, `RAGFLOW_BASE_URL` must resolve from that container; `127.0.0.1:9380` only works for Java on the host. Both instances must contain the same relevant project documents. Keep the RAGFlow service at its existing `9380` port and do not remove its data volumes.

The harness needs Java credentials to upload the fixture (`ADMIN`), call debug, and collect project safe-denial answers. It reads them from `EVAL_LEGACY_TOKEN` and `EVAL_RAGFLOW_TOKEN`. It reads the RAGFlow API key from `RAGFLOW_API_KEY` only for independent citation backchecks. No token is written to capture or report files.

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

### Sequential collection on a resource-constrained host

Use `--route` when only one Java retrieval route can run at a time. Reuse the exact same manifest, fixture bytes, and conditions file; declare one test window that covers both collections. The merge command rejects any manifest, fixture hash, or conditions drift. A one-route capture records `collectedRoutes` and cannot be scored until it is merged with its counterpart.

```sh
# Run while the Java instance is configured with provider=legacy.
python3 integrations/ragflow/paired_eval.py collect \
  --route legacy \
  --manifest integrations/ragflow/synthetic_cases.json \
  --conditions /tmp/ragflow-eval-conditions.json \
  --legacy-url http://127.0.0.1:8080 \
  --ingest-fixture --warmup 1 --repetitions 3 \
  --output /tmp/ragflow-synthetic-legacy-partial.json

# Stop only that Java instance, restore the equivalent isolated DB snapshot,
# and restart Java with provider=ragflow before collecting the second route.
python3 integrations/ragflow/paired_eval.py collect \
  --route ragflow \
  --manifest integrations/ragflow/synthetic_cases.json \
  --conditions /tmp/ragflow-eval-conditions.json \
  --ragflow-url http://127.0.0.1:8080 \
  --ragflow-api-url http://127.0.0.1:9380 \
  --ingest-fixture --warmup 1 --repetitions 3 \
  --output /tmp/ragflow-synthetic-ragflow-partial.json

python3 integrations/ragflow/paired_eval.py merge \
  --legacy-capture /tmp/ragflow-synthetic-legacy-partial.json \
  --ragflow-capture /tmp/ragflow-synthetic-ragflow-partial.json \
  --output /tmp/ragflow-synthetic-capture.json
python3 integrations/ragflow/paired_eval.py score \
  --capture /tmp/ragflow-synthetic-capture.json \
  --output /tmp/ragflow-synthetic-report.json
```

The merged capture contains exactly one run for every case and route. It combines the route-specific ingestion evidence, RAGFlow project mapping checks, and citation backchecks. This permits Java and database resources to be time-sliced while RAGFlow and Dify remain running.

Repeat `collect` and `score` with `project_cases.json` and separate output names. Synchronize the project corpus on both routes first. You can use `--ingest-fixture` to prove synthetic parse completion during that run, but the extra fixture then enters the retrieval corpus and must be recorded in the conditions. The 2026-09-22 project run kept the eight-document project corpus clean and measured fixture parsing in a separate synthetic run. Its project score therefore has `fixtureParsedBoth=false`; read both reports together. `projectDocumentIds` are checked through `GET /api/kb/documents/{docId}/ragflow-sync`, which reconciles the remote job and reports mapping status. A `kb_document` row marked DONE without a DONE RAGFlow mapping does not pass. Each route is warmed once per query, then measured three times. When both routes are collected together, route order alternates by query. Every repetition is scored; the report exposes first-sample and every-sample coverage plus rank instability. Retrieval p95 uses only debug-call durations and the nearest observed rank, `ceil(0.95 × n)`. If debug returns `stageTimingMs`, the collector allowlists `queryRewrite`, `registry`, `upstreamApi`, `evidenceNormalization`, `responseAssembly`, and `total`; the scorer reports sample count, mean, and p95 for each available stage.

To apply the reviewed contract to a compatible older capture without modifying it, pass `--manifest` to `score`. Missing safe-denial answer samples fail closed:

```sh
python3 integrations/ragflow/paired_eval.py score \
  --capture integrations/ragflow/project_paired_capture_2026-09-22.json \
  --manifest integrations/ragflow/project_cases_baseline_2026-09-26.json \
  --output /tmp/ragflow-project-reviewed-report.json
```

The baseline manifest preserves the source hashes used by the historical
capture. Use `project_cases.json` for new runs after the case-closure source
edits.

## Reading the report

The report includes each sample's logical-anchor ranks and complete-fact coverage, first/every-sample aggregates, project safe-denial evidence and answer checks, synthetic zero-evidence rate, parse completion, retrieval and optional stage p95, relative misses, instability, and RAGFlow chunk API backchecks. A missing `RAGFLOW_API_KEY` or `--ragflow-api-url` makes citation verification unverified and prevents the gate from passing. Missing answer samples, mappings, or ingestion evidence also remain explicit failures. `readyToSwitch` is an **automated gate result for the retrieval data plane only**; it does not authorize changing the default route or complete the Dify and end-to-end migration gates.

The migration plan's retrieval thresholds are encoded as gates: at least 25 reviewed project positives, at most one new miss relative to legacy, zero critical new misses, complete RAGFlow required facts on every repetition, the appropriate negative contract on both paths, every displayed RAGFlow citation resolved to the same real dataset/document/chunk, stable ranking, and new p95 no more than 1.5 times legacy p95 on the same machine. The collector also checks each declared project document mapping is DONE and that each debug endpoint reports the intended provider; it preserves `similarityScores` and the allowlisted stage timings. Review failures and the complete capture before making a cutover decision. A direct citation lookup validates existence and identifier match; it does not establish source authority or factual truth.

The local measurements and reproducible captures are in the
[project paired report](PROJECT_PAIRED_RESULT_2026-09-22.md) and
[synthetic paired report](SYNTHETIC_PAIRED_RESULT_2026-09-22.md). They are
diagnostic results under recorded local conditions. Neither report supports
changing the default route yet. The reviewed contract's offline application to
the original project capture is recorded in
[the 2026-09-26 rescore](PROJECT_EVAL_CONTRACT_RESCORING_2026-09-26.md).
The subsequent source-evidence repair and live paired rerun are in the
[2026-09-26 case-closure report](PROJECT_CASE_CLOSURE_2026-09-26.md).
The isolated synthetic fixture rerun is in the
[2026-09-26 synthetic gate recheck](SYNTHETIC_GATE_RECHECK_2026-09-26.md):
both parsers reached `DONE`, while the legacy route still returned evidence
for all three zero-evidence negatives.
The follow-up [legacy abstention repair](LEGACY_ABSTENTION_REPAIR_2026-09-27.md)
records the fail-closed implementation, final-code synthetic and holdout
captures, and the remaining project latency and full-regression gaps.

For slow external answerability verification during a diagnostic collection,
`EVAL_HTTP_TIMEOUT_SECONDS=90` raises the collector's per-request HTTP limit
from 30 seconds. Record that limit in the run conditions; it changes only the
collector timeout, not the Java service or a quality gate.

## Showcase 答案级评测

`paired_eval.py` 只给出检索证据门禁。`showcase_eval.py` 再采集完整问答：原有 `project_cases.json` 的 29 题，加上采样前固定的 [8 题 holdout](showcase_holdout_cases.json)。新题包括中文改写、多事实问题、纯无证据和有边界证据的拒答；来源短语在仓库知识包中逐项校验。看过模型输出后的任何标签修正必须登记在 [label changes](showcase_label_changes.md)。

采集器支持 `legacy-hybrid`、`ragflow-dify`，以及用于隔离编排变量的 `ragflow-langgraph`。每种模式从独立 Java 配置采样，可在一台内存受限机器上依次运行。对每题先发一次 `/api/research/hybrid/debug` 检索探针，再运行答案接口或 Java workflow。**工作流探针是另一请求，不是该次 Dify/LangGraph 的内部工具回执**；其事实覆盖只能说明同配置检索能力，不能证明工作流实际用了这些 chunk。报告分别列出检索事实覆盖、答案短语匹配下界、人工核定答案事实覆盖、引用编号映射、RAGFlow 来源存在性、人工核定引用支持性，以及拒答正确性。人工审阅留空时相应结果为 `null`，不会被自动算作通过。

先从 [条件模板](showcase_conditions.example.json)为每种模式各建一份私有 JSON，填写统一代码 SHA、模型、项目语料哈希、topK、预算、主机和测试窗口；不要存入密钥、token、dataset ID 或私有路径。`SHOWCASE_EVAL_TOKEN` 是从演示页签发的 USER Token，仅放在环境变量中。RAGFlow 直接 chunk 回查还需要 `SHOWCASE_RAGFLOW_URL` 与 `RAGFLOW_API_KEY`，缺少它们时来源存在性保持未知。采集输出会将 RAGFlow 来源 ID 做稳定哈希，并遮蔽常见 JWT、邮箱和手机号；公开前仍须人工检查答案中是否有其他敏感内容。

```sh
export SHOWCASE_EVAL_TOKEN='从本地演示页签发的 USER Token'
export SHOWCASE_RAGFLOW_URL='http://127.0.0.1:9380'

# Java 当前配置为 legacy；按模板填写 /tmp/showcase-legacy-conditions.json。
python3 integrations/ragflow/showcase_eval.py collect \
  --mode legacy-hybrid --base-url http://127.0.0.1:8080 \
  --conditions /tmp/showcase-legacy-conditions.json \
  --output /tmp/showcase-legacy-capture.json

# 切换统一版本 Java 为 retrieval=ragflow、workflow=dify 后执行。
python3 integrations/ragflow/showcase_eval.py collect \
  --mode ragflow-dify --base-url http://127.0.0.1:8080 \
  --conditions /tmp/showcase-dify-conditions.json \
  --output /tmp/showcase-dify-capture.json

python3 integrations/ragflow/showcase_eval.py review-template \
  --capture /tmp/showcase-dify-capture.json \
  --output /tmp/showcase-dify-review.json
python3 integrations/ragflow/showcase_eval.py score \
  --capture /tmp/showcase-legacy-capture.json \
  --capture /tmp/showcase-dify-capture.json \
  --review /tmp/showcase-reviews.json \
  --output /tmp/showcase-score.json
```

两个模式的审阅模板按模式名合并为一个 JSON 后再 `score`。审阅者须对照实际引用证据填每个 `factChecks`、`citationSupport` 和 `refusalCorrect`，留下判断理由。`--case-id` 可重复指定关键题，`--repetitions 3` 用于复测；首次完整集合仍需所有 37 题。`answerP95Ms` 使用最近秩次法并附 `answerP95SampleCount`。同模型、同语料、同预算与同主机条件由报告 `comparability` 标志检查；有混杂因素时不归因于 Dify 或 RAGFlow。`usageObserved` 原样保留 API 返回值；缺项表示未知，不推算费用。旧检索修复后的完整 29 题必须用最终代码重新采样，不能沿用历史 46.9 秒 p95。

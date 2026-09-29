# Dify release-quality candidate — 2026-09-28

## Frozen acceptance before sampling

Baseline: `0210673584d38861917c897517dfa67247215c46`, published Evidence v4. The historical v4 captures and reviews remain unchanged.

The [12-case targeted manifest](../ragflow/showcase_dify_release_targeted_cases_2026-09-28.json) is frozen before the first response from this round. It contains the original Planner failure, both private-contact/credential boundary questions, the Kubernetes format-failure question, pure no-evidence, attempt/retry and citation-limit regressions, plus five Chinese rewrites. Rewrites inherit the exact source case's fact labels and denial contract. Two repetitions are planned for this targeted suite. The existing 29 + 8 full suite will then run once on the final fixed code and published DSL.

Manual acceptance separates required facts, direct support by citations, strict boundary denials, pure no-evidence and format failures. For the attempt/retry regression, the answer must distinguish two total attempts from at most one additional retry. Success status alone is not accuracy. A source must exist and support its attached claim; diagnostic text cannot become a citation. The banking questions remain pure no-evidence: a vaguely related safety boundary is not a citation for an unsupported banking fact.

## Implemented candidate contract

The installed official DeepSeek plugin 0.0.24 exposes `response_format=json_object` for `deepseek-v4-flash`, but its model manifest does not declare native JSON Schema. The candidate requests JSON object generation and keeps exact schema, tool scope and source validation in the Code nodes. It does not extract the first object, join adjacent objects, normalize malformed quotes, or add format repair calls. Adjacent JSON objects and the observed Unicode closing quote remain rejection fixtures, together with duplicate keys, non-finite constants, extra fields and invalid types. Planner, Reviewer and Synthesizer schemas require exactly their declared fields. There is no increase in the model-call or worker-call budget.

Evidence v5 explicitly retained thinking enabled, high reasoning effort and 4096 maximum output tokens on all possible LLM nodes. Its targeted run exposed one `finish_reason=length` truncation at 4096 completion tokens. The next candidate retains thinking for Planner/Reviewer and disables it for both mutually exclusive Synthesizers, requests concise answers and short source labels, and retains the same 4096 cap. Temperature 0 remains declared; the installed plugin removes it only on thinking nodes. These settings are not a claim of deterministic generation. LLM and tool retries remain disabled; a complete successful path executes one Planner, one Reviewer and one Synthesizer, and early insufficiency can execute fewer. Java's 120-second deadline remains unchanged.

The plan and revision validators identify private-contact and explicit raw-credential requests and replace model task inputs with one fixed `kb_search` query about the public corpus boundary. This also permits a valid empty Planner plan to retrieve public absence/exclusion evidence. It is a policy redirect, not format repair: malformed or unauthorized plans still fail, the knowledge tool must be allowed, and the same four-Worker combined budget applies. Ordinary credential-configuration questions keep their original read-only queries. Other missing facts are not automatically turned into boundary denials; Reviewer and Synthesizer require direct support for the requested fact or category. No actual private value is queried by this policy path.

The provider's [JSON Output guide](https://api-docs.deepseek.com/guides/json_mode/) documents `json_object`, prompt examples and adequate output length; it also notes that empty content can still occur. Such output must fail closed. These constraints do not establish factual correctness, so live samples and citation review remain required.

## Results

### Evidence v5 development sampling

The frozen targeted suite completed 24 samples: 19 `SUCCEEDED`, four `INSUFFICIENT_EVIDENCE`, one `FAILED`. This was not the final full-suite run. The original bank question's first repetition incorrectly turned an unrelated secret/contact exclusion into a cited absence claim. The attempt/retry question's first repetition failed strict JSON parsing because the Synthesizer hit its output cap; no partial answer was published. The citation-limit question's first repetition returned insufficient evidence. Both contacts/secrets pairs and both original JSON-failure cases otherwise produced cited answers in their two repetitions. These observations are preserved in the v5 capture and will receive the same manual scoring as the final candidate.

### Documented-boundary proof contract

The next candidate adds `answer_kind` and `boundary_support` to the existing Reviewer and Synthesizer JSON responses, without adding model calls. A documented denial must name a precise subject, quote at most 400 characters from an allowed evidence source, and identify its existing source label. The validator checks the quote against that exact source, confirms that the subject belongs to the question (or the corpus's contact-category synonym), and checks the subject within a supported explicit negative clause/list. The final gate also requires the proof's source to be cited in the answer. Generic project, document, production or sensitive-information subjects cannot substitute for the requested subject. Recognized private-value questions must use a documented boundary rather than a factual answer containing a value.

The scope rules are conservative Chinese/English negative-list grammar checks. They reject a separate clause, a conflicting predicate or contrast, and return empty insufficient evidence when scope cannot be established. They contain no bank-topic or case-ID decision rule. Development fixtures cover bank rewrites, an actual explicit bank exclusion, same-paragraph mentions with negation of another subject, invented quotes, and contact-category mismatches. These fixtures are separate from the frozen manifests. This is a necessary evidence-scope check, not a general semantic entailment proof; manual fact and citation review remains required.

Final fixed-version conditions, publication/hash, capture/review/score paths and results are recorded below. All earlier development samples remain available.

### Evidence v6 partial development sampling

V6 collected 13 of the planned 24 samples before an independent retrieval probe returned HTTP 500. RAGFlow logged a Zhipu embedding connection failure caused by Docker DNS `Errno -2`; subsequent host/container DNS checks resolved the provider again. The capture remains partial rather than being labelled a full suite. All 33 observed LLM node outputs were valid single JSON objects with exact root fields; none hit the token cap. Both banking repetitions were correctly empty, but all six contacts/secrets/Kubernetes repetitions were safe uncited insufficiency because model-generated boundary subjects restated the question or added parenthetical qualifiers. Both attempt/retry answers also omitted the required numerical additional-retry limit, so that fact is not credited. These failures remain visible in the v6 review and score.

The subsequent candidate requires a short subject copied from the negative source clause and passes the already validated Reviewer proof to the Synthesizer. The final gate independently checks that proof again. It adds only justified subject synonyms: the public contact category, raw JWT keys/JWT signing-key category, and K8s/Kubernetes topic names. A category match alone never bypasses the quote or negative-scope checks. Overqualified subjects remain rejected rather than being silently stripped. The retry prompt explicitly requests the numerical equation `N attempts = 1 first attempt + at most N-1 additional retries` after the evidence context. No budget increase or added call is introduced.

### Evidence v7 fixed DSL — targeted results

Implementation SHA: `86fac77e661bdcaac400a0d80cb4a26119c8078f`. Published workflow: `b1eb75ba-565c-4e13-ac15-4f97c42a97e4`, marked Evidence v7. DSL SHA-256: `48923fbfed9d2a6bf8d7c8d0ba3f2a4690fea959c04b8f21cf4d9d74f5273c46`; its 32-node/29-edge published graph exactly matches the committed generated DSL. Publication metadata and screenshot are saved beside this document.

All 24 frozen targeted samples completed: 17 `SUCCEEDED`, five `INSUFFICIENT_EVIDENCE`, one `FAILED`, one `TIMED_OUT`. Both original JSON-failure questions, private contacts and raw JWT questions passed in both repetitions. Both attempt/retry answers explicitly distinguish two total attempts from at most one additional retry. All four banking samples (original and rewrite) stayed empty with zero citations. Manual full-fact coverage is 6/8 positive samples; strict refusal correctness is 15/16 negative samples. All 17 published citation groups exist and support their attached claims.

The remaining three failures are disclosed: two citation-mapping samples had external model TLS EOF/deadline failures, and one K8s rewrite was safe uncited insufficiency because Reviewer omitted `interrupt` from an original boundary quote. The exact-quote gate rejected it rather than accepting a paraphrase or discarding invalid proof items. All 62 available LLM outputs were valid single objects with exact root fields; two nodes had unavailable output due to transport/stop. No available output hit `finish_reason=length`. Calls observed from Dify node records remained at most three LLM nodes and four Worker HTTP nodes per run; these counts are not an independent provider billing/request ledger. Native usage totals reflect observed completed output only; an aborted provider request can have unknown billable usage.

The complete 37-case run used this same fixed DSL and runtime source. Only the audit reporter and result artifacts were committed between the targeted and full runs; the reporter distinguishes unavailable transport output from a malformed model response. No targeted case, label, prompt or runtime implementation changed after this targeted run.

### Evidence v7 fixed DSL — single complete 37-case run

Tested SHA: `fa021dc7c36b0b87514bdc01f2383bc9f783a24a`. The Java jar was rebuilt and restarted on that commit. Comparing Java/Python runtime source, the DSL/builder, evaluator and all three manifests with targeted implementation SHA `86fac77e661bdcaac400a0d80cb4a26119c8078f` produces an empty diff. The published workflow ID and DSL hash above are unchanged. This is one response per case in the existing 29 + 8 suite; no failed response was replaced or resampled.

| Measure | Frozen targeted repeats | Complete suite, once |
| --- | --- | --- |
| Samples | 24 | 37 |
| Java terminal statuses | 17 success, 5 insufficient, 1 failed, 1 timed out | 34 success, 2 insufficient, 1 failed |
| Positive samples with every required fact | 6/8 | 29/31 |
| Mean per-positive-sample fact coverage | 0.750000 | 0.959677 |
| Strict refusal correctness | 15/16 | 5/6 |
| Published citation groups with existing sources | 17/17 | 34/34 |
| Published citation groups supporting all attached claims | 17/17 | 33/34 |
| Answer P95, including failed/insufficient samples | 26.497572 s, n=24 | 16.871826 s, n=37 |
| Separate retrieval-probe P95 | 1.080352 s | 0.554638 s |
| Available / unavailable LLM node outputs | 62 / 2 | 108 / 0 |
| Single-object JSON / exact root schema failures | 0 / 0 | 0 / 1 |
| Output-length truncations | 0 | 0 |
| Observed LLM nodes / Worker HTTP nodes | 64 / 40 | 108 / 97 |
| Maximum nodes per run, LLM / Worker HTTP | 3 / 4 | 3 / 4 |
| Observed total tokens | 115,307 | 229,375 |

These suites have different question mixes; their P95 values are descriptive, not a causal performance comparison. Token counts come from observed Dify usage, not an independent provider billing ledger. For the full suite, mean tokens per sample were 6,199.324324 and token P95 was 8,438. Dify's recorded zero price is not proof that these requests cost nothing.

All 37 answers and their attached source previews were reviewed individually. `project-kb-025` receives only three of four required facts: it incorrectly equates two persistent attempts with two retries, then appends the correct equation “two total attempts = first attempt + at most one additional retry.” The contradiction fails the frozen numerical distinction and citation-support acceptance, despite `SUCCEEDED` and valid citation IDs. The remaining incomplete positive sample is `showcase-holdout-001`: Reviewer returned valid JSON with extra `answer` and `citations` fields beyond its four-field schema. The Code validator returned `FAILED`, without publishing that text. JSON object mode therefore reduced the observed syntax problem without eliminating schema deviations.

`project-kb-neg-003` returned empty `INSUFFICIENT_EVIDENCE`, with no invented MAU/P99/SLA numbers. Its Reviewer explicitly chose insufficiency; this is safe but fails the frozen requirement for an evidence-backed cited denial. Private contacts, raw JWT keys and both Kubernetes denials passed with actual public boundary sources. The pure banking question returned exactly `INSUFFICIENT_EVIDENCE`, empty answer and zero citations. No generic secret/contact exclusion was promoted into evidence about a bank account.

The capture's inherited free-text `conditions.notes` begins “Evidence v6 candidate.” This is a stale description, preserved rather than rewriting the captured record. Its machine fields (`codeSha`, `publishedWorkflowId`, `dslSha256` and per-node model settings), publication metadata and native trace all identify the fixed Evidence v7 measured here.

### Inspectable artifacts and offline checks

Each row links the original answer capture, individual manual review, reproducible score and redacted native node-format/usage audit. V6 remains partial at 13/24 samples. The native audit omits model reasoning and credential headers; its private source export is outside the repository.

| Version and suite | Capture | Manual review | Score | Native audit |
| --- | --- | --- | --- | --- |
| V5 targeted development, 24 samples | [capture](../ragflow/showcase_dify_release_v5_targeted_capture_2026-09-28.json) | [review](../ragflow/showcase_dify_release_v5_targeted_review_2026-09-28.json) | [score](../ragflow/showcase_dify_release_v5_targeted_score_2026-09-28.json) | [audit](../ragflow/showcase_dify_release_v5_targeted_format_2026-09-28.json) |
| V6 partial development, 13 samples | [capture](../ragflow/showcase_dify_release_v6_targeted_capture_2026-09-28.json) | [review](../ragflow/showcase_dify_release_v6_targeted_review_2026-09-28.json) | [score](../ragflow/showcase_dify_release_v6_targeted_score_2026-09-28.json) | [audit](../ragflow/showcase_dify_release_v6_targeted_format_2026-09-28.json) |
| V7 frozen targeted, 24 samples | [capture](../ragflow/showcase_dify_release_v7_targeted_capture_2026-09-28.json) | [review](../ragflow/showcase_dify_release_v7_targeted_review_2026-09-28.json) | [score](../ragflow/showcase_dify_release_v7_targeted_score_2026-09-28.json) | [audit](../ragflow/showcase_dify_release_v7_targeted_format_2026-09-28.json) |
| V7 complete 37-case suite, once | [capture](../ragflow/showcase_dify_release_v7_full37_capture_2026-09-28.json) | [review](../ragflow/showcase_dify_release_v7_full37_review_2026-09-28.json) | [score](../ragflow/showcase_dify_release_v7_full37_score_2026-09-28.json) | [audit](../ragflow/showcase_dify_release_v7_full37_format_2026-09-28.json) |

On the tested SHA, the actual Code-node verifier passed, Maven reported 241 tests in 56 suites with zero failures/errors/skips, the Python nonintegration suite passed 112 tests, and the evaluator's seven unit tests passed. The [verification record](evidence-v7-release-verification-2026-09-28.json) reproduces all four new score files from their captures and reviews and records the checked full-suite metrics, frozen manifest membership and unchanged DSL hash. These checks do not turn manual review into an independent blind assessment.

To reproduce the complete-suite score without network access:

```sh
python3 integrations/ragflow/showcase_eval.py score \
  --capture integrations/ragflow/showcase_dify_release_v7_full37_capture_2026-09-28.json \
  --review integrations/ragflow/showcase_dify_release_v7_full37_review_2026-09-28.json \
  --output /tmp/deepresearch-v7-full37-rescore.json
```

The [publication record](evidence-v7-release-publication-2026-09-28.json) and [published version screenshot](evidence-v7-published-2026-09-28.png) identify the local app version. The result is a reviewable demo candidate with disclosed quality misses, not a 100% accuracy or production-readiness claim. The independent release task owns fresh-directory reproduction and the joint fault demonstration. The [Java acceptance handoff](ACCEPTANCE_2026-09-28.md) explains durable cancellation/remote-stop states; this quality suite alone does not prove all real-provider kill/restart or browser fault scenarios.

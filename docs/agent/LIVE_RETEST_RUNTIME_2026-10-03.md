# Round 1 live acceptance retest — runtime report

Status: authorized preparation; no new research submissions yet. This dated report preserves the prior stopped campaign's history. Final run results will be added after observation, including all failures and unattempted cases.

Baseline: `62e29a77130f727413558140a6c8872410a0b731`. Dedicated candidate branch: `feat/agent-live-retest-20261003`. Coding model: GPT-6.1-sol/high. Research runtime: existing `deepseek-openai-compatible/deepseek-v4-flash`, verified at startup and on actual provider responses without an extra model probe.

## Authorized batch and history

Batch `round1-retest-20261003` allows at most five new research submissions and zero automatic research reruns, in the fixed order web-only, mixed, version-conditions, contradictory-material, insufficient-evidence. Each terminal run requires an independent B semantic decision bound to the run ID, exact build, audit byte hash and Evidence snapshot hashes before another reservation. The first three require complete source-backed answers; honest insufficiency is eligible only for the final two. Any failed/unknown request, unavailable model result, identity mismatch, budget failure, or failed/incomplete semantic review stops subsequent paid work.

The original protected campaign state directory and `run-journal.json` are reused. Six historical rows, including two consumed retries and two final model failures, remain immutable. A separate authorization record binds a byte-identical historical journal snapshot, the old stop metadata and the authority document. The original eight-run guard is unchanged. New reservations are appended under the existing exclusive file lock; a lost POST response consumes its slot and is never submitted again. The allowance cannot move to a fresh directory. Per-run limits remain 8 decisions,16 model/16 tool admissions,180 seconds,64000 input/16384 output tokens.

No new sources are uploaded or parsed. B's signed-off committed scenario manifest reuses the existing synthetic dataset and reviewed IANA materials; only the already-authorized research runtime retrieves/reads sources. B performs no independent provider/source requests. Production services, credentials, environment files, existing eight knowledge documents, and production volumes are outside this task.

## Admission and identity checks

`scripts/agent_retest_batch.py` implements the distinct allowance and immutable history binding. `scripts/accept-agent-round1.py --batch round1-retest-20261003` requires exact-SHA success for all six applicable CI jobs, the original state directory, unchanged committed questions, B readiness, configured source registry, and verified app/sidecar/database identity. Batch registration requires an explicit `--authorize-batch` invocation with the current authority and immutable historical stop record. Later submissions require exact independent `pass` decisions; fixture expectations never supply semantic approval.

The app is rebuilt from a pinned committed Git archive with embedded SHA/source-manifest/isolation identity. Host/container JAR hashes, image ownership, loopback ports, source blobs, sidecar process/import/source hashes and database volume ownership are checked. The dedicated stopped database can resume only after its ownership, volume and declared loopback binding are verified. Model identity is observed on existing calls with only expected/unrecognized model names, matching booleans and numeric HTTP status stored. Successful responses with absent or mismatched model identity are stopped; no raw provider prose, request prompts or credentials are recorded by this observer.

Offline script tests cover history tampering, changed authority/stop, duplicate/concurrent admission, lost response consumption, strict order and exact review bindings, immutable verdicts, capacity, honest insufficiency versus failure, native model identity observation, and absence of extra provider requests. Full local/remote gates, fixed candidate SHA, actual isolation identity, run table, usage and diagnoses will be recorded below after verification and execution. The protected coordinator receipt contains concrete local log paths; public reports use portable paths.

## Progress and remaining work

Runtime preparation and candidate verification are in progress. All five new scenarios are currently unattempted. The prior knowledge-only success belongs to the old campaign and is not a success of this batch. Actual bill/cost remains unknown until a provider billing receipt is available; known token usage and missing usage will be reported separately. Long-term research memory and multi-Agent cooperation remain unstarted pending coordinator acceptance of this round.

# Original-question requirements and verified coverage

Task B implementation is based on common baseline `f557443de6aa89152d80b7e2907a02e1f2caf534`. The old independent report commit `57ff9d90b05725a046fe075e6bae916eecac58e1` is preserved on a separate local branch. This deliverable adds only `agent_requirements.py`, its dedicated tests, and this report. Existing runtime, protocol, SQL and Java integration belong to Task A. No real model, search or research call, push, deployment, environment change or historical acceptance rewrite occurred.

The prior research checked two factual groups but persisted one generic criterion. Such a task cannot establish coverage of the entire original question. Requirements must be extracted explicitly from the original question before evidence-driven work, retained independently of mutable plans, and resolved through actual scoped adjudications.

## Declaration and immutable identity

The first existing budgeted decision response supplies `RequirementDraft` objects: obligation text, exact question codepoint spans, Claim kind, subject, version, fact-effective time and conditions. It requires no additional planning call. `freeze_requirements` checks structural anchors and creates server-derived IDs under `agent-original-requirements/1`, bound to exact run and original-question UTF-8 hash. Draft order, condition order and unknown-reason wording do not change identity. The immutable manifest records all declarations plus a canonical digest; replay requires an identical manifest. Removed, added or narrowed obligations fail visibly.

All nonwhitespace original characters need anchors, including shared constraints. Span overlap is permitted for shared qualifiers. This is a literal anchoring check, **not a semantic completeness proof**: a planner can still give one inadequate obligation a full-question span. There is no punctuation splitter, keyword inference, question-specific rule or automatic generic requirement. Independent review and model instructions must assess extraction quality. The tests deliberately retain a bad full-question declaration example to make this limitation explicit. The module never represents its IDs/hashes as proof that every meaning was captured.

Claim-to-obligation relevance is also semantic. Matching subject/version/time/conditions and a criterion ID cannot prove that Claim text answers the requirement. The planner must use precise obligation subjects/text and propose Claims that directly resolve those stored criteria. Independent semantic review must check that mapping; no additional unbudgeted model call is introduced to certify arbitrary-language entailment.

At most 32 obligations and 16 spans per obligation are supported; limits reject instead of dropping requirements. The runtime must also obey its smaller task/criterion and output budgets. If a complete declaration cannot be obtained within those limits, it must preserve an honest gap. Missing manifests in legacy checkpoints are unknown coverage, not migrated proof from the first old task or Claim.

## Association and completion

`bind_requirements` validates append-only requirement-to-criterion associations. Both identities must exist. Each obligation owns a distinct criterion; a criterion cannot resolve multiple original obligations. Replanning can add supporting tasks, but cannot remove or replace the original association or silently rewrite the requirement scope. `validate_requirement_claims` reuses the existing criterion binding logic and checks original scope/kind before executing a check. Supporting claims outside those bindings remain possible.

`evaluate_coverage` recomputes criterion dependency freshness on a copy. Each required obligation needs an immutable expected scoped Claim, current accepted investigation, matching latest call, check ID, exactly one matching fresh Claim and one DecisionRecord for that Claim and run, a supported or refuted result, no decision gaps or unresolved evidence, and nonempty adopted evidence IDs linked to the matching Claim direction. One Claim cannot resolve multiple obligations. Task `done` and packet `complete` labels provide no independent proof. Correct refutations count as resolved questions; contested, insufficient, stale or scope-mismatched results retain gaps. The report includes stable requirement IDs, obligation text, status, criterion/check/Claim/Decision/evidence references, and gaps. Input state is unchanged.

These are structural checks over authoritative state. The server must still verify settled operation receipts, immutable check records, source ownership, evidence freshness, quote offsets/hashes and unresolved durable capacity gaps. A caller-supplied self-consistent manifest digest cannot authorize a replacement. Task A must persist and load the immutable server manifest and implement equivalent Java publication enforcement; Python helper success alone cannot authorize a public complete report.

## Offline validation

64 dedicated tests passed, plus 30 existing criterion/investigation regressions (94 total). Ruff lint and formatting passed. Tests use sanitized Atlas v2 enterprise encryption/retention questions and Unicode questions; no IANA or other scenario hardcoding exists in the implementation.

Validated cases include omitted second question regions, a first verified Claim plus misleading complete labels, correct two-obligation coverage, valid refutations, version/subject/condition/time/kind substitution, failed/pending/stale latest attempts, duplicate or absent Claim/Decision records, wrong run, old Claim, unlinked/adopted evidence defects, cancelled tasks, changed prerequisites, missing associations, duplicate/unknown/cross-run IDs, reused criteria/Claims, attempted association replacement, removed criteria/requirements, changed declarations, JSON restart/order invariance, strict span bounds and UTF-8-versus-codepoint offsets. The declaration models can extend the existing decision schema without an additional model invocation or circular eager import.

Reproduction from this checkout:

```sh
PYTHONPATH=workflow-service/src workflow-service/.venv/bin/python -m pytest \
  workflow-service/tests/test_agent_requirements.py \
  workflow-service/tests/test_agent_completion.py \
  workflow-service/tests/test_agent_investigations.py
workflow-service/.venv/bin/ruff check \
  workflow-service/src/deepresearch_workflow/agent_requirements.py \
  workflow-service/tests/test_agent_requirements.py
```

The protected local failed-state hashes matched the handover: audit `ca52407cc14b89c141ef69d6487cb17e717953793d8c779b7d5bfb5bc69040e9`, selected state `21c0e24d21a84fb4fb0a7aefa6a3183e0b35bc9153ad66ddcf46c33e3c9f5bb3`, binding `771d95ff2d28f059f5781006e0066fe46c4233b6d133f6f5e4f7d4dcbc4ceae8`, independent reconstruction `9a2a07626f4e0b772b941683c15faa97979c545a292e23f369b62cfc1ce5f561`, prior proof manifest `ecbf6433a2d76b7accb982b8c7c2f5d7586c4b2a58ddf15b3489e846c91bbc56`. They were read only; raw diagnostic state/provider output was not added to tests or commits. The failed checkpoint has one task and one criterion; it is not relabeled as complete.

All 42 historical B proof files were independently rehashed against that prior manifest and remained unchanged. The committed module/test/report delivery `a372a8d4e7bf8be784700d148ebe1da99a928849` passed the full committed public-tree/history gate: 191 commits, 836 files, zero leaks or waivers. A acquired this exact source through local cherry-pick `d9ee6784c8a7bdc32f0e652909bfc10ef43fa895`; integration review remains pending its combined candidate.

## Integration handoff

A acknowledged the shared contract. Required hooks: first budgeted decision extraction and deterministic freeze before action; native immutable run/question manifest and append-only bindings; distinct server-created criteria from declarations; precheck scope validation; persistent obligations in every decision projection and restart; coverage before finish; partial reports listing every original uncovered obligation; independent Java authority using the same frozen requirement scope and actual current check provenance. New contract/projection versions must alter request identity without rewriting settled old requests.

This report initially validates the standalone module only. B will review A's immutable integrated candidate after these hooks exist; no integrated production behavior or live acceptance is claimed here. The original live failure remains FAIL/STOPPED. Further real acceptance requires a separately bounded authorization through the coordinator.

## Exact integrated coverage-hook review

B independently exported and reviewed A candidate `410e253625625ef108c52393143f86fb22221c05`, preserving its exact source. The B module is byte-identical to `a372a8d`. 99 pinned-source Python requirement/context-runtime/completion/investigation checks passed. Offline Maven against this export passed two requirement Java units and 27 migrated native SQL/publication tests with zero failures, errors or skips, using the existing disposable test fixtures. The actual native tests demonstrate complete cited two-obligation publication without another model operation, honest partial publication for an uncovered second obligation despite misleading complete labels, and immutable declaration/association checks.

B found one P2 integration defect: Java `Character.isWhitespace` disagreed with Python `str.isspace` for NBSP and related separators. An identical original question manifest for `encrypt\u00a0retain`, with both word regions anchored and the whitespace unanchored, passed Python freeze but failed native Java `validManifest`, forcing a valid plan toward false incompleteness. The immutable corrective candidate `b4b1bdff59e8b16f28535dc6f648ebf7011c4dec` changes both native anchor checks to a Python-compatible predicate and adds six separator cases; source-quote semantics and frozen public contracts remain unchanged.

B independently compiled this exact corrective export: three focused Java units passed, the original NBSP fixture now passes actual native `validManifest`, and the compiled private predicate matches Python 3.12 over all 1,114,112 Unicode codepoints (29 whitespace codepoints, identical sets). The correction leaves all previously reviewed Python runtime/protocol, SQL, module and wire code unchanged. Private fix review proof SHA-256 is `a1ec81ea260a434af99d66437fe2f6ff94391f2cace11b16902893d180b829b5`; raw diagnostic inputs are not committed.

**B original-requirement integration hooks: PASS at `b4b1bdff59e8b16f28535dc6f648ebf7011c4dec`.** The first existing budgeted response supplies and freezes distinct obligations before its action. Native persistence binds the immutable manifest to exact original question and settled declaration; associations cannot be replaced. Replans/restarts retain original requirements; scope/kind substitution rejects before verification. Current scoped Claim/Decision/evidence/check/dependency proof gates finish. Missing legacy declarations or uncovered/contradictory obligations cannot yield complete success; original requirement IDs/text remain in partial output and context. Java independently reloads authority and includes requirements in the final sealed state. Packet capacity gaps prevent mechanical closure, and mechanical publication adds no synthesis model request. B found no additional blocker in these hooks.

This scoped offline B verdict does not replace C's broader independent combined review, certify arbitrary-language extraction/Claim relevance, grant live readiness, or change the historical real FAIL/STOPPED result. No real provider/source/research calls, A checkout writes, pushes, deployments or environment/frontend changes occurred.

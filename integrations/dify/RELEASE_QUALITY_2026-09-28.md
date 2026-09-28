# Dify release-quality candidate — 2026-09-28

## Frozen acceptance before sampling

Baseline: `0210673584d38861917c897517dfa67247215c46`, published Evidence v4. The historical v4 captures and reviews remain unchanged.

The [12-case targeted manifest](../ragflow/showcase_dify_release_targeted_cases_2026-09-28.json) is frozen before the first response from this round. It contains the original Planner failure, both private-contact/credential boundary questions, the Kubernetes format-failure question, pure no-evidence, attempt/retry and citation-limit regressions, plus five Chinese rewrites. Rewrites inherit the exact source case's fact labels and denial contract. Two repetitions are planned for this targeted suite. The existing 29 + 8 full suite will then run once on the final fixed code and published DSL.

Manual acceptance separates required facts, direct support by citations, strict boundary denials, pure no-evidence and format failures. For the attempt/retry regression, the answer must distinguish two total attempts from at most one additional retry. Success status alone is not accuracy. A source must exist and support its attached claim; diagnostic text cannot become a citation. The banking questions remain pure no-evidence: a vaguely related safety boundary is not a citation for an unsupported banking fact.

## Planned output constraint

The installed official DeepSeek plugin 0.0.24 exposes `response_format=json_object` for `deepseek-v4-flash`, but its model manifest does not declare native JSON Schema. The candidate will request JSON object generation and keep exact schema, tool scope and source validation in the Code nodes. It will not extract the first object, join adjacent objects, normalize malformed quotes, or add format repair calls. Adjacent JSON objects and the observed Unicode closing quote remain rejection fixtures. There is no increase in the model-call or worker-call budget in this first candidate.

The provider's [JSON Output guide](https://api-docs.deepseek.com/guides/json_mode/) documents `json_object`, prompt examples and adequate output length; it also notes that empty content can still occur. Such output must fail closed. These constraints do not establish factual correctness, so live samples and citation review remain required.

## Results

Sampling has not started. The final code SHA, published version/DSL hash, conditions, capture, review, score, failure categories and validation commands will be recorded here after the fixed-version run.

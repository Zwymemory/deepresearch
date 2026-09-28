# Dify release-quality candidate — 2026-09-28

## Frozen acceptance before sampling

Baseline: `0210673584d38861917c897517dfa67247215c46`, published Evidence v4. The historical v4 captures and reviews remain unchanged.

The [12-case targeted manifest](../ragflow/showcase_dify_release_targeted_cases_2026-09-28.json) is frozen before the first response from this round. It contains the original Planner failure, both private-contact/credential boundary questions, the Kubernetes format-failure question, pure no-evidence, attempt/retry and citation-limit regressions, plus five Chinese rewrites. Rewrites inherit the exact source case's fact labels and denial contract. Two repetitions are planned for this targeted suite. The existing 29 + 8 full suite will then run once on the final fixed code and published DSL.

Manual acceptance separates required facts, direct support by citations, strict boundary denials, pure no-evidence and format failures. For the attempt/retry regression, the answer must distinguish two total attempts from at most one additional retry. Success status alone is not accuracy. A source must exist and support its attached claim; diagnostic text cannot become a citation. The banking questions remain pure no-evidence: a vaguely related safety boundary is not a citation for an unsupported banking fact.

## Implemented candidate contract

The installed official DeepSeek plugin 0.0.24 exposes `response_format=json_object` for `deepseek-v4-flash`, but its model manifest does not declare native JSON Schema. The candidate requests JSON object generation and keeps exact schema, tool scope and source validation in the Code nodes. It does not extract the first object, join adjacent objects, normalize malformed quotes, or add format repair calls. Adjacent JSON objects and the observed Unicode closing quote remain rejection fixtures, together with duplicate keys, non-finite constants, extra fields and invalid types. Planner, Reviewer and Synthesizer schemas require exactly their declared fields. There is no increase in the model-call or worker-call budget.

All four possible LLM nodes explicitly retain the installed plugin's previous defaults: thinking enabled, high reasoning effort and 4096 maximum output tokens. Temperature 0 remains declared, but the installed plugin removes temperature in thinking mode; it is not a claim of deterministic generation. LLM and tool retries remain disabled; a run executes one Planner, one Reviewer and one of the two mutually exclusive Synthesizers. Java's 120-second run deadline remains unchanged.

The plan and revision validators identify private-contact and explicit raw-credential requests and replace model task inputs with one fixed `kb_search` query about the public corpus boundary. This also permits a valid empty Planner plan to retrieve public absence/exclusion evidence. It is a policy redirect, not format repair: malformed or unauthorized plans still fail, the knowledge tool must be allowed, and the same four-Worker combined budget applies. Ordinary credential-configuration questions keep their original read-only queries. Other missing facts are not automatically turned into boundary denials; Reviewer and Synthesizer require direct support for the requested fact or category. No actual private value is queried by this policy path.

The provider's [JSON Output guide](https://api-docs.deepseek.com/guides/json_mode/) documents `json_object`, prompt examples and adequate output length; it also notes that empty content can still occur. Such output must fail closed. These constraints do not establish factual correctness, so live samples and citation review remain required.

## Results

Sampling has not started. The final code SHA, published version/DSL hash, conditions, capture, review, score, failure categories and validation commands will be recorded here after the fixed-version run.

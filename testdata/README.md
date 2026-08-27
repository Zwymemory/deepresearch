# Test data provenance and release boundary

The committed files under `testdata/` are small, synthetic fixtures created for
DeepResearch's deterministic tests and local demo. They are released with this
repository under the project MIT license unless a file explicitly says otherwise.

## Committed fixtures

- `kb/*.md` contains fictional employee-policy, configuration, and context-engineering
  material. It does not contain company-internal documents.
- `kb/ai-agent-notes.pdf` is a minimal synthetic PDF used only to exercise the upload
  and parser path; it contains no personal metadata.
- `eval/*.jsonl` contains hand-authored questions, expected tools, document keys, and
  grounding assertions. `project-knowledge-gold.jsonl` evaluates facts documented in
  this repository rather than external private material.
- `benchmark/` documents the locally authored mini retrieval benchmark.

These fixtures are intentionally small and are not evidence of production accuracy.
Metric claims must identify the exact dataset, denominator, configuration, and run.

## Excluded third-party data

`testdata/open/` and `testdata/eval/open-scifact.jsonl` are generated locally from
BEIR/SciFact downloads and are excluded from Git and public release archives. The
repository distributes conversion and evaluation code, not the upstream corpus.
See [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) for the recorded model and
dataset terms.

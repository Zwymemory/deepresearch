# Contributing

1. Create a focused branch and keep unrelated workspace files out of the change.
2. Copy `.env.example` to `.env`; never commit keys or local datasets.
3. Install Python 3.12 and JDK 21, then run `make test` before opening a pull request. The Make targets create ignored, project-local virtual environments and install the pinned test dependencies automatically.
4. Add deterministic tests for state transitions, authorization, retries, metrics, and data transformations.
5. Do not persist chain-of-thought, raw bearer tokens, or unredacted private evidence.
6. Describe metric definitions and comparison baselines. In particular, do not label hit rate as recall.

Online model evaluation is opt-in because it uses external services and may incur cost. CI must remain offline and deterministic.

The portable career HTML is committed as a release artifact. `make report` validates its source fields, links, row count and privacy constraints with the repository's standard-library validator; it does not depend on a private report-builder installation.

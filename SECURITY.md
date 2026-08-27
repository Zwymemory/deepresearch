# Security policy

DeepResearch is a portfolio and local demonstration system, not a hosted production service.

## Supported version

Security fixes target the current default branch. Historical learning snapshots are not supported.

## Reporting

Do not open a public issue containing credentials, personal data, private document content, or a working exploit. Use GitHub Private Vulnerability Reporting for the repository and include the affected commit, reproduction steps, impact, and suggested mitigation. If private reporting is not enabled on a future mirror, disclose only after the maintainer publishes an explicit private contact channel.

## Security boundaries

- Public API, internal service, and MCP delegation JWTs use different signing keys when the workflow feature is enabled.
- Durable workflows expose only `kb_search`, `web_search`, and `calculator`; file and write tools are excluded at schema and execution time.
- Tool output and model output are untrusted input. Public events contain safe summaries, hashes, and result codes rather than hidden reasoning or bearer tokens.
- Docker Compose credentials are development defaults. A real deployment must use a secret manager, TLS/mTLS, authenticated Elasticsearch, encrypted storage, network policies, and least-privilege database roles.
- LangGraph checkpoints provide recovery, not exactly-once writes. Adding a write tool requires an outbox or downstream idempotency contract.

Run the unit, integration, and secret-scan CI gates before publishing a change. Public
exports must also pass [`scripts/verify-public-release.sh`](scripts/verify-public-release.sh)
and follow [`docs/PUBLIC_RELEASE.md`](docs/PUBLIC_RELEASE.md); never publish the surrounding
monorepo as a shortcut.

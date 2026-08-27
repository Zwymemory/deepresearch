# DeepResearch workflow service

Python 3.12 sidecar for the durable DeepResearch path. Java remains the only public API,
authorization and tool-execution plane. This process claims Java-created workflow rows introduced
in V7 and hardened through V11, runs a checkpointed LangGraph, and asks Java to atomically finalize
the result.

## Graph

```text
Planner -> up to four read-only Workers (max concurrency 2)
        -> Reviewer -> at most one revision round
        -> Synthesizer | INSUFFICIENT_EVIDENCE
```

Each Worker has a separate structured-output model step that may only focus its assigned query
and produce an audit-safe summary; it cannot change tools or create evidence. The original tool
response remains the evidence consumed by Reviewer and Synthesizer. A result is successful only
when at least one citation survives the exact `source_id` allowlist check.

The public citation contract is a compact, unique `citations` array addressed by `[来源N]`.
The resolver accepts that form directly, can deterministically compact markers that address the
full evidence array, and can normalize a provider response whose citation-position table repeats
the same exact `source_id`. Duplicate-position normalization is accepted only when every declared
position is used in first-appearance order, every source belongs to the current evidence set, and
no marker is out of range. Unknown sources, partial duplicate tables, or mappings that require a
semantic guess still fail with `CITATION_VALIDATION_FAILED`. The safe synthesis event records only
counts, the contract result, and the normalization mode; it never logs the answer or source IDs.

For example, suppose the trusted evidence array has six entries and the model uses positions
`1`, `3`, and `6`:

```text
evidence:  [source-A, source-B, source-C, source-D, source-E, source-F]
draft:     Claim A [来源1]. Claim C [来源3]. Claim F [来源6].
published citations: [source-A, source-C, source-F]
published answer:    Claim A [来源1]. Claim C [来源2]. Claim F [来源3].
```

The draft marker `6` means “the sixth entry in the full evidence array”; it is **not** interpreted
as the sixth entry in the already compact three-item `citations` list. The application performs
the positional lookup first and then rewrites markers to the public compact array. A marker such
as `[来源7]` in this six-entry example fails closed with `MARKER_OUT_OF_RANGE`; no source is guessed.

Only `kb_search`, `web_search`, and `calculator` can cross the MCP boundary. Planner,
Reviewer, and Synthesizer have no tools. Checkpoints contain typed workflow state but never
the user bearer token, internal JWT secret, delegation token, or hidden reasoning.

Crash safety uses three independent mechanisms:

1. LangGraph PostgreSQL checkpoints with `durability="sync"`;
2. a dedicated session-level PostgreSQL advisory lock for the complete graph invocation;
3. a random claim token on every progress/finalization write plus durable tool receipts.

A Python `COMPLETED` receipt is a durable result cache. V9 also makes the Java MCP execution
plane consume the deterministic `Idempotency-Key` and persist a separate
`EXECUTING/COMPLETED` result: completed calls replay, while an `EXECUTING` row inherited from an
old claim returns `MCP_RESULT_UNKNOWN` and is not re-executed. This chooses at-most-once safety
for the enabled read-only tools. It is still not exactly-once: a Java crash after the action but
before completion can lose the result and leave the call permanently ambiguous.

Every Java-created run also stores an immutable camel-case `budget` JSON snapshot. The runner
loads that snapshot again on every claim/reclaim and gives it to the claim-bound graph, so a
sidecar restart cannot silently replace the run's task, worker-concurrency, revision, model/tool
call, token, cost, or deadline configuration with newer process defaults. Environment settings
cap that configuration. V10 adds a PostgreSQL reservation ledger: model and tool call counts are
reserved atomically before an external call, ambiguous model attempts remain charged, settled
typed model results can replay, and deterministic tool operation keys are reused across claims.
Finalization reconciles the state with durable call counts and all settled provider usage.
Token and cost cannot be worst-case reserved because their authoritative values arrive only in
the provider response; model calls are serialized, so one already-reserved in-flight call may
cross the remaining token/cost cap before the run stops with `BUDGET_EXCEEDED`.

## Required environment

```bash
export WORKFLOW_DB_PASSWORD='replace-with-a-random-value-of-at-least-32-bytes'
export WORKFLOW_DATABASE_URL="postgresql://deepresearch_workflow:${WORKFLOW_DB_PASSWORD}@postgres:5432/deepresearch"
export JAVA_BASE_URL=http://app:8080
export MCP_URL=http://app:8080/mcp/sse
export DEEPRESEARCH_INTERNAL_JWT_SECRET=CHANGEME_CHANGEME_CHANGEME_CHANGEME
export OPENAI_API_KEY=replace-me
export MODEL_NAME=gpt-5-mini
export MODEL_INPUT_COST_PER_MILLION=1.0
export MODEL_OUTPUT_COST_PER_MILLION=2.0
export MODEL_MAX_ATTEMPTS=2
export MODEL_RETRY_BACKOFF_SECONDS=0.5
```

`deepresearch_workflow` is the least-privilege PostgreSQL role created and kept in sync by
`docker/postgres/001-workflow-role.sh`. It is intentionally different from the Java application's
database owner account. In Compose, set only `WORKFLOW_DB_PASSWORD` in the root `.env`; Compose
constructs `WORKFLOW_DATABASE_URL` for the sidecar. For a standalone process, export both values
as shown above. Never replace the role with the database owner merely to make startup succeed.

`DEEPRESEARCH_INTERNAL_JWT_SECRET` must equal Java's internal workflow secret but must be
different from both the public API JWT secret and MCP delegation secret. For every token
exchange and finalize request the sidecar locally signs a fresh HS256 JWT with:

```text
iss=deepresearch-workflow
aud=deepresearch-internal
sub=workflow-sidecar
exp-iat<=60 seconds
```

No static internal bearer is accepted by the implementation.

Token exchange also carries the current random `claimToken`. The delegated MCP connection sends
`X-Workflow-Run-Id` and `X-Workflow-Task-Id`; Java verifies all three bindings before tool
execution. Run progress, safe events, and tool receipts are claim- and lease-fenced.

The two cost rates are CNY per million tokens and must be updated for the selected provider.
When an OpenAI-compatible provider omits usage metadata, the sidecar uses a conservative UTF-8
size estimate instead of recording zero tokens, so token and cost gates remain active.

### DeepSeek structured output

When `OPENAI_BASE_URL` points to `api.deepseek.com` (or `MODEL_NAME` starts with
`deepseek`), the adapter uses a provider-specific boundary:

- thinking is disabled because DeepSeek rejects LangChain's forced structured-output
  `tool_choice` while thinking is enabled;
- every Planner, Worker, Reviewer, and Synthesizer result is returned through one forced
  `function_calling` response with `strict=true`, then validated again by Pydantic;
- `max_tokens` is sent as a DeepSeek root request field instead of ChatOpenAI's
  `max_completion_tokens` alias;
- SDK retries are disabled, so one durable model reservation corresponds to one provider
  request. The application defaults to at most two persisted attempts: a failed or ambiguous
  attempt first becomes `UNKNOWN` and remains charged against the model-call-count budget before
  the next attempt is created. Unknown token usage and provider billing are not fabricated.
- Only timeout, rate-limit, schema and transient provider failures are retried. Non-transient
  provider errors are finalized immediately with a stable error code. This accounting does not
  prove that a provider avoided duplicate execution or billing after an unknown result.

Do not log raw model content or parser messages. A structured-output failure records only
bounded metadata such as schema name, parser exception type, finish reason, content state,
and tool-call count. Follow the safe lifecycle log with:

```bash
docker logs --follow deepresearch-workflow
```

## Run and verify

Java must apply migrations V7 through V11 before the runner starts; V8 establishes the restricted
sidecar role, V9 closes Java MCP call-id consumption, V10 adds durable budget reservations, and
V11 pins the fencing trigger `search_path` and schema-qualifies `public.agent_workflow_run`. This
prevents the restricted sidecar from bypassing child-row claim fencing with a temporary shadow
table; the behavior is covered by a Testcontainers integration test.

```bash
python -m pip install -e '.[test]'
ruff check src tests
pytest --cov
uvicorn deepresearch_workflow.app:app --host 0.0.0.0 --port 8091
curl --fail http://127.0.0.1:8091/internal/health/ready
```

The PostgreSQL locking/checkpoint integration suite uses a disposable database:

```bash
TEST_DATABASE_URL=postgresql://workflow:workflow@127.0.0.1:55432/workflow \
  pytest -m integration -vv
```

Current verification covers static checks, isolated graph/model/security tests, real PostgreSQL
lease/fencing/session-lock behavior, synchronous LangGraph checkpoint persistence, image build,
and liveness/readiness smoke tests. The local acceptance path has also completed real
Java + MCP + DeepSeek runs through Planner, concurrent Workers, Reviewer, and Synthesizer.
Paid-provider checks remain excluded from default CI. The remaining environment acceptance
gate is a live kill/restart test covering both receipt layers: completed replay and the deliberate
`MCP_RESULT_UNKNOWN` outcome when Java inherits an old `EXECUTING` row. The latter is at-most-once
safety with possible result loss, not an exactly-once claim.

For a configuration/health smoke test without PostgreSQL or a model provider:

```bash
RUNNER_ENABLED=false MODEL_PROVIDER=disabled uvicorn deepresearch_workflow.app:app --port 8091
```

The service intentionally has no public workflow creation endpoint. Use Java
`POST /api/research/workflows` with an `Idempotency-Key`; poll its status or consume its
durable SSE endpoint there.

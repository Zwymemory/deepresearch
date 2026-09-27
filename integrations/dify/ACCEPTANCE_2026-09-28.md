# Dify integration acceptance handoff — 2026-09-28

## Scope and current gate

The unified starting point is merge commit `6e0e146` (Dify `42fcc7a` plus RAGFlow `9a984c6`). This change adds final citation revalidation, durable remote stop tracking, a bounded legacy evidence verifier, and Compose opt-in variables. The default workflow engine remains `langgraph`. The RAGFlow evaluator owns the subsequent live comparison and fault run; this file records the Java-side checks completed before that handoff.

## Safety invariants

- Java accepts a Dify success only when the answer markers and at most five unique citation IDs match this run's completed tool receipts. It checks each cited source against the current local document state and RAGFlow chunk API within an eight-second overall deadline. Failure clears the answer and citations and records `CITATION_SOURCE_UNAVAILABLE`.
- A local cancellation or deadline closes the Java run and revokes its grant immediately. A separate V15 row tracks the remote stop request, a 30-second claim lease, bounded retries, and the last error. A stop API 2xx yields `REQUESTED`; only a remote detail status of `stopped` yields `CONFIRMED_STOPPED`. The API view exposes `remoteStopState` so operators can distinguish those states. An unknown workflow dispatch is never sent again automatically.
- The legacy evidence verifier admits at most eight model calls, uses four bounded worker threads, and has a 15-second overall deadline. Exhaustion or model failure causes the existing rerank coordinator to return no evidence.
- RAGFlow document mappings require both `sync_status='DONE'` and local document `status='DONE'`; the client closes a response stream when its read deadline expires.

## Commands and results

| Command | Result |
| --- | --- |
| `mvn -q test` | Pass; all standard unit suites completed. |
| `python3 integrations/dify/verify_workflow.py` | Pass; Dify DSL graph and Evidence v1 contract checks. |
| `mvn -q -Dapi.version=1.44 -Pintegration verify` | Exit 0; 61 XML suites, 267 tests, zero failures and zero errors. Flyway applied V1–V15 to disposable PostgreSQL containers. |
| `docker compose config --services` | Pass; app, workflow, PostgreSQL, Elasticsearch, and reranker services resolve. |
| `git diff --check` | Pass. |

Docker Desktop 29.4.3 requires a Docker API version of at least 1.40. The bundled Testcontainers client initially received HTTP 400 during Docker detection; the Maven system property `-Dapi.version=1.44` resolved that local compatibility issue. The integration log emitted an existing Surefire fork shutdown warning after `System.exit(0)`, but the command returned 0 and the report XML contains no failures or errors.

## Remaining live evidence

The code checks above do not establish a fresh full-service run. The live acceptance pass must record positive and no-evidence answers, SSE reconnect, cancel and Java restart, Dify outage and deadline, duplicate tool receipt behavior, and citation invalidation after document removal. Keep the actual commands, timestamps, outcomes, and any unverified cases in the showcase report. Do not treat a local `CANCELLED` status or a Dify stop HTTP 2xx as proof of `CONFIRMED_STOPPED`.

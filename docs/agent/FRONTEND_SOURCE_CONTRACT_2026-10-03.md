# Frontend source metadata contract

Date: 2026-10-03. Backend baseline: `9b3a0fed79b3b19603b28d8e35ad63c3d35b0f1b`. Branch: `feat/frontend-source-contract`.

This change adds `citationDetails` to ordinary LangGraph workflow results and native Single Agent responses. It preserves `citations`, source identifier spelling, answer markers, statuses, and the existing `INDEXED_V1` validation. It introduces no migration or new endpoint. The evidence read API is a separate delivery.

## Ordinary workflow API

Authenticated `GET /api/research/workflows/{runId}` returns the existing `WorkflowDtos.View`. The new data is under `finalResponse.citationDetails`, saved at the first successful Java finalization. Each item corresponds to the same-position `citations` entry; `[来源N]` still refers to `citations[N - 1]` under `INDEXED_V1`.

```json
{
  "sourceId": "https://example.org/limits",
  "kind": "WEB_SEARCH_SNAPSHOT",
  "title": "Synthetic public source",
  "url": "https://example.org/limits",
  "excerpt": "Synthetic search excerpt.",
  "metadataStatus": "AVAILABLE",
  "unavailableReason": null
}
```

| Field | Meaning |
|---|---|
| `sourceId` | Exact public citation identity; do not substitute a URL or derive a new ID. |
| `kind` | `WEB_SEARCH_SNAPSHOT`, `KNOWLEDGE_CHUNK`, or `UNKNOWN` when metadata is unavailable. |
| `title` | Bounded, redacted title from typed retrieval; `null` means not recorded. |
| `url` | Validated HTTP(S) link from a web retrieval snapshot; `null` for KB chunks or unavailable metadata. No KB URL is invented. |
| `excerpt` | Bounded, redacted retrieval excerpt; `null` means not recorded. A web search snippet is not an original page read. |
| `metadataStatus` | `AVAILABLE` means the source metadata has a trusted retrieval provenance. It does not establish factual accuracy or claim support. `UNAVAILABLE` means this projection cannot supply it. |
| `unavailableReason` | `MISSING_SNAPSHOT`, `AMBIGUOUS_SNAPSHOT`, or `SNAPSHOT_LIMIT`; otherwise `null`. |

Unavailable items retain only the citation ID and availability fields; `kind` is `UNKNOWN`, and `title`, `url`, `excerpt` are JSON `null`. `MISSING_SNAPSHOT` covers old receipts, unrecognized/malformed metadata, absent receipts, unmatched identities, rejected links, and sources not present in this exact run. The public result deliberately does not reveal which other run might contain a source. A calculator result also has no retrieval snapshot.

Identical repeated snapshots are deduplicated. Different snapshots for the same source ID produce `AMBIGUOUS_SNAPSHOT`; no latest-version assumption or automatic adjudication is made. This includes changed excerpts of one document. Finalized metadata remains frozen even if another receipt later appears. A terminal retry uses the stored snapshot and original fingerprint; historical retries retain their original response shape for fingerprint comparison.

Historical final responses with citations but no `citationDetails` receive an in-memory unavailable projection on GET. GET does not read receipt snapshots to backfill old runs, mutate stored JSON, retrieve documents, or call a model. An unfinished workflow without a final response still has no source-detail projection. Existing statuses and `insufficientEvidence` retain their meaning; metadata absence alone does not change terminal status. Ordinary `INSUFFICIENT_EVIDENCE` keeps its existing `NONE` citation contract even if an old result carries citations.

## Capture and trust boundaries

`WebSearchTool` and `KnowledgeBaseSearchTool` capture metadata directly from their typed search/retrieval results while executing an already-authorized tool call. `CitationAwareToolOutput.sourceSnapshots` keeps it separate from model-visible observation text. No title, link, or excerpt is accepted from a finalize request or inferred from a model response.

MCP adds `sourceSnapshots` to its response and preserves its existing evidence envelope and ID parser. Its KB evidence IDs continue to use the raw chunk key; the native runtime continues to use `kb:` plus that chunk key. A snapshot is attached only if its typed identity is represented by the exact MCP receipt. Mismatches degrade to unavailable rather than changing citation IDs. Existing source-ID truncation in the Python tool client can also leave a long identifier unmatched; this delivery does not change the frozen Python envelope.

Java persists these snapshots in V9's existing `mcp_safe_result`. The existing execution admission, exact run/task/tool/fingerprint binding, claim fencing, and grant/scope checks still govern completion. The sidecar cannot write the Java receipt columns. Finalization reads only completed Java receipts for the exact run and stored owner, checks the tool and evidence/snapshot identity, and ignores the sidecar's `safe_result`. Completed receipts from a preceding execution claim survive recovery in the same run; incomplete or rejected Java calls supply no metadata.

Bounds: at most 10 typed snapshots per retrieval operation; titles at most 300 Unicode code points, excerpts at most 2400. Web excerpts retain the tool's existing roughly 300-character preview; legacy hybrid KB excerpts retain its roughly 420-character preview. Finalization considers at most 256 completed retrieval receipts; a 257th yields `SNAPSHOT_LIMIT` for every cited source. Existing database response-size limits remain in force. Native scopes similarly fail closed after 256 captured snapshots. Public workflow citation count remains at most 32. No raw provider response, query, prompt, credentials, claim token, content digest, or internal receipt identifiers are added to the public detail item.

## Capabilities by mode

| Mode | Where details are returned | Provenance and limitations |
|---|---|---|
| LangGraph workflow | `WorkflowDtos.View.finalResponse.citationDetails` | New bounded typed retrieval snapshots in Java receipts. Web search preview or KB retrieval excerpt. Explicit per-source availability; old runs degrade. |
| Dify workflow | Same existing workflow GET | Existing `WEB_SEARCH_SNAPSHOT` / `KNOWLEDGE_CHUNK` details are preserved as stored, including web `retrievedAt`. This delivery does not add availability fields to those items. KB details may be a subset of citations and may omit `url`. Unmatched IDs mean unavailable. |
| Autonomous research | Same existing workflow GET, for runs created via `/api/research/agents` | Existing sealed publication details remain unchanged: `WEB_ORIGINAL` / `KNOWLEDGE_CHUNK`, with quotations tied to the publication proof. KB URL may be `""`. Existing `claims`, `report_status`, `unfinished_goals`, and `semantic_truth_guaranteed` remain authoritative for their own contract; this change adds no claim-level verification. |
| Single Agent, `native-tool-calling` | Top-level `AgentResearchResponse.citationDetails` in POST `/api/research/agent` and its existing SSE result | Bounded typed metadata captured in the existing thread-local run scope, reordered to the final compacted citations. The same availability fields apply. The existing idempotency response snapshot can replay this DTO; there is no new generic GET-by-run history API. |
| Single Agent, `manual-react` | Same DTO, empty `citations`, `citationDetails`, and `NONE` contract | This legacy runtime returns formatted text and does not retain a typed source registry for final citations. No metadata is inferred from its text. It remains unsupported for source inspection. |

The general `agent_run`/step/event tables do not persist native citation snapshots. Extending durable native history would need a separate owner-scoped storage/read contract. The smallest manual-ReAct follow-up is to retain typed retrieval identities/snapshots per run and validate/compact the final markers before publishing structured citations, then decide whether to persist them. Neither lifecycle change is part of this delivery.

## Frontend handling and fixtures

Match details by exact `sourceId` across all modes; do not assume Dify's array has one item per citation. Render titles/excerpts as plain escaped text. Display unavailable metadata separately from factual uncertainty. Only a populated, safe `url` supplies a navigation target; do not treat KB IDs as URLs. Treat absent availability fields in existing Dify/autonomous items as the established mode-specific contract, not as fabricated `AVAILABLE` flags. Missing fields, absent items, `null`, and blank URLs are unknown/unavailable.

No publication, update, factual applicability, or original-read timestamp is added. A recorded Dify `retrievedAt` is retrieval completion time, not the date a claim became applicable. Run `createdAt`/`updatedAt` are workflow record times. Ordinary snapshots do not expose receipt timestamps as source dates.

Sanitized examples in [fixtures/frontend-sources](fixtures/frontend-sources) use synthetic documents and reserved example domains:

- `langgraph-available.json`, `langgraph-historical.json`, `langgraph-ambiguous.json`, `langgraph-insufficient.json`: `finalResponse` extracts.
- `dify-existing.json`: existing Dify `finalResponse` extract, including a correctly derived synthetic snapshot ID.
- `autonomous-source-extract.json`: existing source-related fields only; it is not a complete sealed report or publication proof.
- `native-single-agent.json`, `manual-react-existing.json`: synthetic synchronous Single Agent DTO examples. Usage is unknown in these illustration fixtures.

These are contract examples, not live acceptance evidence or a frontend implementation. This task has not merged the frontend or the separate evidence API.

## Offline verification

Relevant tests cover actual HTTP/JWT/Flyway/PostgreSQL/JSONB serialization and replay; exact run/owner isolation; foreign-run exclusion; sidecar-only/incomplete receipt exclusion; recovery across execution claims; GET immutability and absence of provider calls; duplicate/conflicting/missing snapshots; safe links and KB-without-URL; typed metadata separated from model-visible text; native final citation order and scope cleanup; old MCP/native deserialization; existing autonomous publication and Dify adapter behavior.

Run with a locally available Python environment containing the repository's test dependencies:

```sh
AGENT_PYTHON=/path/to/offline-python PYTHONDONTWRITEBYTECODE=1 \
  mvn -o -q -Dapi.version=1.40 \
  -Dtest='*Test,WorkflowCitationDetailsHttpIT,McpKnowledgeLoopIT,AgentHttpPostgresIT' test
/path/to/offline-python -B contracts/agent/v0/validate.py
git diff --check
```

Tests use disposable databases and fixture-backed transports. Maven is used for compilation/tests only. No package, production Spring integration, paid retrieval/model calls, live acceptance, deployment, or remote publishing is performed. Final/tested commit IDs and verification totals are recorded in the coordinator delivery receipt, outside public documentation, to avoid self-referential commit identifiers.

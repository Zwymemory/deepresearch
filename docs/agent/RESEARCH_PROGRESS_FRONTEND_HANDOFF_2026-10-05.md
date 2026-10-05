# Research Progress Memory: Backend Handoff to Claude

Date: 2026-10-05

## 1. Delivery and scope

The initial backend progress-memory implementation is ready for frontend integration on branch `feat/research-progress-memory-mvp-20261005`, based on `cdbb89bed829851eaa32f23c75ad603a1b86438e`.

Backend checkout: `/Users/zwy/Claude/Projects/deepresearch-memory-mvp`.

Frontend checkout: `/Users/zwy/Claude/Projects/deepresearch-github/.claude/worktrees/deepresearch-frontend-redesign-1bb438`.

Continue from Claude's frontend commit `39ddfef` or newer work. Preserve the existing visual identity, themes, stage-based layout and Motion interactions. This assignment connects the existing notebook; it does not restart the redesign or authorize another milestone.

This document supersedes the memory-contract blockers in `docs/frontend/R1_R2_2026-10-05.md`: project discovery, a bounded saved-progress list, request/response examples and new-session loading are now specified and implemented. It does not close the other outstanding backend/frontend integration items.

The backend branch is separate from the frontend branch and is not automatically deployed. Port 8090 remains the synthetic preview service. Do not assume an existing process on port 8080 contains this change. Codex owns backend integration, configuration and migrations; Claude must not copy backend source into its branch or change Java, database, authentication, packaging or CI files.

**What this version does:** explicitly save server-derived research progress, retrieve it after a service restart or in a different session, list accessible saved records, load project context into a newly created session, and delete a saved snapshot.

**What it does not do:** automatically supply that context to the model, rewrite queries, verify old facts again, perform semantic recall, continue a research run, or coordinate agents. Loading context must not be advertised as model consumption. The history remains untrusted prior context.

## 2. Authentication and supported runs

All routes below use the existing `Authorization: Bearer <user API token>` flow. Reuse M2's identity handling. No new API key, privileged credential or provider key belongs in the browser.

The initial version supports autonomous research runs represented in `agent_research_run`. It does not support ordinary LangGraph/Dify workflow runs or synchronous Single Agent results. The discovery route returns 404 for an unsupported or inaccessible run. Keep their save action unavailable with a suitable explanation; do not create a project on the client.

The authenticated tenant, owner, project and source run must agree. A different tenant or owner receives 404, including an ADMIN identity belonging to another owner. Invalid or missing bearer tokens receive 401. Global listing returns only the current identity's accessible records.

## 3. Endpoint contract

Paths are relative to the Java API origin. All requests in this version have **no JSON body**. Path identifiers and query values must be URL-encoded. Successful operations return HTTP 200 with JSON.

| Action | Method and path | Response |
| --- | --- | --- |
| Discover the project for an owned autonomous run | `GET /api/research/agents/{runId}/progress-project` | `{ "project_id": "...", "run_id": "..." }` |
| Save or refresh progress from server records | `PUT /api/research/projects/{projectId}/progress/runs/{runId}` | `research-progress/1` snapshot |
| Read a saved snapshot | `GET /api/research/projects/{projectId}/progress/runs/{runId}` | Same snapshot shape; does not save implicitly |
| Browse saved progress across the current owner's projects | `GET /api/research/progress` | `{ "schema_version": "research-progress-list/1", "items": [...], "candidate_limit": 20 }` |
| Load project context into a new session without starting research | `POST /api/research/projects/{projectId}/resume-context` | `research-resume-context/1`, including a server-created `target_session_id` |
| Reload context for an existing eligible session | `GET /api/research/projects/{projectId}/resume-context?sessionId={targetSessionId}` | Same resume-context shape |
| Delete the saved snapshot | `DELETE /api/research/projects/{projectId}/progress/runs/{runId}` | `{ "run_id": "...", "deleted": true }`; repeated deletion returns `false` |

### Discovery and saving

Use the existing run ID from the accepted run/status response, discover its `project_id`, and then save. Never derive project IDs from session IDs or fabricate them from UUIDs.

Saving reads current server records, not browser-authored summaries. Repeated saving of unchanged data preserves the single snapshot and its storage timestamp. If the research has progressed, another explicit save refreshes that snapshot. This is one snapshot per project/run, not an append-only version history.

Saving a running or failed autonomous run is allowed. The saved `run_status` and unresolved work retain that state; a successful save does not make the research successful.

### Listing and availability

Listing considers the latest 20 saved candidates across the owner’s projects, ordered by database `saved_at` descending and then run ID. It removes records whose provenance is no longer accessible/current. It does not backfill from older candidates, return a total count or provide pagination. An empty list means no accessible records in this bounded window, not proof that no historical rows exist.

`saved_at` is currently a database ordering field, not a public payload field. Do not invent a saved date, reuse the original run's date as the save time, or retain the preview's fictional timestamp.

Use `(project_id, source_run_id)` as the notebook record identity. The response has no separate notebook UUID.

### Loading and sessions

The POST creates an owned, empty session and returns accessible saved context for the selected project. It does not create a workflow, make model/tool calls, or bind a project to future research. It returns up to the latest 20 project candidates after filtering. **It is project-level loading, not loading only the clicked run**: show the actual returned records and their count. Do not claim that only one record was loaded when several were returned.

This POST is not idempotent: another explicit POST creates another empty session. Disable duplicate clicks and do not automatically retry POST after an ambiguous network failure. If its response was received, use the returned session ID with GET for refresh/recovery. Do not call POST from mount, reload or reconnect handlers.

The GET requires an existing session owned by the caller and not associated with a different research project. It excludes snapshots originating from that target session. It creates nothing. The returned context is computed at read time; it is not a permanently frozen copy attached to the session.

Suggested UI confirmation: `已载入历史研究进度（尚未传入模型；未开始研究）`. Clear loaded context and pending responses on identity changes. Do not auto-submit the composer or silently add a new field to the research-create request.

### Deleting

Deletion removes only the saved progress row. It does not delete the original run, evidence, session or browser-local recent-run history. A second delete of an already removed snapshot returns 200 with `deleted: false`, provided the original run is still owned and accessible. Both outcomes allow removal from the displayed notebook. Clear any loaded browser copy referencing that record.

## 4. Payload meanings and UI mapping

Exact, synthetic responses captured through the real HTTP/JWT stack are checked in beside this document under `fixtures/research-progress/`:

- `project.json`: project discovery.
- `saved.json`: save/read snapshot for a failed run with contested claims.
- `list.json`: saved-progress list.
- `resume.json`: context and a newly created target session.
- `deleted.json`: deletion result.

These are controlled test records, not production research or credentials. Their IDs are illustrative. The real source directory is `/Users/zwy/Claude/Projects/deepresearch-memory-mvp/docs/agent/fixtures/research-progress/`.

| Snapshot field | Meaning / frontend treatment |
| --- | --- |
| `schema_version` | `research-progress/1`; validate the supported shape at the adapter boundary. |
| `context_kind`, `trusted_as_evidence` | Always `prior_progress` and `false`. Preserve the history warning. |
| `project_id`, `source_run_id`, `source_session_id` | Provenance and navigation identifiers. Not display titles or URLs. |
| `original_goal` | Notebook heading/original research question. |
| `run_status` | Status at explicit save time, not a live run-status subscription. |
| `completed_work` | Goal objects with completion proof and accessible adopted evidence at save time. This is not a fresh factual revalidation. |
| `unresolved_questions` | Goal objects, including original-run gaps. They must remain unresolved in the UI. |
| `next_steps` | Deterministic string array; not a newly generated autonomous plan. |
| `source_evidence` | IDs and snapshot hashes: `evidence_id`, `receipt_id`, `snapshot_sha256`, `source_id`. No guaranteed title, quote, URL or report citation number here. |
| `source_claims` | `claim_id`, `record_sha256`, `decision_status`, `freshness`; preserve contested/unverified states. |

Goal objects contain `goal`, `status`, `completion_verified`, `gaps`, and optionally `task_id`, `criteria`, `error_code`. Run-level gap objects omit `task_id` and `criteria`; use defensive adapters. Nested criterion records retain the existing camelCase fields `criterionId`, `checkIds`, `claimIds`, alongside `text`, `status`, `gaps`. Do not blindly map all nested names to snake_case.

The resume response has `schema_version: research-resume-context/1`, `context_kind`, `trusted_as_evidence`, `project_id`, `target_session_id`, `progress` (snapshot array), and `usage_instruction`.

The server checks saved evidence availability/freshness/snapshot hashes and saved claim states against current records when reading, listing and loading. If those references no longer match, direct read returns 404 and aggregate reads omit that snapshot. This protects against reusing stale progress; it does not certify that retained claims are true.

## 5. Errors and recovery

| HTTP status | Meaning and handling |
| --- | --- |
| 401 | Missing, invalid or expired API identity. Reuse the existing authentication flow and clear identity-scoped cached data. |
| 404 | Unknown, unsupported, inaccessible or provenance-invalid record/project/run; also an ineligible target session. Do not distinguish hidden ownership from absence. |
| 400 | Missing required `sessionId` for GET, or malformed request. Do not retry automatically. |
| 413 | Snapshot exceeds the service's 60,000-byte canonical JSON limit. Show that this record cannot be saved in the initial version; do not silently truncate it. |
| 5xx / network failure | Preserve an error state, never substitute preview success. PUT may be explicitly retried; treat new-session POST as described above. |

There is no new stable memory-specific error JSON envelope in this version. The security filter returns its existing JSON errors; other error bodies may vary with Spring error handling. Branch on HTTP status, tolerate non-JSON bodies, and do not parse Chinese reason strings as machine codes.

## 6. Integration sequence for Claude

1. Add typed notebook adapters using these endpoints; preserve M2's live/demo and identity isolation behavior.
2. In live mode, load the notebook list only when needed. Use project discovery to determine save eligibility for an autonomous run. Reconcile the list after save/delete succeeds.
3. Replace preview-only timestamp assumptions and shapes with the actual payload mapping above. Keep the explicitly selected synthetic preview separate.
4. Connect project-context loading to the new-session POST, with explicit user action and clear wording. Retain the returned session ID for GET recovery. Do not imply model use.
5. Run focused frontend checks for save/read/list/load/delete, errors, identity switching and no accidental research creation. Preserve both themes and reduced-motion behavior.
6. Report what was tested against a real memory service versus a mock, record remaining environment dependencies, and stop.

A deployed Java service must contain this branch's controller/service/repository and Flyway migration `V23__research_progress_memory.sql`. No new provider credentials or model calls are needed by the memory routes. For full-application startup, use the repository's established backend configuration managed by Codex; do not alter shared databases or apply migrations from Claude.

Use the existing Vite `DEEPRESEARCH_API_PROXY` setting only with a confirmed Java service origin. Do not treat the 8090 mock as backend acceptance. The isolated acceptance server described below shuts down after the test; it is not a persistent preview URL.

## 7. Verification performed by Codex

On 2026-10-05, this command passed with **2 tests, 0 failures, 0 errors, 0 skips**:

```bash
cd /Users/zwy/Claude/Projects/deepresearch-memory-mvp
mvn -o -Pintegration -Dtest=NoTests \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ResearchProgressMemoryIT verify
```

- Disposable PostgreSQL with all 23 Flyway migrations, real repositories and transactions.
- Service reconstruction against the retained database: progress survives and can be read from another session.
- Unchanged save idempotency, deletion, unavailable-reference filtering, valid completion proof, and failed/contested/unverified status preservation.
- A loopback embedded Tomcat server using the actual memory controller, production `SecurityConfig`, JWT issuer/filter and database; requests made through Java's real HTTP client.
- Project discovery, saved-progress listing, save/read, new-session context loading, GET reload and delete tested over HTTP.
- Missing/invalid JWT, tenant/owner isolation, missing session parameter and no workflow creation during loading checked.
- Build/package completed. No paid API requests, no production-data edits, no frontend changes, no deployment or push.

The security test substitutes the unused MCP delegation collaborator; no model, search provider, workflow worker or complete production Spring application is started. These are bounded real HTTP/database tests with synthetic research records, not full frontend-to-backend acceptance or a live research benchmark.

## 8. Items still outside this handoff

- Claim-to-report-citation mapping remains unavailable: no per-claim badge beside `[来源N]` without a reliable mapping.
- Additional report citation dates, Spring `/app/` packaging and CI remain separate tasks.
- Existing Round 1 research acceptance limitations remain unchanged.
- Model consumption of loaded memory, automatic continuation, semantic recall and multi-agent collaboration are deferred.

After delivering this initial notebook integration, stop. Do not expand into these items automatically.

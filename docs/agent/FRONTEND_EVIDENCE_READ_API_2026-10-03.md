# Public evidence read API — 2026-10-03

Baseline: `9b3a0fed79b3b19603b28d8e35ad63c3d35b0f1b`. This additive backend change uses existing tables (V17–V21); no migration, adjudication change, execution API change, or frontend change is required.

## Request and authorization

`GET /api/research/workflows/{runId}/evidence`

Use the existing public user Bearer JWT. Anonymous access, invalid JWTs, internal service JWTs, and MCP delegation JWTs receive 401. The controller requires `AuthPrincipal` even if legacy user headers are enabled elsewhere. `WorkflowService.ownedRun` returns 404 for an unknown or foreign run. There are no supported ownership, project, run-selection, pagination, or execution-token query parameters; extra parameters cannot change scope.

After owned-run authorization, tenant and owner come from the principal; project comes from the exact stored `agent_research_run`. Every child query includes tenant, owner, project, and run. The read transaction uses PostgreSQL repeatable-read and read-only mode, does not lock the run for execution, and remains usable after terminalization, lease expiry, or grant revocation. Successful and integrity-error responses use `Cache-Control: no-store`.

The endpoint performs database reads only. It neither calls the publisher nor refreshes sources, uses model transport, reserves budget, mutates state, or emits events.

## Availability and limits

The schema is `evidence-view/1`. These are inspection states, separate from run status and factual decision status:

| `availability` | Meaning |
|---|---|
| `UNSUPPORTED_MODE` | Owned LangGraph or Dify workflow has no autonomous evidence projection. Legacy single-agent endpoints are outside this durable-run API. |
| `NO_RECORDS_YET` | Supported agent run has no evidence records, source-read receipts, checks, or blocked attempts. |
| `RECORDED_INCOMPLETE` | Recorded work has pending/failed reads, awaiting checks, unresolved blocked attempts, or no completed claims. |
| `AVAILABLE` | Projected checks/records are inspectable, with no recorded unfinished check/read/block in this bounded view. This can include contested or insufficient outcomes. It does not assert completion of research goals. |
| `BOUNDED_OUT` | A quantity/byte ceiling was exceeded. All data arrays are empty and `completeProjection=false`. This is not an empty-research or no-conflict result. |

Every response includes limitations: recorded observations only, empty arrays do not prove no conflict, model relations do not guarantee truth, publication requires its separate seal and finalization, no source refresh, and no raw snapshots/model rationales. Treat these as persistent UI semantics, not transient errors.

There is no partial-page projection: at most 256 stored record versions (including unprojected derived packets/challenges), 128 checks, 128 source-read receipts, and 128 blocked attempts. Bounded SQL queries fetch limit+1 in stable identity order. Stored verification input is capped at 2 MiB; finalized-seal verification also has a 2 MiB ceiling. Final public JSON serialization is capped at 262144 bytes. Exceeding any ceiling returns `BOUNDED_OUT`, rather than hiding inconvenient relations in a truncated list. A larger-run pagination contract is deferred.

Titles over 256 Unicode codepoints are unavailable (`null`). Source URLs over 2048 codepoints are unavailable. Bound quote text over 1000 codepoints is omitted, with `textAvailability=OMITTED_SIZE_LIMIT`; its full quote hash and exact codepoint start/end remain available. Text is never silently shortened under an unchanged hash. Claim text is bounded by the existing 4000-codepoint contract. Conditions preserve at most 20 stored entries, each within the existing 1000-character bound. Unsupported record shapes fail closed.

## Typed projection

All response DTOs are explicit Java records, without `JsonNode`, `Map`, or arbitrary internal-record passthrough.

| Array / field | Allowed contents |
|---|---|
| `evidence` | Record identity/type/version/hash/DB recorded time; source identity, kind (`knowledge` / `web`), bounded title, safe public URL, grounded publication-date tag, observed time, snapshot hash, applicability. |
| `claims` | Committed claim identity, text, kind, applicability, recorded decision status, originating check, latest-round flag, publication membership, verified quote-bound links. |
| `decisions` | Decision identity, claim ID, status, policy version, adopted/unresolved IDs, dismissed IDs, fixed gap codes. Free-form rationale/dismissal reasons are omitted. |
| `checks` | Check/investigation identity, round, parent, status, request hash, stored creation/completion times, latest-round flag, request-bound claim and evidence identities. Pending claim prose is omitted. |
| `disagreements` | A contested committed claim and decision with verified supporting and refuting **unresolved** links to different source snapshots. This records a relation; it does not decide which source is true. |
| `blockedAttempts` | Attempt/investigation identity, round, allowlisted capacity error, payload hash, recorded time, and `resolvedByLaterCheck`. Candidate claim text is omitted. |

Claim links retain `relation` (`supports`, `refutes`, `insufficient`) separately from `disposition` (`adopted`, `unresolved`, `dismissed`). A dismissed out-of-scope supporting proposal is not a recorded disagreement. An insufficient claim does not create a disagreement merely because it has a gap or challenge. Historical claims/checks remain visible and carry `latestRecordedRound`; later supplements do not erase earlier observations.

`identity.recordedAt`, check times and blocked-attempt times are database recording times, serialized as UTC instants. `observedAt` is the stored read observation time. Neither proves a document's publication date or effective time. `publishedAt` and applicability `validAt` retain explicit known/unknown tags; unknown values stay null. Claim applicability describes the requested scope; evidence applicability describes the recorded source declaration. Unknown-tag free-form reasons are omitted. No dates are inferred from creation, retrieval, or observation time.

Knowledge sources have `url=null`; dataset/document/chunk locators and private paths are excluded. Web URLs must be absolute HTTP(S) URLs with a dotted DNS hostname, no credentials, query, fragment, explicit port, numeric/IP hostname, localhost or `.local`/`.internal`/`.localhost` suffix. Rejected URLs become null, never fabricated alternatives. This conservative display policy does no DNS lookup; it is not a fetch allowlist. Render titles, claim text, conditions and quote text as ordinary text, not HTML.

Raw source snapshots, unrelated paragraphs, prompts, model responses, reasoning/rationales, execution claim tokens, bearer credentials, owner/tenant/project identity, raw locators, and research packet bodies are excluded. Only original text within an existing verified claim quote is eligible for excerpt display.

## Integrity and publication

Before projection, stored record payload SHA-256, embedded scope/type/identity/schema/version, snapshot hash, receipt-record equality, receipt metadata hashes/source identity/requested candidate, and parent receipt binding are checked. Check request hashes, request evidence equality, parent round/investigation linkage, completed-result equality with committed records, originating claim spec, claim-to-decision linkage, disposition membership, and Unicode quote range/text/hash are verified. Foreign/missing records are never joined by unscoped IDs. A mismatch returns:

```json
{"errorCode":"EVIDENCE_VIEW_INTEGRITY_INVALID"}
```

HTTP 409 contains no failing raw record or private locator. This validates persisted structure; it does not rerun adjudication or independently validate model semantics.

View `publicationState` is `RECORDED_ONLY`, `FINALIZED_REPORT`, or `NOT_ASSESSED` for unsupported or bounded-out responses. Claim membership is `RECORDED_ONLY` or `IN_FINALIZED_REPORT`. Membership requires a terminal SUCCEEDED/INSUFFICIENT_EVIDENCE run whose stored public final answer, citations and claims match an existing completed publication seal, with matching answer hash, approved proof, and exact committed claim/decision payloads. Completed checks, supported labels and adopted links alone never grant membership. A seal that has not been finalized does not grant membership. Finalized reports may include disputed/insufficient sections; membership is not an unconditional factual approval of those claims. The view never returns any answer prose and does not mint publication eligibility or reevaluate current goal coverage.

## Sanitized response examples

Complete empty-agent response (synthetic run identity):

```json
{
  "schemaVersion":"evidence-view/1",
  "runId":"run-demo",
  "runStatus":"QUEUED",
  "availability":"NO_RECORDS_YET",
  "publicationState":"RECORDED_ONLY",
  "limits":{"records":256,"checks":128,"sourceReads":128,"blockedAttempts":128,"responseBytes":262144,"completeProjection":true},
  "limitations":["RECORDED_OBSERVATIONS_ONLY","EMPTY_DOES_NOT_PROVE_NO_CONFLICT","MODEL_RELATIONS_ARE_NOT_TRUTH_GUARANTEES","PUBLICATION_REQUIRES_EXISTING_SEAL_AND_FINALIZATION","NO_SOURCE_REFRESH","NO_RAW_SNAPSHOTS_OR_MODEL_RATIONALES"],
  "evidence":[],"claims":[],"decisions":[],"checks":[],"disagreements":[],"blockedAttempts":[]
}
```

Selected fields from an available recorded conflict (identity hashes omitted from this excerpt; real responses always include full identities):

```json
{
  "availability":"AVAILABLE",
  "publicationState":"RECORDED_ONLY",
  "claims":[{
    "text":"API limit hypothesis",
    "decisionStatus":"contested",
    "checkId":"check-demo",
    "latestRecordedRound":true,
    "publicationState":"RECORDED_ONLY",
    "evidenceLinks":[
      {"evidenceId":"evidence-0","evidenceVersion":1,"relation":"supports","disposition":"unresolved"},
      {"evidenceId":"evidence-1","evidenceVersion":1,"relation":"refutes","disposition":"unresolved"}
    ]
  }],
  "disagreements":[{
    "claimId":"claim-demo","decisionId":"decision-claim-demo","checkId":"check-demo",
    "supportingEvidenceIds":["evidence-0"],"refutingEvidenceIds":["evidence-1"]
  }]
}
```

For a recorded insufficient outcome, `decisionStatus=insufficient`, `decisions[].gapCodes=["INSUFFICIENT_EVIDENCE"]`, and `disagreements=[]`. The absence of disagreement remains only absence of a recorded linked conflict. A capacity response has `availability=BOUNDED_OUT`, `publicationState=NOT_ASSESSED`, empty arrays, `completeProjection=false`, and an additional `PROJECTION_CAPACITY_EXCEEDED_NO_PARTIAL_DATA` limitation.

## Verification and handoff

The dedicated `EvidenceViewHttpIT` runs a real random-port Spring HTTP server, production JWT filters/owned-run authorization/read service/JDBC queries, and an isolated Testcontainers PostgreSQL with all existing migrations. Only retrieval/vector transports are mocked. Source/check/seal storage is synthetic; completed check outcomes are generated by the production adjudicator with synthetic proposals. These fixtures are not paid/live model acceptance or proof of semantic quality.

Its 31 cases cover public credentials, cross-user/tenant/run isolation (including two runs in one project), spoofed browser scope, unsupported/empty/source-only/pending states, supported/refuted/contested/insufficient and dismissed relations, eight integrity/linkage corruptions, six unsafe URL shapes, safe URLs, all record/read/check/input-byte ceilings, oversized quotes, recorded blocked gaps, exact-seal membership stable terminal reads and an empty finalized insufficient report. A terminal-read snapshot confirms no changes to run version/time/final response, records, checks, operation counts or event counts; retrieval mocks have no interactions.

```sh
mvn -o -q -Pintegration -Dtest=EvidenceQuoteTest -Dit.test=EvidenceViewHttpIT verify
```

Dedicated HTTP suite: 31 passed; existing Unicode quote suite: 7 passed; zero failures/errors/skips. Coordinator must rerun relevant checks on its selected A+B integration SHA. Existing publication/finalization remains the authority for answer release. This task does not wire React, expand ordinary workflow citation metadata, publish a site, change packaging, push a branch, or deploy. Large-run pagination and legacy single-agent evidence storage remain follow-ups.

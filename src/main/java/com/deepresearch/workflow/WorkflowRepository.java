package com.deepresearch.workflow;

import com.deepresearch.workflow.WorkflowDtos.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL source of truth for public workflow state and replayable safe events. */
@Repository
public class WorkflowRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WorkflowRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public int insertRun(NewRun run) {
        return jdbcTemplate.update("""
                INSERT INTO agent_workflow_run
                    (run_id, session_id, user_id, question, context_snapshot, endpoint,
                     idempotency_key, request_fingerprint, graph_thread_id, status, stage,
                     deadline_at, requested_scopes, grant_id)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, CAST(? AS text[]), ?)
                ON CONFLICT (user_id, endpoint, idempotency_key) DO NOTHING
                """, run.runId(), run.sessionId(), run.userId(), run.question(), run.contextSnapshotJson(),
                run.endpoint(), run.idempotencyKey(), run.requestFingerprint(), run.graphThreadId(),
                run.status(), run.stage(), run.deadlineAt(), postgresArray(run.requestedScopes()), run.grantId());
    }

    public record AgentIdentity(String tenantId, String ownerId) {}

    public Optional<AgentIdentity> agentIdentity(String runId) {
        return jdbcTemplate.query("SELECT tenant_id,owner_id FROM agent_research_run WHERE run_id=?",
                (rs,n)->new AgentIdentity(rs.getString(1),rs.getString(2)),runId).stream().findFirst();
    }

    public boolean activeAgentToolReservation(String runId,String callId,UUID claim) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM agent_research_operation o JOIN agent_workflow_run r ON r.run_id=o.run_id
                WHERE o.run_id=? AND o.operation_key=? AND o.kind='TOOL' AND o.purpose='TOOL'
                AND o.status IN ('RESERVED','SETTLED') AND o.claim_token=? AND r.claim_token=o.claim_token
                AND NOT r.cancel_requested AND r.lease_until>now() AND r.deadline_at>now() AND r.status='WORKING')
            """,Boolean.class,runId,callId,claim));
    }
    public void lockAgentToolCompletion(String runId,UUID claim) {
        if (jdbcTemplate.queryForList("SELECT run_id FROM agent_workflow_run WHERE run_id=? AND claim_token=? AND NOT cancel_requested AND lease_until>now() AND deadline_at>now() FOR UPDATE",runId,claim).isEmpty())
            throw new IllegalStateException("stale Agent tool completion");
    }
    public boolean settleAgentTool(String runId,String callId,UUID claim,String envelope) {
        return jdbcTemplate.update("""
            UPDATE agent_research_operation SET status='SETTLED',safe_result=CAST(? AS jsonb),actual_usage='{}'::jsonb,settled_at=now()
            WHERE run_id=? AND operation_key=? AND kind='TOOL' AND purpose='TOOL' AND status='RESERVED' AND claim_token=?
            """,envelope,runId,callId,claim)==1;
    }

    public boolean sealedAgentPublication(String runId,String answer,List<String> citations) {
        return sealedAgentReport(runId,answer,citations,null).isPresent();
    }
    public Optional<com.fasterxml.jackson.databind.JsonNode> sealedAgentReport(String runId,String answer,List<String> citations,String terminalStatus) {
        try {
            // finalizeRun holds this row lock until the terminal update. Every proof write fences on it.
            if (jdbcTemplate.queryForList("SELECT run_id FROM agent_workflow_run WHERE run_id=? FOR UPDATE",runId).isEmpty())
                return Optional.empty();
            var sealed=jdbcTemplate.query("""
                SELECT result::text FROM agent_research_publication
                    WHERE run_id=? AND status='COMPLETED' AND answer_hash=?
                    AND result->>'answer'=? AND citations=CAST(? AS jsonb)
                    AND (?::text IS NULL OR result->>'terminal_status'=?)
                    ORDER BY completed_at DESC LIMIT 1
                """,(rs,n)-> {
                    try { return objectMapper.readTree(rs.getString(1)); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                        throw new IllegalArgumentException("publication proof invalid",invalid);
                    }
                },runId,com.deepresearch.agent.ToolArgumentFingerprint.sha256(answer),
                    answer,objectMapper.writeValueAsString(citations),terminalStatus,terminalStatus).stream().findFirst();
            if (sealed.isEmpty()) return sealed;
            var current=new AgentCompletionService(jdbcTemplate).goalsForRun(runId);
            if (current.isEmpty() || !com.deepresearch.evidence.EvidenceJson.canonical(com.deepresearch.evidence.EvidenceJson.JSON.valueToTree(current))
                    .equals(com.deepresearch.evidence.EvidenceJson.canonical(sealed.get().path("goals"))))
                return Optional.empty();
            if (!com.deepresearch.evidence.EvidenceJson.canonical(new AgentResearchStateService(jdbcTemplate).proofForRun(runId))
                    .equals(com.deepresearch.evidence.EvidenceJson.canonical(sealed.get().path("research_state"))))
                return Optional.empty();
            return sealed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("publication citations invalid",invalid);
        }
    }

    public void insertGrant(NewGrant grant) {
        jdbcTemplate.update("""
                INSERT INTO agent_workflow_grant
                    (grant_id, run_id, subject, scopes, policy_version, expires_at)
                VALUES (?, ?, ?, CAST(? AS text[]), ?, ?)
                ON CONFLICT (grant_id) DO NOTHING
                """, grant.grantId(), grant.runId(), grant.subject(), postgresArray(grant.scopes()),
                grant.policyVersion(), grant.expiresAt());
    }

    public void insertDifyMapping(String runId) {
        jdbcTemplate.update("INSERT INTO dify_workflow_run (run_id, dispatch_state) VALUES (?, 'PENDING') ON CONFLICT DO NOTHING", runId);
    }

    /** A claimed dispatch is never retried automatically, including after a process crash. */
    public List<String> claimDifyDispatches(int limit) {
        return jdbcTemplate.query("""
                UPDATE dify_workflow_run SET dispatch_state = 'POSTING', updated_at = now()
                WHERE run_id IN (
                    SELECT d.run_id FROM dify_workflow_run d
                    JOIN agent_workflow_run r ON r.run_id = d.run_id
                    WHERE d.dispatch_state = 'PENDING' AND r.status = 'DIFY_DISPATCHING'
                      AND r.deadline_at > now()
                    ORDER BY r.created_at FOR UPDATE OF d SKIP LOCKED LIMIT ?)
                RETURNING run_id
                """, (rs, n) -> rs.getString(1), limit);
    }

    @Transactional
    public List<String> timeoutPendingDify() {
        List<String> ids = jdbcTemplate.query("""
                UPDATE agent_workflow_run r SET status = 'TIMED_OUT', stage = 'TIMED_OUT',
                    cancel_requested = true, error_code = 'DIFY_DEADLINE_EXCEEDED',
                    updated_at = now(), version = version + 1
                WHERE r.status = 'DIFY_DISPATCHING' AND r.deadline_at <= now()
                  AND EXISTS (SELECT 1 FROM dify_workflow_run d WHERE d.run_id = r.run_id AND d.dispatch_state = 'PENDING')
                RETURNING r.run_id
                """, (rs, n) -> rs.getString(1));
        for (String id : ids) {
            revokeGrantForRun(id);
            insertEvent(id, "dify:terminal", "SYSTEM", null,
                    "TIMED_OUT", "{\"status\":\"TIMED_OUT\"}");
        }
        return ids;
    }

    @Transactional
    public void bindDify(String runId, String workflowRunId, String taskId) {
        // Keep the agent-run -> Dify-mapping lock order shared with cancellation.
        jdbcTemplate.query("SELECT run_id FROM agent_workflow_run WHERE run_id=? FOR UPDATE",
                (rs, n) -> rs.getString(1), runId);
        jdbcTemplate.update("""
                UPDATE dify_workflow_run SET workflow_run_id = ?, task_id = ?,
                    dispatch_state = CASE WHEN dispatch_state = 'POSTING' THEN 'BOUND' ELSE dispatch_state END,
                    updated_at = now()
                WHERE run_id = ? AND dispatch_state IN ('POSTING', 'UNKNOWN')
                """, workflowRunId, taskId, runId);
        jdbcTemplate.update("""
                UPDATE agent_workflow_run SET status = 'DIFY_WORKING', stage = 'DIFY_WORKING', updated_at = now()
                WHERE run_id = ? AND status = 'DIFY_DISPATCHING' AND cancel_requested = false
                """, runId);
        jdbcTemplate.update("""
                UPDATE dify_workflow_run d SET stop_state='PENDING', stop_next_attempt_at=now()
                FROM agent_workflow_run r
                WHERE d.run_id=? AND r.run_id=d.run_id AND r.cancel_requested=true
                  AND d.task_id IS NOT NULL AND d.stop_state='NONE'
                """, runId);
    }

    @Transactional
    public boolean unknownDifyDispatch(String runId) {
        jdbcTemplate.query("SELECT run_id FROM agent_workflow_run WHERE run_id=? FOR UPDATE",
                (rs, n) -> rs.getString(1), runId);
        jdbcTemplate.update("UPDATE dify_workflow_run SET dispatch_state = 'UNKNOWN', updated_at = now() WHERE run_id = ? AND dispatch_state = 'POSTING'", runId);
        int changed = jdbcTemplate.update("""
                UPDATE agent_workflow_run SET status = 'DISPATCH_UNKNOWN', stage = 'DISPATCH_UNKNOWN',
                    cancel_requested = true,
                    error_code = 'DIFY_DISPATCH_UNKNOWN',
                    error_message = 'Dify 派发结果未知，需按 java_run_id 人工对账；禁止自动重试',
                    updated_at = now(), version = version + 1
                WHERE run_id = ? AND status = 'DIFY_DISPATCHING'
                """, runId);
        if (changed == 1) revokeGrantForRun(runId);
        return changed == 1;
    }

    @Transactional
    public List<String> abandonStaleDifyDispatches() {
        List<String> ids = jdbcTemplate.query("""
                SELECT r.run_id FROM agent_workflow_run r
                JOIN dify_workflow_run d ON d.run_id=r.run_id
                WHERE d.dispatch_state = 'POSTING'
                  AND (d.updated_at < now() - interval '5 minutes' OR r.deadline_at <= now())
                ORDER BY r.run_id FOR UPDATE OF r SKIP LOCKED LIMIT 100
                """, (rs, n) -> rs.getString(1));
        List<String> transitioned = new java.util.ArrayList<>();
        for (String id : ids) {
            int unmapped = jdbcTemplate.update("""
                    UPDATE dify_workflow_run SET dispatch_state='UNKNOWN', updated_at=now()
                    WHERE run_id=? AND dispatch_state='POSTING'
                    """, id);
            if (unmapped != 1) continue;
            int changed = jdbcTemplate.update("""
                    UPDATE agent_workflow_run SET status = 'DISPATCH_UNKNOWN', stage = 'DISPATCH_UNKNOWN',
                        cancel_requested = true,
                        error_code = 'DIFY_DISPATCH_UNKNOWN',
                        error_message = 'Dify 派发结果未知，需按 java_run_id 人工对账；禁止自动重试',
                        updated_at = now(), version = version + 1
                    WHERE run_id = ? AND status = 'DIFY_DISPATCHING'
                    """, id);
            if (changed == 1) {
                revokeGrantForRun(id);
                transitioned.add(id);
            }
        }
        return transitioned;
    }

    public Optional<DifyMapping> difyMapping(String runId) {
        return jdbcTemplate.query("SELECT workflow_run_id, task_id, dispatch_state FROM dify_workflow_run WHERE run_id = ?",
                (rs, n) -> new DifyMapping(rs.getString(1), rs.getString(2), rs.getString(3)), runId).stream().findFirst();
    }

    /**
     * Rotates the oldest bound runs to the back of the polling queue while claiming them.
     * The timestamp is a short database lease that outlives the statement-level row lock,
     * so another application instance cannot immediately claim the same remote detail call.
     */
    public List<String> claimBoundDifyRuns(int limit) {
        if (limit < 1) return List.of();
        return jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT d.run_id FROM dify_workflow_run d
                    JOIN agent_workflow_run r ON r.run_id = d.run_id
                    WHERE d.dispatch_state = 'BOUND' AND r.status = 'DIFY_WORKING'
                      AND d.updated_at <= now() - interval '10 seconds'
                    ORDER BY d.updated_at, d.run_id
                    FOR UPDATE OF d SKIP LOCKED
                    LIMIT ?
                )
                UPDATE dify_workflow_run d SET updated_at = now()
                FROM candidates c WHERE d.run_id = c.run_id
                RETURNING d.run_id
                """, (rs, n) -> rs.getString(1), limit);
    }

    public List<String> expiredDifyRuns() {
        return jdbcTemplate.query("""
                SELECT r.run_id FROM agent_workflow_run r JOIN dify_workflow_run d ON d.run_id = r.run_id
                WHERE r.status = 'DIFY_WORKING' AND r.deadline_at <= now() AND d.dispatch_state = 'BOUND'
                ORDER BY r.deadline_at LIMIT 20
                """, (rs, n) -> rs.getString(1));
    }

    @Transactional
    public boolean timeoutDify(String runId) {
        int changed = jdbcTemplate.update("""
                UPDATE agent_workflow_run SET status = 'TIMED_OUT', stage = 'TIMED_OUT',
                    cancel_requested = true, error_code = 'DIFY_DEADLINE_EXCEEDED',
                    updated_at = now(), version = version + 1
                WHERE run_id = ? AND status = 'DIFY_WORKING' AND deadline_at <= now()
                """, runId);
        if (changed != 1) return false;
        requestDifyStop(runId);
        revokeGrantForRun(runId);
        insertEvent(runId, "dify:terminal", "SYSTEM", null,
                "TIMED_OUT", "{\"status\":\"TIMED_OUT\"}");
        return true;
    }

    @Transactional
    public boolean finishDify(String runId, WorkflowStatus status, String responseJson,
                              String usageJson, String errorCode, String answer) {
        RunRow discovered = find(runId).orElse(null);
        if (discovered == null) return false;
        lockSessionAndRun(runId, discovered.sessionId(), discovered.userId());
        int changed = jdbcTemplate.update("""
                UPDATE agent_workflow_run SET status = ?, stage = ?, final_response = CAST(? AS jsonb),
                    usage = CAST(? AS jsonb), error_code = ?, error_message = ?, updated_at = now(), version = version + 1
                WHERE run_id = ? AND status = 'DIFY_WORKING' AND cancel_requested = false
                  AND deadline_at > clock_timestamp()
                  AND EXISTS (SELECT 1 FROM dify_workflow_run WHERE run_id = ? AND dispatch_state = 'BOUND')
                """, status.name(), status.name(), responseJson, usageJson, errorCode,
                errorCode == null ? null : DifyFailureCodes.message(errorCode), runId, runId);
        if (changed != 1) return false;
        RunRow row = find(runId).orElseThrow();
        revokeGrantForRun(runId);
        if (status == WorkflowStatus.SUCCEEDED) {
            insertFinalMessages(runId, row.sessionId(), row.userId(), row.question(), answer, true);
        }
        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", status.name());
        if (errorCode != null) payload.put("errorCode", errorCode);
        insertEvent(runId, "dify:terminal", "SYSTEM", null, status.name(), payload.toString());
        return true;
    }

    public record DifyMapping(String workflowRunId, String taskId, String dispatchState) {}

    /** Local cancellation is authoritative; this records remote cleanup separately. */
    public boolean requestDifyStop(String runId) {
        return jdbcTemplate.update("""
                UPDATE dify_workflow_run SET stop_state='PENDING', stop_next_attempt_at=now(),
                    stop_last_error=NULL
                WHERE run_id=? AND dispatch_state IN ('POSTING','BOUND','UNKNOWN')
                  AND stop_state='NONE'
                """, runId) == 1;
    }

    public Optional<String> difyStopState(String runId) {
        return jdbcTemplate.query("SELECT stop_state FROM dify_workflow_run WHERE run_id=?",
                (rs, n) -> rs.getString(1), runId).stream().findFirst();
    }

    /** Claim is durable across a Java crash; an expired lease can be retried. */
    @Transactional
    public List<DifyStopWork> claimDifyStops(int limit) {
        if (limit < 1) return List.of();
        jdbcTemplate.update("""
                UPDATE dify_workflow_run SET stop_state='EXHAUSTED', stop_last_error='DIFY_STOP_UNCONFIRMED',
                    stop_lease_until=NULL, stop_claim_token=NULL, stop_next_attempt_at=NULL
                WHERE stop_state='LEASED' AND stop_attempts>=8 AND stop_lease_until<=now()
                """);
        return jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT run_id, stop_state AS previous_state FROM dify_workflow_run
                    WHERE task_id IS NOT NULL AND stop_attempts < 8
                      AND ((stop_state IN ('PENDING','REQUESTED') AND stop_next_attempt_at <= now())
                        OR (stop_state='LEASED' AND stop_lease_until <= now()))
                    ORDER BY stop_next_attempt_at, run_id
                    FOR UPDATE SKIP LOCKED LIMIT ?
                )
                UPDATE dify_workflow_run d SET stop_state='LEASED', stop_attempts=stop_attempts+1,
                    stop_claim_token=gen_random_uuid(), stop_lease_until=now()+interval '30 seconds'
                FROM candidates c WHERE d.run_id=c.run_id
                RETURNING d.run_id,d.task_id,d.workflow_run_id,c.previous_state,d.stop_claim_token,d.stop_attempts
                """, (rs, n) -> new DifyStopWork(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, UUID.class), rs.getInt(6)), limit);
    }

    public boolean completeDifyStop(DifyStopWork work, String nextState, String errorCode) {
        if (!Set.of("PENDING", "REQUESTED", "CONFIRMED_STOPPED", "REMOTE_TERMINAL", "EXHAUSTED")
                .contains(nextState)) throw new IllegalArgumentException("Invalid Dify stop state");
        String state = work.attempts() >= 8 && Set.of("PENDING", "REQUESTED").contains(nextState)
                ? "EXHAUSTED" : nextState;
        int delay = Math.min(60, 5 * (1 << Math.min(work.attempts() - 1, 4)));
        return jdbcTemplate.update("""
                UPDATE dify_workflow_run SET stop_state=?, stop_last_error=?,
                    stop_next_attempt_at=CASE WHEN ? IN ('PENDING','REQUESTED')
                        THEN now() + (? * interval '1 second') ELSE NULL END,
                    stop_lease_until=NULL, stop_claim_token=NULL
                WHERE run_id=? AND stop_state='LEASED' AND stop_claim_token=?
                """, state, errorCode, state, delay, work.runId(), work.claimToken()) == 1;
    }

    public record DifyStopWork(String runId, String taskId, String workflowRunId,
                               String previousState, UUID claimToken, int attempts) {}

    @Transactional
    public boolean beginDifyToolCall(String runId, String callId, String tool, String fingerprint) {
        // Serialize budget checks for this run before inserting a new call ID.
        List<String> lock = jdbcTemplate.query("SELECT run_id FROM dify_workflow_run WHERE run_id = ? FOR UPDATE",
                (rs, n) -> rs.getString(1), runId);
        if (lock.isEmpty()) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dify_workflow_tool_call WHERE run_id = ?", Integer.class, runId);
        if (count != null && count >= 6) return false;
        return jdbcTemplate.update("""
                INSERT INTO dify_workflow_tool_call(run_id, call_id, tool_name, request_fingerprint, status)
                SELECT r.run_id, ?, ?, ?, 'EXECUTING' FROM agent_workflow_run r
                JOIN dify_workflow_run d ON d.run_id = r.run_id
                WHERE r.run_id = ? AND r.status = 'DIFY_WORKING' AND r.cancel_requested = false
                  AND r.deadline_at > now() AND d.dispatch_state = 'BOUND'
                  AND ? = ANY(r.requested_scopes)
                ON CONFLICT DO NOTHING
                """, callId, tool, fingerprint, runId, tool) == 1;
    }

    public Optional<DifyToolCall> findDifyToolCall(String runId, String callId) {
        return jdbcTemplate.query("""
                SELECT tool_name, request_fingerprint, status, safe_result
                FROM dify_workflow_tool_call WHERE run_id = ? AND call_id = ?
                """, (rs, n) -> new DifyToolCall(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4)), runId, callId).stream().findFirst();
    }

    @Transactional
    public boolean completeDifyToolCall(String runId, String callId, String tool,
                                        String fingerprint, String safeResultJson,
                                        List<String> citationIds) {
        // Parallel Dify workers often cite the same chunks. Serialize their
        // receipts for one run before touching the shared source table, so
        // overlapping INSERT ... ON CONFLICT calls cannot deadlock.
        List<String> lock = jdbcTemplate.query("SELECT run_id FROM dify_workflow_run WHERE run_id = ? FOR UPDATE",
                (rs, n) -> rs.getString(1), runId);
        if (lock.isEmpty()) return false;
        int changed = jdbcTemplate.update("""
                UPDATE dify_workflow_tool_call c SET status = 'COMPLETED',
                    safe_result = CAST(? AS jsonb), completed_at = now()
                WHERE c.run_id = ? AND c.call_id = ? AND c.tool_name = ?
                  AND c.request_fingerprint = ? AND c.status = 'EXECUTING'
                  AND EXISTS (
                    SELECT 1 FROM agent_workflow_run r JOIN dify_workflow_run d ON d.run_id = r.run_id
                    WHERE r.run_id = c.run_id AND r.status = 'DIFY_WORKING'
                      AND r.cancel_requested = false AND r.deadline_at > now()
                      AND d.dispatch_state = 'BOUND' AND ? = ANY(r.requested_scopes))
                """, safeResultJson, runId, callId, tool, fingerprint, tool);
        if (changed != 1) return false;
        for (String id : citationIds.stream().distinct().sorted().toList()) {
            jdbcTemplate.update("""
                    INSERT INTO dify_workflow_source(run_id, citation_id) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """, runId, id);
        }
        return true;
    }

    public record DifyToolCall(String tool, String fingerprint, String status, String safeResultJson) {}

    public Set<String> difySources(String runId) {
        return new java.util.HashSet<>(jdbcTemplate.query(
                "SELECT citation_id FROM dify_workflow_source WHERE run_id = ?",
                (rs, n) -> rs.getString(1), runId));
    }

    public record DifyWebSource(String citationId, String url, String title, String content,
                                OffsetDateTime completedAt) {}

    public record DifyKbSource(String citationId, String title, String content) {}

    /** Presentation metadata from this run's authorized completed KB receipt, after live validation. */
    public Optional<DifyKbSource> difyKbSource(String runId, String citationId) {
        return jdbcTemplate.query("""
                SELECT e->>'citationId', e->>'title', e->>'content'
                FROM dify_workflow_tool_call c
                JOIN agent_workflow_run r ON r.run_id=c.run_id
                JOIN dify_workflow_source s ON s.run_id=c.run_id AND s.citation_id=?
                CROSS JOIN LATERAL jsonb_array_elements(c.safe_result->'evidences') e
                WHERE c.run_id=? AND c.tool_name='kb_search' AND c.status='COMPLETED'
                  AND c.completed_at IS NOT NULL AND 'kb_search'=ANY(r.requested_scopes)
                  AND c.safe_result->>'success'='true' AND c.safe_result->>'code'='OK'
                  AND c.safe_result->>'tool'='kb_search'
                  AND e->>'citationId'=? AND e->>'untrusted'='true'
                  AND c.call_id ~ ('^' || c.run_id || ':(initial|revision):[1-4]$')
                ORDER BY c.completed_at, c.call_id LIMIT 1
                """, (rs, n) -> new DifyKbSource(rs.getString(1), rs.getString(2), rs.getString(3)),
                citationId, runId, citationId).stream().findFirst();
    }

    /** Reads only successful, completed, authorized receipts for this exact run. No remote fetch. */
    public Optional<DifyWebSource> difyWebSource(String runId, String citationId) {
        return jdbcTemplate.query("""
                SELECT e->>'citationId', e->>'url', e->>'title', e->>'content', c.completed_at
                FROM dify_workflow_tool_call c
                JOIN agent_workflow_run r ON r.run_id=c.run_id
                JOIN dify_workflow_source s ON s.run_id=c.run_id AND s.citation_id=?
                CROSS JOIN LATERAL jsonb_array_elements(c.safe_result->'evidences') e
                WHERE c.run_id=? AND c.tool_name='web_search' AND c.status='COMPLETED'
                  AND c.completed_at IS NOT NULL AND 'web_search'=ANY(r.requested_scopes)
                  AND c.safe_result->>'success'='true' AND c.safe_result->>'code'='OK'
                  AND c.safe_result->>'tool'='web_search'
                  AND e->>'citationId'=? AND e->>'untrusted'='true'
                  AND c.call_id ~ ('^' || c.run_id || ':(initial|revision):[1-4]$')
                ORDER BY c.completed_at, c.call_id LIMIT 1
                """, (rs, n) -> new DifyWebSource(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, OffsetDateTime.class)), citationId, runId, citationId)
                .stream().findFirst();
    }

    public boolean insertEvent(String runId, String eventKey, String role, String taskId,
                               String type, String safePayloadJson) {
        return jdbcTemplate.update("""
                INSERT INTO agent_workflow_event
                    (run_id, event_key, role, task_id, type, safe_payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (run_id, event_key) DO NOTHING
                """, runId, eventKey, role, taskId, type, safePayloadJson) == 1;
    }

    public Optional<RunRow> findByIdempotency(String userId, String endpoint, String key) {
        return jdbcTemplate.query("""
                SELECT * FROM agent_workflow_run
                WHERE user_id = ? AND endpoint = ? AND idempotency_key = ?
                """, this::mapRun, userId, endpoint, key).stream().findFirst();
    }

    public Optional<RunRow> findOwned(String runId, String userId) {
        return jdbcTemplate.query("""
                SELECT * FROM agent_workflow_run WHERE run_id = ? AND user_id = ?
                """, this::mapRun, runId, userId).stream().findFirst();
    }

    public Optional<RunRow> find(String runId) {
        return jdbcTemplate.query("SELECT * FROM agent_workflow_run WHERE run_id = ?",
                this::mapRun, runId).stream().findFirst();
    }

    public List<Event> eventsAfter(String runId, long afterEventId, int limit) {
        return jdbcTemplate.query("""
                SELECT event_id, run_id, type, role, task_id, safe_payload, created_at
                FROM agent_workflow_event
                WHERE run_id = ? AND event_id > ?
                ORDER BY event_id ASC
                LIMIT ?
                """, (rs, rowNum) -> {
            long eventId = rs.getLong("event_id");
            return new Event(eventId, rs.getString("run_id") + ":" + eventId,
                    rs.getString("type"), rs.getString("role"), rs.getString("task_id"),
                    json(rs.getString("safe_payload")),
                    rs.getObject("created_at", OffsetDateTime.class));
        }, runId, afterEventId, limit);
    }

    public List<ToolReceiptRow> completedToolReceipts(String runId) {
        return jdbcTemplate.query("""
                SELECT call_id, task_id, tool_name, request_fingerprint, safe_result
                FROM agent_workflow_tool_receipt
                WHERE run_id = ? AND status = 'COMPLETED' AND safe_result IS NOT NULL
                ORDER BY created_at, call_id
                """, (rs, rowNum) -> new ToolReceiptRow(
                rs.getString("call_id"), rs.getString("task_id"), rs.getString("tool_name"),
                rs.getString("request_fingerprint"), rs.getString("safe_result")), runId);
    }

    /** Trusted snapshots survive claim recovery; sidecar envelopes are deliberately excluded. */
    public List<ToolReceiptRow> completedSourceReceipts(String runId, String owner) {
        return jdbcTemplate.query("""
                SELECT receipt.call_id, receipt.task_id, receipt.tool_name,
                       receipt.request_fingerprint, receipt.mcp_safe_result
                FROM agent_workflow_tool_receipt receipt
                JOIN agent_workflow_run run ON run.run_id = receipt.run_id
                WHERE receipt.run_id = ? AND run.user_id = ?
                  AND receipt.mcp_execution_status = 'COMPLETED'
                  AND receipt.mcp_safe_result IS NOT NULL AND receipt.mcp_claim_token IS NOT NULL
                  AND receipt.tool_name IN ('kb_search', 'web_search')
                ORDER BY receipt.created_at, receipt.call_id
                LIMIT 257
                """, (rs, rowNum) -> new ToolReceiptRow(
                rs.getString("call_id"), rs.getString("task_id"), rs.getString("tool_name"),
                rs.getString("request_fingerprint"), rs.getString("mcp_safe_result")), runId, owner);
    }

    public Optional<GrantRow> activeGrant(String grantId, String runId, UUID claimToken) {
        return jdbcTemplate.query("""
                SELECT g.grant_id, g.run_id, g.subject, g.scopes, g.expires_at,
                       r.status, r.cancel_requested
                FROM agent_workflow_grant g
                JOIN agent_workflow_run r ON r.run_id = g.run_id
                WHERE g.grant_id = ? AND g.run_id = ?
                  AND r.grant_id = g.grant_id
                  AND r.user_id = g.subject
                  AND r.claim_token = ?
                  AND r.lease_until > now()
                  AND g.revoked_at IS NULL AND g.expires_at > now()
                """, (rs, rowNum) -> new GrantRow(
                rs.getString("grant_id"), rs.getString("run_id"), rs.getString("subject"),
                sqlArray(rs.getArray("scopes")), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("status"), rs.getBoolean("cancel_requested")), grantId, runId, claimToken)
                .stream().findFirst();
    }

    public boolean bindTaskGrant(String grantId, String runId, String taskId, UUID claimToken,
                                 List<String> scopes, OffsetDateTime expiresAt) {
        // (runId, taskId) 的 grant 和精确 scope 是不可重绑定的安全边界；
        // 恢复执行只轮换 claim token 与过期时间，防止任务复用造成权限漂移。
        return jdbcTemplate.update("""
                INSERT INTO agent_workflow_task_grant
                    (run_id, task_id, grant_id, claim_token, scopes, expires_at)
                VALUES (?, ?, ?, ?, CAST(? AS text[]), ?)
                ON CONFLICT (run_id, task_id) DO UPDATE
                SET claim_token = EXCLUDED.claim_token,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = now()
                WHERE agent_workflow_task_grant.grant_id = EXCLUDED.grant_id
                  AND agent_workflow_task_grant.scopes = EXCLUDED.scopes
                """, runId, taskId, grantId, claimToken, postgresArray(scopes), expiresAt) == 1;
    }

    public boolean activeTaskGrant(String grantId, String runId, String taskId, UUID claimToken,
                                   List<String> scopes) {
        Boolean active = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM agent_workflow_task_grant tg
                    JOIN agent_workflow_run r ON r.run_id = tg.run_id
                    JOIN agent_workflow_grant g
                      ON g.grant_id = tg.grant_id AND g.run_id = tg.run_id
                    WHERE tg.grant_id = ? AND tg.run_id = ? AND tg.task_id = ?
                      AND tg.claim_token = ? AND r.claim_token = tg.claim_token
                      AND tg.scopes @> CAST(? AS text[])
                      AND CAST(? AS text[]) @> tg.scopes
                      AND tg.expires_at > now() AND r.lease_until > now()
                      AND r.cancel_requested = FALSE AND g.revoked_at IS NULL
                      AND g.expires_at > now()
                      AND r.status NOT IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED')
                )
                """, Boolean.class, grantId, runId, taskId, claimToken,
                postgresArray(scopes), postgresArray(scopes));
        return Boolean.TRUE.equals(active);
    }

    /**
     * Atomically reserves the Java execution side of a Python-created receipt.
     * The update succeeds only for the current fenced claim and its exact task scope.
     */
    public boolean beginMcpToolExecution(WorkflowDelegationContext context, UUID claimToken,
                                         String callId, String toolName, String requestFingerprint) {
        return jdbcTemplate.update("""
                UPDATE agent_workflow_tool_receipt receipt
                SET mcp_execution_status = 'EXECUTING',
                    mcp_claim_token = ?,
                    mcp_started_at = now()
                WHERE receipt.run_id = ? AND receipt.call_id = ?
                  AND receipt.task_id = ? AND receipt.tool_name = ?
                  AND receipt.request_fingerprint = ?
                  AND receipt.claim_token = ?
                  AND receipt.mcp_execution_status IS NULL
                  AND EXISTS (
                    SELECT 1
                    FROM agent_workflow_run workflow_run
                    JOIN agent_workflow_grant workflow_grant
                      ON workflow_grant.grant_id = workflow_run.grant_id
                     AND workflow_grant.run_id = workflow_run.run_id
                    JOIN agent_workflow_task_grant task_grant
                      ON task_grant.run_id = workflow_run.run_id
                     AND task_grant.grant_id = workflow_grant.grant_id
                    WHERE workflow_run.run_id = receipt.run_id
                      AND workflow_run.claim_token = ? AND workflow_run.lease_until > now()
                      AND workflow_run.cancel_requested = FALSE AND workflow_run.status = 'WORKING'
                      AND workflow_run.user_id = workflow_grant.subject
                      AND workflow_grant.grant_id = ? AND workflow_grant.revoked_at IS NULL
                      AND workflow_grant.expires_at > now()
                      AND task_grant.task_id = ?
                      AND task_grant.claim_token = workflow_run.claim_token
                      AND task_grant.expires_at > now()
                      AND task_grant.scopes @> CAST(? AS text[])
                      AND CAST(? AS text[]) @> task_grant.scopes
                  )
                """, claimToken, context.runId(), callId, context.taskId(), toolName,
                requestFingerprint, claimToken, claimToken, context.grantId(), context.taskId(),
                postgresArray(context.scopes().stream().sorted().toList()),
                postgresArray(context.scopes().stream().sorted().toList())) == 1;
    }

    public Optional<McpToolExecutionRow> findMcpToolExecution(String runId, String callId) {
        return jdbcTemplate.query("""
                SELECT task_id, tool_name, request_fingerprint, claim_token,
                       mcp_execution_status, mcp_safe_result, mcp_claim_token
                FROM agent_workflow_tool_receipt
                WHERE run_id = ? AND call_id = ?
                """, (rs, rowNum) -> new McpToolExecutionRow(
                rs.getString("task_id"), rs.getString("tool_name"),
                rs.getString("request_fingerprint"), rs.getObject("claim_token", UUID.class),
                rs.getString("mcp_execution_status"), rs.getString("mcp_safe_result"),
                rs.getObject("mcp_claim_token", UUID.class)), runId, callId).stream().findFirst();
    }

    public boolean activeMcpExecutionBinding(WorkflowDelegationContext context, UUID claimToken) {
        Boolean active = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM agent_workflow_task_grant task_grant
                    JOIN agent_workflow_run workflow_run
                      ON workflow_run.run_id = task_grant.run_id
                    JOIN agent_workflow_grant workflow_grant
                      ON workflow_grant.grant_id = task_grant.grant_id
                     AND workflow_grant.run_id = task_grant.run_id
                    WHERE task_grant.grant_id = ? AND task_grant.run_id = ?
                      AND task_grant.task_id = ? AND task_grant.claim_token = ?
                      AND workflow_run.claim_token = task_grant.claim_token
                      AND workflow_run.status = 'WORKING'
                      AND workflow_run.lease_until > now()
                      AND workflow_run.cancel_requested = FALSE
                      AND workflow_run.user_id = workflow_grant.subject
                      AND workflow_grant.revoked_at IS NULL
                      AND workflow_grant.expires_at > now()
                      AND task_grant.expires_at > now()
                      AND task_grant.scopes @> CAST(? AS text[])
                      AND CAST(? AS text[]) @> task_grant.scopes
                )
                """, Boolean.class, context.grantId(), context.runId(), context.taskId(), claimToken,
                postgresArray(context.scopes().stream().sorted().toList()),
                postgresArray(context.scopes().stream().sorted().toList()));
        return Boolean.TRUE.equals(active);
    }

    /** Completes only the same Java executor claim while that workflow claim is still live. */
    public boolean completeMcpToolExecution(WorkflowDelegationContext context, UUID claimToken,
                                            String callId, String toolName,
                                            String requestFingerprint, String safeResultJson) {
        return jdbcTemplate.update("""
                UPDATE agent_workflow_tool_receipt receipt
                SET mcp_execution_status = 'COMPLETED',
                    mcp_safe_result = CAST(? AS jsonb),
                    mcp_completed_at = now()
                WHERE receipt.run_id = ? AND receipt.call_id = ?
                  AND receipt.task_id = ? AND receipt.tool_name = ?
                  AND receipt.request_fingerprint = ?
                  AND receipt.claim_token = ? AND receipt.mcp_claim_token = ?
                  AND receipt.mcp_execution_status = 'EXECUTING'
                  AND EXISTS (
                    SELECT 1
                    FROM agent_workflow_run workflow_run
                    JOIN agent_workflow_grant workflow_grant
                      ON workflow_grant.grant_id = workflow_run.grant_id
                     AND workflow_grant.run_id = workflow_run.run_id
                    JOIN agent_workflow_task_grant task_grant
                      ON task_grant.run_id = workflow_run.run_id
                     AND task_grant.grant_id = workflow_grant.grant_id
                    WHERE workflow_run.run_id = receipt.run_id
                      AND workflow_run.claim_token = ? AND workflow_run.lease_until > now()
                      AND workflow_run.cancel_requested = FALSE AND workflow_run.status = 'WORKING'
                      AND workflow_run.user_id = workflow_grant.subject
                      AND workflow_grant.grant_id = ? AND workflow_grant.revoked_at IS NULL
                      AND workflow_grant.expires_at > now()
                      AND task_grant.task_id = ?
                      AND task_grant.claim_token = workflow_run.claim_token
                      AND task_grant.expires_at > now()
                      AND task_grant.scopes @> CAST(? AS text[])
                      AND CAST(? AS text[]) @> task_grant.scopes
                  )
                """, safeResultJson, context.runId(), callId, context.taskId(), toolName,
                requestFingerprint, claimToken, claimToken, claimToken, context.grantId(),
                context.taskId(), postgresArray(context.scopes().stream().sorted().toList()),
                postgresArray(context.scopes().stream().sorted().toList())) == 1;
    }

    @Transactional
    public int cancel(String runId, String userId) {
        int changed = jdbcTemplate.update("""
                UPDATE agent_workflow_run
                SET cancel_requested = TRUE, status = 'CANCELLED', stage = 'CANCELLED',
                    version = version + 1, updated_at = now()
                WHERE run_id = ? AND user_id = ?
                  AND status NOT IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED','DISPATCH_UNKNOWN')
                """, runId, userId);
        if (changed == 1) requestDifyStop(runId);
        return changed;
    }

    public void revokeGrantForRun(String runId) {
        jdbcTemplate.update("""
                UPDATE agent_workflow_grant SET revoked_at = COALESCE(revoked_at, now())
                WHERE run_id = ?
                """, runId);
    }

    public int finalizeClaim(String runId, UUID claimToken, WorkflowStatus status,
                             String finalResponseJson, String usageJson,
                             String errorCode, String errorMessage, String finalizeFingerprint) {
        // 成功类终态必须先进入 FINALIZING，确保 checkpoint 与用量已经对账；
        // 失败类终态允许从执行阶段直接收口，以便异常路径保持 fail-closed。
        return jdbcTemplate.update("""
                UPDATE agent_workflow_run
                SET status = ?, stage = ?, final_response = CAST(? AS jsonb), usage = CAST(? AS jsonb),
                    error_code = ?, error_message = ?, finalize_fingerprint = ?,
                    finalized_claim_token = ?, version = version + 1, updated_at = now()
                WHERE run_id = ? AND claim_token = ?
                  AND lease_until > clock_timestamp()
                  AND cancel_requested = FALSE
                  AND status NOT IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED')
                  AND (? NOT IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE') OR status = 'FINALIZING')
                """, status.name(), status.name(), finalResponseJson, usageJson,
                errorCode, errorMessage, finalizeFingerprint, claimToken,
                runId, claimToken, status.name());
    }

    /** Common finalization prefix: never acquire the first session lock after a run lock. */
    public void lockSessionAndRun(String runId, String sessionId, String userId) {
        if (jdbcTemplate.queryForList("""
                SELECT session_id FROM agent_session WHERE session_id=? AND user_id=? FOR UPDATE
                """, sessionId, userId).isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "workflow 不存在");
        }
        if (jdbcTemplate.queryForList("""
                SELECT run_id FROM agent_workflow_run
                WHERE run_id=? AND session_id=? AND user_id=? FOR UPDATE
                """, runId, sessionId, userId).isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "workflow 不存在");
        }
    }

    public void insertFinalMessages(String runId, String sessionId, String userId,
                                    String question, String answer, boolean successful) {
        jdbcTemplate.update("""
                INSERT INTO agent_run (run_id, session_id, user_id, question, answer, rounds, finished)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                ON CONFLICT (run_id) DO NOTHING
                """, runId, sessionId, userId, question, answer, successful);
        jdbcTemplate.update("""
                INSERT INTO agent_message (message_id, session_id, run_id, role, content)
                VALUES (?, ?, ?, 'user', ?)
                ON CONFLICT (message_id) DO NOTHING
                """, runId + "-user", sessionId, runId, question);
        jdbcTemplate.update("""
                INSERT INTO agent_message (message_id, session_id, run_id, role, content)
                VALUES (?, ?, ?, 'assistant', ?)
                ON CONFLICT (message_id) DO NOTHING
                """, runId + "-assistant", sessionId, runId, answer);
        jdbcTemplate.update("UPDATE agent_session SET updated_at = now() WHERE session_id = ?", sessionId);
        // Durable completion hook shared by Dify and LangGraph. Generation is deferred
        // until a maintenance worker can reserve a budget; never add an untracked model call.
        insertEvent(runId, "workflow:session-summary:required", "SYSTEM", null,
                "SESSION_SUMMARY_MAINTENANCE_REQUIRED",
                "{\"schema_version\":\"0.1.0\",\"status\":\"pending\","
                        + "\"reason\":\"new_final_messages\",\"generation\":\"deferred\"}");
    }

    private RunRow mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new RunRow(
                rs.getString("run_id"), rs.getString("session_id"), rs.getString("user_id"),
                rs.getString("question"), rs.getString("context_snapshot"), rs.getString("endpoint"),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getString("graph_thread_id"), rs.getString("status"), rs.getString("stage"),
                rs.getObject("deadline_at", OffsetDateTime.class), rs.getBoolean("cancel_requested"),
                sqlArray(rs.getArray("requested_scopes")), rs.getString("grant_id"),
                rs.getObject("claim_token", UUID.class), rs.getString("claimed_by"),
                rs.getObject("lease_until", OffsetDateTime.class),
                rs.getString("final_response"), rs.getString("usage"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getString("finalize_fingerprint"), rs.getObject("finalized_claim_token", UUID.class),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private JsonNode json(String raw) {
        try {
            return raw == null ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
        } catch (Exception failure) {
            throw new IllegalStateException("workflow JSON 无法读取", failure);
        }
    }

    private String postgresArray(List<String> values) {
        return "{" + String.join(",", values == null ? List.of() : values) + "}";
    }

    private List<String> sqlArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        return value instanceof String[] strings ? Arrays.asList(strings) : List.of();
    }

    public record NewRun(String runId, String sessionId, String userId, String question,
                         String contextSnapshotJson, String endpoint, String idempotencyKey,
                         String requestFingerprint, String graphThreadId, String status,
                         String stage, OffsetDateTime deadlineAt, List<String> requestedScopes,
                         String grantId) {
    }

    public record NewGrant(String grantId, String runId, String subject, List<String> scopes,
                           int policyVersion, OffsetDateTime expiresAt) {
    }

    public record RunRow(String runId, String sessionId, String userId, String question,
                         String contextSnapshotJson, String endpoint, String idempotencyKey,
                         String requestFingerprint, String graphThreadId, String status,
                         String stage, OffsetDateTime deadlineAt, boolean cancelRequested,
                         List<String> requestedScopes, String grantId, UUID claimToken,
                         String claimedBy, OffsetDateTime leaseUntil, String finalResponseJson,
                         String usageJson, String errorCode, String errorMessage,
                         String finalizeFingerprint, UUID finalizedClaimToken, long version,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record GrantRow(String grantId, String runId, String subject, List<String> scopes,
                           OffsetDateTime expiresAt, String runStatus, boolean cancelRequested) {
    }

    public record ToolReceiptRow(String callId, String taskId, String toolName,
                                 String requestFingerprint, String safeResultJson) {
    }

    public record McpToolExecutionRow(String taskId, String toolName, String requestFingerprint,
                                      UUID claimToken, String executionStatus,
                                      String safeResultJson, UUID executionClaimToken) {
    }
}

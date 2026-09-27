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
                    cancel_requested = true, error_code = 'DIFY_DEADLINE_EXCEEDED', updated_at = now()
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
        jdbcTemplate.update("""
                UPDATE dify_workflow_run SET workflow_run_id = ?, task_id = ?, dispatch_state = 'BOUND', updated_at = now()
                WHERE run_id = ? AND dispatch_state = 'POSTING'
                """, workflowRunId, taskId, runId);
        jdbcTemplate.update("""
                UPDATE agent_workflow_run SET status = 'DIFY_WORKING', stage = 'DIFY_WORKING', updated_at = now()
                WHERE run_id = ? AND status = 'DIFY_DISPATCHING' AND cancel_requested = false
                """, runId);
    }

    @Transactional
    public void unknownDifyDispatch(String runId) {
        jdbcTemplate.update("UPDATE dify_workflow_run SET dispatch_state = 'UNKNOWN', updated_at = now() WHERE run_id = ? AND dispatch_state = 'POSTING'", runId);
        int changed = jdbcTemplate.update("""
                UPDATE agent_workflow_run SET status = 'DISPATCH_UNKNOWN', stage = 'DISPATCH_UNKNOWN',
                    error_code = 'DIFY_DISPATCH_UNKNOWN',
                    error_message = 'Dify 派发结果未知，需按 java_run_id 人工对账；禁止自动重试',
                    updated_at = now()
                WHERE run_id = ? AND status = 'DIFY_DISPATCHING'
                """, runId);
        if (changed == 1) revokeGrantForRun(runId);
    }

    @Transactional
    public List<String> abandonStaleDifyDispatches() {
        List<String> ids = jdbcTemplate.query("""
                UPDATE dify_workflow_run d SET dispatch_state = 'UNKNOWN', updated_at = now()
                FROM agent_workflow_run r
                WHERE d.run_id = r.run_id AND d.dispatch_state = 'POSTING'
                  AND (d.updated_at < now() - interval '5 minutes' OR r.deadline_at <= now())
                RETURNING d.run_id
                """, (rs, n) -> rs.getString(1));
        for (String id : ids) {
            int changed = jdbcTemplate.update("""
                    UPDATE agent_workflow_run SET status = 'DISPATCH_UNKNOWN', stage = 'DISPATCH_UNKNOWN',
                        error_code = 'DIFY_DISPATCH_UNKNOWN',
                        error_message = 'Dify 派发结果未知，需按 java_run_id 人工对账；禁止自动重试',
                        updated_at = now()
                    WHERE run_id = ? AND status = 'DIFY_DISPATCHING'
                    """, id);
            if (changed == 1) revokeGrantForRun(id);
        }
        return ids;
    }

    public Optional<DifyMapping> difyMapping(String runId) {
        return jdbcTemplate.query("SELECT workflow_run_id, task_id, dispatch_state FROM dify_workflow_run WHERE run_id = ?",
                (rs, n) -> new DifyMapping(rs.getString(1), rs.getString(2), rs.getString(3)), runId).stream().findFirst();
    }

    public List<String> boundDifyRuns() {
        return jdbcTemplate.query("""
                SELECT d.run_id FROM dify_workflow_run d JOIN agent_workflow_run r ON r.run_id = d.run_id
                WHERE d.dispatch_state = 'BOUND' AND r.status = 'DIFY_WORKING'
                ORDER BY d.updated_at LIMIT 20
                """, (rs, n) -> rs.getString(1));
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
                    cancel_requested = true, error_code = 'DIFY_DEADLINE_EXCEEDED', updated_at = now()
                WHERE run_id = ? AND status = 'DIFY_WORKING' AND deadline_at <= now()
                """, runId);
        if (changed != 1) return false;
        revokeGrantForRun(runId);
        insertEvent(runId, "dify:terminal", "SYSTEM", null,
                "TIMED_OUT", "{\"status\":\"TIMED_OUT\"}");
        return true;
    }

    @Transactional
    public boolean finishDify(String runId, WorkflowStatus status, String responseJson,
                              String usageJson, String errorCode, String answer) {
        int changed = jdbcTemplate.update("""
                UPDATE agent_workflow_run SET status = ?, stage = ?, final_response = CAST(? AS jsonb),
                    usage = CAST(? AS jsonb), error_code = ?, updated_at = now(), version = version + 1
                WHERE run_id = ? AND status = 'DIFY_WORKING' AND cancel_requested = false
                  AND EXISTS (SELECT 1 FROM dify_workflow_run WHERE run_id = ? AND dispatch_state = 'BOUND')
                """, status.name(), status.name(), responseJson, usageJson, errorCode, runId, runId);
        if (changed != 1) return false;
        RunRow row = find(runId).orElseThrow();
        revokeGrantForRun(runId);
        if (status == WorkflowStatus.SUCCEEDED) {
            insertFinalMessages(runId, row.sessionId(), row.userId(), row.question(), answer, true);
        }
        insertEvent(runId, "dify:terminal", "SYSTEM", null, status.name(),
                "{\"status\":\"" + status.name() + "\"}");
        return true;
    }

    public record DifyMapping(String workflowRunId, String taskId, String dispatchState) {}

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

    public int cancel(String runId, String userId) {
        return jdbcTemplate.update("""
                UPDATE agent_workflow_run
                SET cancel_requested = TRUE, status = 'CANCELLED', stage = 'CANCELLED',
                    version = version + 1, updated_at = now()
                WHERE run_id = ? AND user_id = ?
                  AND status NOT IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED')
                """, runId, userId);
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
                  AND lease_until > now()
                  AND cancel_requested = FALSE
                  AND status NOT IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED')
                  AND (? NOT IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE') OR status = 'FINALIZING')
                """, status.name(), status.name(), finalResponseJson, usageJson,
                errorCode, errorMessage, finalizeFingerprint, claimToken,
                runId, claimToken, status.name());
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

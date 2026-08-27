package com.deepresearch.workflow;

import com.deepresearch.workflow.WorkflowDtos.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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

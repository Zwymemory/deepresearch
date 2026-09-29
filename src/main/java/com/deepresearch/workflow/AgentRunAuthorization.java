package com.deepresearch.workflow;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** B's evidence services share this live authorization gate; bodies never grant identity. */
@Service
public class AgentRunAuthorization {
    private final WorkflowAccessService access;
    private final JdbcTemplate jdbc;
    public AgentRunAuthorization(WorkflowAccessService access,JdbcTemplate jdbc) { this.access=access;this.jdbc=jdbc; }
    public record AuthorizedRun(String runId,String projectId,String tenantId,String ownerId,
                                String storageUserId,String taskId,List<String> requestedScopes) {}
    public AuthorizedRun authorizeInternal(String authorization,String runId,String claimToken,
                                          String taskId,String requiredScope) {
        access.authenticateInternal(authorization);
        UUID claim;
        try { claim=UUID.fromString(claimToken); }
        catch (Exception ignored) { throw denied(); }
        var rows=jdbc.query("""
            SELECT a.project_id,a.tenant_id,a.owner_id,r.user_id,r.requested_scopes
            FROM agent_research_run a JOIN agent_workflow_run r ON r.run_id=a.run_id
            JOIN agent_workflow_grant g ON g.run_id=r.run_id AND g.grant_id=r.grant_id
            JOIN agent_research_task t ON t.run_id=r.run_id AND t.task_id=?
            WHERE r.run_id=? AND r.claim_token=? AND r.lease_until>now() AND r.deadline_at>now()
              AND r.cancel_requested=false AND g.revoked_at IS NULL AND g.expires_at>now()
              AND r.user_id=g.subject
              AND r.status IN ('PLANNING','WORKING','REVIEWING','SYNTHESIZING')
              AND t.status<>'cancelled' AND (? IS NULL OR (?=ANY(r.requested_scopes) AND ?=ANY(g.scopes)))
            """, (rs,n)->new AuthorizedRun(runId,rs.getString("project_id"),rs.getString("tenant_id"),
                rs.getString("owner_id"),rs.getString("user_id"),taskId,
                List.copyOf(Arrays.asList((String[])rs.getArray("requested_scopes").getArray()))),
                taskId,runId,claim,requiredScope,requiredScope,requiredScope);
        if (rows.size()!=1) throw denied();
        return rows.get(0);
    }
    private ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,"Agent 运行授权不可用");
    }
}

package com.deepresearch.workflow;

import com.deepresearch.service.UserContextService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Creates an explicitly selected Agent run with server-issued project identity. */
@Service
public class AgentRunService {
    private final WorkflowService workflows;
    private final UserContextService users;
    private final JdbcTemplate jdbc;
    public AgentRunService(WorkflowService workflows, UserContextService users, JdbcTemplate jdbc) {
        this.workflows=workflows; this.users=users; this.jdbc=jdbc;
    }

    @Transactional
    public WorkflowDtos.Accepted create(WorkflowDtos.CreateRequest request, String key) {
        var principal=users.currentPrincipalRequired();
        var accepted=workflows.createAutonomous(request,key);
        if (accepted.replayed()) {
            Integer owned=jdbc.queryForObject("SELECT count(*) FROM agent_research_run WHERE run_id=? AND tenant_id=? AND owner_id=?",
                    Integer.class,accepted.runId(),principal.tenantId(),principal.userId());
            if (owned==null || owned!=1) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,"Agent 运行不存在");
            return accepted;
        }
        String candidate="project-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO research_project(project_id,tenant_id,owner_id,session_id) VALUES (?,?,?,?)
            ON CONFLICT (tenant_id,owner_id,session_id) DO NOTHING
            """,candidate,principal.tenantId(),principal.userId(),accepted.sessionId());
        String project=jdbc.queryForObject("SELECT project_id FROM research_project WHERE tenant_id=? AND owner_id=? AND session_id=?",
                String.class,principal.tenantId(),principal.userId(),accepted.sessionId());
        jdbc.update("INSERT INTO agent_research_run(run_id,project_id,tenant_id,owner_id) VALUES (?,?,?,?)",
                accepted.runId(),project,principal.tenantId(),principal.userId());
        jdbc.update("""
            UPDATE agent_workflow_run SET budget=CAST(? AS jsonb) WHERE run_id=?
            """, """
            {"runtime":"agent","maxTasks":16,"maxConcurrency":1,"maxRevisionRounds":2,
             "maxDecisionSteps":8,"maxModelCalls":16,"maxToolCalls":16,"deadlineSeconds":180,
             "maxTokens":100000,"maxCostCny":1.0,"maxInputTokens":64000,"maxOutputTokens":16384}
            """, accepted.runId());
        return accepted;
    }
}

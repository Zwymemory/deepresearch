package com.deepresearch.workflow;

import com.deepresearch.service.UserContextService;
import com.deepresearch.evidence.EvidenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Owned, read-only M2 summary and original projected records. GET never invokes a model. */
@RestController
@RequestMapping("/api/research/agents")
public class ProjectSummaryController {
    private final JdbcTemplate jdbc;
    private final UserContextService users;
    public ProjectSummaryController(JdbcTemplate jdbc, UserContextService users) {
        this.jdbc=jdbc; this.users=users;
    }
    @GetMapping("/{runId}/context-summary")
    public JsonNode view(@PathVariable String runId) {
        var principal=users.currentPrincipalRequired();
        var projects=jdbc.queryForList("""
            SELECT a.project_id FROM agent_research_run a JOIN agent_workflow_run r USING(run_id)
            WHERE a.run_id=? AND a.tenant_id=? AND a.owner_id=? AND r.user_id=?
            """,String.class,runId,principal.tenantId(),principal.userId(),principal.storageUserId());
        if(projects.size()!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"workflow 不存在");
        var rows=jdbc.query("""
            SELECT view::text FROM agent_context_summary WHERE run_id=?
            ORDER BY created_at DESC,source_sha256 DESC LIMIT 1
            """,(rs,n)->parse(rs.getString(1)),runId);
        var result=rows.isEmpty()?EvidenceJson.object("status","NOT_GENERATED","summary",null,
                "sources",java.util.List.of(),"uncovered_records",java.util.List.of()):rows.get(0).deepCopy();
        var out=(com.fasterxml.jackson.databind.node.ObjectNode)result;
        out.put("schema_version","project-context-summary-view/1");
        out.put("run_id",runId);out.put("project_id",projects.get(0));
        String hash=out.path("summary").path("summary_sha256").asText("");
        Integer recorded=hash.isBlank()?0:jdbc.queryForObject("""
            SELECT count(*) FROM agent_research_operation WHERE run_id=? AND purpose='DECISION'
            AND status='SETTLED' AND safe_result->'request_binding'->>'project_summary_sha256'=?
            """,Integer.class,runId,hash);
        out.put("planner_input_recorded",recorded!=null && recorded>0);
        return out;
    }
    private static JsonNode parse(String text) {
        try {return EvidenceJson.JSON.readTree(text);}
        catch(java.io.IOException error) {throw new IllegalStateException("Invalid stored summary",error);}
    }
}

package com.deepresearch.workflow;

import com.deepresearch.evidence.EvidenceAuthority;
import com.deepresearch.evidence.EvidenceException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Database-only proof of all research, including investigations with no criterion binding. */
public final class AgentResearchStateService {
    private final JdbcTemplate db;
    public AgentResearchStateService(JdbcTemplate db) { this.db=db; }

    public JsonNode proofForRun(String run) {
        var identities=db.queryForList("SELECT project_id,tenant_id,owner_id FROM agent_research_run WHERE run_id=?",run);
        if(identities.size()!=1) return JSON.nullNode();
        var row=identities.get(0);
        var grant=new EvidenceAuthority.Grant(new com.deepresearch.security.AuthPrincipal(
            (String)row.get("tenant_id"),(String)row.get("owner_id"),List.of()),
            (String)row.get("project_id"),run,null,null,null);
        return proof(grant,new AgentCompletionService(db).goals(grant));
    }

    /** Caller holds the run lock, keeping the snapshot stable through seal/finalize. */
    public JsonNode proof(EvidenceAuthority.Grant g,List<EvidenceAuthority.ReportGoal> goals) {
        var checks=rows(g,"""
            SELECT check_id,task_id,call_id,investigation,dispute_round,parent_check_id,
                request_fingerprint,request_sha256,request::text AS request,status,
                response_sha256,assessment_id,result::text AS result
            FROM agent_evidence_check WHERE run_id=? AND project_id=? AND tenant_id=? AND owner_id=?
            ORDER BY check_id
            ""","request","result");
        var blocked=rows(g,"""
            SELECT payload::text AS payload FROM agent_evidence_blocked_attempt
            WHERE run_id=? AND project_id=? AND tenant_id=? AND owner_id=? ORDER BY attempt_id
            ""","payload");
        // Immutable research records and source receipts are included; packets are derived views.
        var records=rows(g,"""
            SELECT record_type,record_id,version,payload_sha256,payload::text AS payload
            FROM agent_evidence_record WHERE run_id=? AND project_id=? AND tenant_id=? AND owner_id=?
              AND record_type IN ('Evidence','Claim','DecisionRecord','Challenge')
            ORDER BY record_type,record_id,version
            ""","payload");
        var reads=rows(g,"""
            SELECT receipt_id,task_id,call_id,source_id,parent_receipt_id,request_fingerprint,status,
                error_code,record_json::text AS record_json,metadata::text AS metadata
            FROM agent_evidence_read_receipt WHERE run_id=? AND project_id=? AND tenant_id=? AND owner_id=?
            ORDER BY receipt_id
            ""","record_json","metadata");
        var attempts=new ArrayList<JsonNode>();
        for(var row:db.queryForList("""
            SELECT p.investigation,p.current_call_id,o.kind,o.purpose,o.status,o.safe_result::text AS body
            FROM agent_research_investigation_progress p
            JOIN agent_research_run a ON a.run_id=p.run_id
            JOIN agent_research_operation o ON o.run_id=p.run_id AND o.operation_key=p.current_call_id AND o.attempt=p.attempt
            WHERE p.run_id=? AND a.project_id=? AND a.tenant_id=? AND a.owner_id=? ORDER BY p.investigation
            """,scope(g))) {
            String investigation=(String)row.get("investigation"),call=(String)row.get("current_call_id");
            JsonNode body=parse((String)row.get("body"));
            var latest=checks.stream().filter(c->investigation.equals(c.get("investigation")))
                .max(Comparator.comparingInt(c->((Number)c.get("dispute_round")).intValue()));
            boolean completed="TOOL".equals(row.get("kind")) && "TOOL".equals(row.get("purpose"))
                && "SETTLED".equals(row.get("status")) && !body.hasNonNull("errorCode")
                && !body.hasNonNull("error_code") && latest.isPresent();
            if(completed) {
                var check=latest.get();JsonNode result=(JsonNode)check.get("result");
                completed="COMPLETED".equals(check.get("status")) && call.equals(check.get("call_id"))
                    && body.path("check_id").asText().equals(check.get("check_id"))
                    && result!=null && result.path("records").isArray() && body.path("records").isArray()
                    && canonical(result.path("records")).equals(canonical(body.path("records")));
            }
            String state=completed?"completed":"RESERVED".equals(row.get("status"))?"pending":"failed";
            attempts.add(object("investigation_id",investigation,"call_id",call,"status",state,
                "operation_status",row.get("status"),"receipt_sha256",sha(canonical(body)),
                "reason",completed?null:state.equals("pending")?"调查尚未完成":"调查失败或结果不确定"));
        }
        var requirements=db.queryForList("SELECT manifest::text AS manifest,declaration_key,declaration_attempt FROM agent_research_requirements WHERE run_id=?",g.runId());
        for(var row:requirements) row.put("manifest",parse((String)row.get("manifest")));
        var requirementBindings=db.queryForList("SELECT requirement_id,task_id,criterion_id FROM agent_research_requirement_binding WHERE run_id=? ORDER BY requirement_id",g.runId());
        var snapshot=object("version",1,"run_id",g.runId(),"project_id",g.projectId(),"tenant_id",g.principal().tenantId(),
            "owner_id",g.principal().userId(),"checks",checks,"blocked",blocked,"records",records,
            "source_reads",reads,"investigation_attempts",attempts,"goals",goals,
            "original_requirements",requirements,"requirement_bindings",requirementBindings);
        // Publication reservations, validation receipts, seals, lease renewal and stage are not research changes.
        return object("version",1,"sha256",sha(canonical(snapshot)),"investigation_attempts",attempts);
    }
    private Object[] scope(EvidenceAuthority.Grant g) {
        return new Object[]{g.runId(),g.projectId(),g.principal().tenantId(),g.principal().userId()};
    }
    private List<Map<String,Object>> rows(EvidenceAuthority.Grant g,String sql,String... jsonColumns) {
        var rows=db.queryForList(sql,scope(g));
        for(var row:rows) for(String column:jsonColumns) row.put(column,parse((String)row.get(column)));
        return rows;
    }
    private static JsonNode parse(String value) {
        if(value==null) return JSON.nullNode();
        try{return JSON.readTree(value);}catch(Exception invalid){throw new EvidenceException("REPORT_STATE_INVALID");}
    }
}

package com.deepresearch.workflow;

import com.deepresearch.evidence.EvidenceAuthority;
import com.deepresearch.evidence.EvidenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Independently checks coverage from native standards, current B records, operations and dependencies. */
public final class AgentCompletionService {
    private final JdbcTemplate db;
    public AgentCompletionService(JdbcTemplate db) { this.db=db; }
    private record Task(String id,String text,String status,List<String> criteria,List<String> dependencies) { }
    public List<EvidenceAuthority.ReportGoal> goals(EvidenceAuthority.Grant grant) {
        var tasks=db.query("SELECT task_id,objective,status,acceptance_criteria,dependencies FROM agent_research_task WHERE run_id=? ORDER BY plan_version,task_id",
            (r,n)->new Task(r.getString(1),r.getString(2),r.getString(3),List.of((String[])r.getArray(4).getArray()),List.of((String[])r.getArray(5).getArray())),grant.runId());
        Map<String,Task> byId=new LinkedHashMap<>();tasks.forEach(t->byId.put(t.id,t));
        Map<String,EvidenceAuthority.ReportGoal> results=new HashMap<>();
        for(var task:tasks) verify(grant,task,byId,results,new HashSet<>());
        return tasks.stream().map(t->results.get(t.id)).toList();
    }
    private EvidenceAuthority.ReportGoal verify(EvidenceAuthority.Grant g,Task task,Map<String,Task> tasks,
            Map<String,EvidenceAuthority.ReportGoal> results,Set<String> trail) {
        if(results.containsKey(task.id)) return results.get(task.id);
        if(!trail.add(task.id)) return incomplete(task,List.of(),List.of("Dependency cycle has no valid completion proof"));
        var gaps=new ArrayList<String>();
        for(String dependency:task.dependencies) {
            Task prerequisite=tasks.get(dependency);
            if(prerequisite==null || !verify(g,prerequisite,tasks,results,new HashSet<>(trail)).completionVerified())
                gaps.add("Prerequisite remains incomplete: "+dependency);
        }
        var rows=db.queryForList("SELECT criterion_id,criterion_index,criterion_text,expected_claim::text AS expected_claim,expected_hash,investigation,last_call_id,dependency_snapshot::text AS dependency_snapshot FROM agent_research_criterion WHERE run_id=? AND task_id=? ORDER BY criterion_index",g.runId(),task.id);
        var standards=new ArrayList<EvidenceAuthority.ReportCriterion>();
        JsonNode dependencies=parse(db.queryForObject("SELECT agent_task_dependency_snapshot(?,?)::text",String.class,g.runId(),task.id));
        Set<String> scopes=new HashSet<>();
        for(int index=0;index<task.criteria.size();index++) {
            String text=task.criteria.get(index),id=criterionId(g.runId(),task.id,index,text);
            var matching=rows.stream().filter(r->id.equals(r.get("criterion_id"))).toList();
            if(matching.size()!=1 || !Integer.valueOf(index).equals(matching.get(0).get("criterion_index")) || !text.equals(matching.get(0).get("criterion_text"))) {
                standards.add(criterion(id,text,"uncovered",null,null,"Stored standard has no valid stable coverage identity"));continue;
            }
            var row=matching.get(0);JsonNode expected=parse((String)row.get("expected_claim"));
            if(expected==null) { standards.add(criterion(id,text,"uncovered",null,null,"No scoped Claim is bound to this stored criterion"));continue; }
            String scoped=canonical(expected);
            if(!sha(scoped).equals(row.get("expected_hash")) || !scopes.add(scoped)) {
                standards.add(criterion(id,text,"blocked",null,null,"Duplicate or invalid initial scoped Claim binding"));continue;
            }
            if(!gaps.isEmpty() || !canonical(dependencies).equals(canonical(parse((String)row.get("dependency_snapshot"))))) {
                standards.add(criterion(id,text,"stale",null,null,"Prerequisite proof changed or remains unfinished; criterion must be rechecked"));continue;
            }
            standards.add(check(g,id,text,expected,(String)row.get("investigation")));
        }
        if(rows.size()!=task.criteria.size() || task.criteria.isEmpty()) gaps.add("Stored standard coverage is missing or inconsistent");
        if(!task.status.equals("done")) gaps.add("Native task has not completed its active checks");
        boolean complete=gaps.isEmpty() && !standards.isEmpty() && standards.stream().allMatch(c->c.status().equals("resolved"));
        var goal=new EvidenceAuthority.ReportGoal(task.id,task.text,complete?"done":task.status.equals("cancelled")?"cancelled":"blocked",complete,standards,gaps);
        results.put(task.id,goal);return goal;
    }
    private EvidenceAuthority.ReportCriterion check(EvidenceAuthority.Grant g,String id,String text,JsonNode expected,String investigation) {
        var operations=db.queryForList("""
            SELECT o.status,o.safe_result::text AS body,p.current_call_id FROM agent_research_investigation_progress p
            JOIN agent_research_operation o ON o.run_id=p.run_id AND o.operation_key=p.current_call_id AND o.attempt=1
            WHERE p.run_id=? AND p.investigation=? AND o.kind='TOOL' AND o.purpose='TOOL'
            """,g.runId(),investigation);
        if(operations.size()!=1 || !"SETTLED".equals(operations.get(0).get("status")))
            return criterion(id,text,"blocked",null,null,"Latest investigation attempt failed or has not completed");
        JsonNode body=parse((String)operations.get(0).get("body"));
        if(body==null || body.hasNonNull("errorCode") || body.path("check_id").asText().isEmpty())
            return criterion(id,text,"blocked",null,null,"Latest check has no attested complete result");
        var checks=db.queryForList("""
            SELECT check_id,call_id,dispute_round,status,request::text AS request,result::text AS result FROM agent_evidence_check
            WHERE run_id=? AND project_id=? AND tenant_id=? AND owner_id=? AND investigation=? ORDER BY dispute_round DESC
            """,g.runId(),g.projectId(),g.principal().tenantId(),g.principal().userId(),investigation);
        if(checks.isEmpty() || !"COMPLETED".equals(checks.get(0).get("status")) || !body.path("check_id").asText().equals(checks.get(0).get("check_id")) || !checks.get(0).get("call_id").equals(operations.get(0).get("current_call_id")))
            return criterion(id,text,"blocked",null,null,"Current authoritative investigation check differs from the completed tool result");
        String checkId=(String)checks.get(0).get("check_id");JsonNode outcome=parse((String)checks.get(0).get("result"));
        if(outcome==null || !canonical(outcome.path("records")).equals(canonical(body.path("records"))))
            return criterion(id,text,"blocked",checkId,null,"Tool result does not match the immutable server assessment");
        var blocked=db.queryForList("SELECT payload::text AS payload FROM agent_evidence_blocked_attempt WHERE run_id=? AND project_id=? AND tenant_id=? AND owner_id=? AND investigation=?",
            g.runId(),g.projectId(),g.principal().tenantId(),g.principal().userId(),investigation);
        for(var attempt:blocked) {
            JsonNode failure=parse((String)attempt.get("payload"));boolean covered=false;
            for(var check:checks) if("COMPLETED".equals(check.get("status")) && ((Number)check.get("dispute_round")).intValue()>=failure.path("dispute_round").asInt()) {
                Set<String> seen=new HashSet<>();parse((String)check.get("request")).path("evidence").forEach(e->seen.add(e.path("evidence_id").asText()));
                boolean all=true;for(var evidence:failure.path("evidence_ids")) if(!seen.contains(evidence.asText())) all=false;
                if(all) covered=true;
            }
            if(!covered) return criterion(id,text,"blocked",checkId,null,"Durable investigation capacity gap remains unresolved");
        }
        List<JsonNode> claims=new ArrayList<>();
        for(var r:outcome.path("records")) if(r.path("record_type").asText().equals("Claim") && canonical(normalize(r)).equals(canonical(expected))) claims.add(r);
        if(claims.size()!=1) return criterion(id,text,"blocked",checkId,null,"Bound scoped Claim is absent or duplicated in the current check");
        JsonNode claim=claims.get(0);String claimId=claim.path("claim_id").asText();
        var records=db.queryForList("""
            SELECT record_type,payload::text AS payload,payload_sha256 FROM agent_evidence_record
            WHERE run_id=? AND project_id=? AND tenant_id=? AND owner_id=?
              AND ((record_type='Claim' AND record_id=?) OR (record_type='DecisionRecord' AND record_id=?))
            """,g.runId(),g.projectId(),g.principal().tenantId(),g.principal().userId(),claimId,"decision-"+claimId);
        JsonNode decision=null;boolean validClaim=false;
        for(var r:records) {
            JsonNode payload=parse((String)r.get("payload"));
            if(payload==null || !sha(canonical(payload)).equals(r.get("payload_sha256"))) continue;
            if(r.get("record_type").equals("Claim")) validClaim=canonical(payload).equals(canonical(claim));
            else decision=payload;
        }
        String status=claim.path("decision_status").asText();
        if(!validClaim || decision==null || !status.equals(decision.path("decision_status").asText())
            || !canonical(decision).equals(canonical(findDecision(outcome,claimId)))
            || !Set.of("supported","refuted").contains(status) || !decision.path("gaps").isEmpty()
            || !decision.path("unresolved_evidence_ids").isEmpty() || decision.path("adopted_evidence_ids").isEmpty())
            return criterion(id,text,"blocked",checkId,claimId,"Current scoped adjudication remains unresolved or its proof is invalid");
        return criterion(id,text,"resolved",checkId,claimId,null);
    }
    private static JsonNode findDecision(JsonNode outcome,String claimId) {
        for(var row:outcome.path("records")) if(row.path("record_type").asText().equals("DecisionRecord") && row.path("claim_id").asText().equals(claimId)) return row;
        return JSON.nullNode();
    }
    private static EvidenceAuthority.ReportCriterion criterion(String id,String text,String status,String check,String claim,String gap) {
        return new EvidenceAuthority.ReportCriterion(id,text,status,check==null?List.of():List.of(check),claim==null?List.of():List.of(claim),gap==null?List.of():List.of(gap));
    }
    private static EvidenceAuthority.ReportGoal incomplete(Task t,List<EvidenceAuthority.ReportCriterion> criteria,List<String> gaps) {
        return new EvidenceAuthority.ReportGoal(t.id,t.text,"blocked",false,criteria,gaps);
    }
    public static String criterionId(String run,String task,int index,String text) {
        return "criterion-"+sha(canonical(object("run_id",run,"task_id",task,"index",index,"text",text))).substring(0,48);
    }
    private static JsonNode parse(String value) {
        if(value==null) return null;
        try{return EvidenceJson.JSON.readTree(value);}catch(Exception malformed){return null;}
    }
    public static JsonNode normalize(JsonNode claim) {
        var scope=(com.fasterxml.jackson.databind.node.ObjectNode)claim.path("applicability").deepCopy();
        TreeSet<String> conditions=new TreeSet<>();scope.path("conditions").forEach(c->conditions.add(c.asText()));scope.set("conditions",JSON.valueToTree(conditions));
        for(String field:List.of("version","valid_at")) if(scope.path(field).path("status").asText().equals("unknown")) ((com.fasterxml.jackson.databind.node.ObjectNode)scope.path(field)).put("reason","Not independently established");
        return object("text",claim.path("text"),"kind",claim.path("kind"),"applicability",scope);
    }
}

package com.deepresearch.workflow;

import com.deepresearch.evidence.EvidenceAuthority;
import com.deepresearch.evidence.EvidenceDtos;
import com.deepresearch.evidence.EvidenceException;
import com.deepresearch.evidence.EvidenceJson;
import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** Live control-plane adapter. Only Java-owned search receipts supply source locators. */
@Service
@ConditionalOnProperty(name="deepresearch.agent.evidence.enabled",havingValue="true")
public class AgentEvidenceAuthority implements EvidenceAuthority {
    private final AgentRunAuthorization runs;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public AgentEvidenceAuthority(AgentRunAuthorization runs,JdbcTemplate db,
                                  org.springframework.transaction.PlatformTransactionManager manager) {
        this.runs=runs; this.db=db; this.tx=new TransactionTemplate(manager);
    }
    @Override public Grant authorize(String header,String operation,EvidenceDtos.Identifiers ids) {
        if (ids==null || ids.call_id()==null || !ids.call_id().matches("tool-[0-9a-f]{32}")) throw EvidenceException.denied();
        String scope=switch(operation) {
            case "read_source" -> "read_source";
            case "check_claims","record_packet","publish_evidence" -> "check_claims";
            default -> throw EvidenceException.denied();
        };
        var run=runs.authorizeInternal(header,ids.run_id(),ids.claim_token(),ids.task_id(),scope);
        if (!run.projectId().equals(ids.project_id())) throw EvidenceException.denied();
        var grant=new Grant(new AuthPrincipal(run.tenantId(),run.ownerId(),List.of()),run.projectId(),
                run.runId(),run.taskId(),ids.call_id(),ids.claim_token());
        if (!active(grant) || (operation.equals("publish_evidence") && !purpose(grant).equals("PUBLICATION")))
            throw EvidenceException.denied();
        return grant;
    }
    @Override public boolean active(Grant g) {
        return db.queryForObject("""
            SELECT count(*) FROM agent_research_run a JOIN agent_workflow_run r ON r.run_id=a.run_id
            JOIN agent_workflow_grant x ON x.grant_id=r.grant_id AND x.run_id=r.run_id
            JOIN agent_research_task t ON t.run_id=r.run_id AND t.task_id=?
            JOIN agent_research_operation o ON o.run_id=r.run_id AND o.operation_key=?
            WHERE r.run_id=? AND a.project_id=? AND a.tenant_id=? AND a.owner_id=?
              AND r.claim_token=?::uuid AND r.lease_until>now() AND r.deadline_at>now()
              AND NOT r.cancel_requested AND x.revoked_at IS NULL AND x.expires_at>now()
              AND r.status IN ('PLANNING','WORKING','REVIEWING','SYNTHESIZING') AND t.status<>'cancelled'
              AND o.kind='TOOL' AND o.status IN ('RESERVED','SETTLED') AND o.claim_token=?::uuid
              AND o.attempt=(SELECT max(attempt) FROM agent_research_operation WHERE run_id=r.run_id AND operation_key=o.operation_key)
            """,Integer.class,g.taskId(),g.callId(),g.runId(),g.projectId(),g.principal().tenantId(),
                g.principal().userId(),g.claimToken(),g.claimToken())==1;
    }
    String purpose(Grant g) {
        return db.queryForObject("SELECT purpose FROM agent_research_operation WHERE run_id=? AND operation_key=? ORDER BY attempt DESC LIMIT 1",
                String.class,g.runId(),g.callId());
    }
    @Override public Candidate candidate(Grant g,String sourceId) {
        if (!active(g)) throw EvidenceException.denied();
        var receipts=db.queryForList("""
            SELECT r.call_id,r.tool_name,r.mcp_safe_result::text AS body FROM agent_workflow_tool_receipt r
            JOIN agent_research_operation o ON o.run_id=r.run_id AND o.operation_key=r.call_id
            WHERE r.run_id=? AND r.mcp_execution_status='COMPLETED' AND o.status='SETTLED'
              AND o.kind='TOOL' AND o.purpose='TOOL' AND r.tool_name IN ('kb_search','web_search') ORDER BY r.mcp_completed_at DESC
            """,g.runId());
        for (var row:receipts) {
            JsonNode body;
            try { body=EvidenceJson.JSON.readTree((String)row.get("body")); }
            catch (Exception invalid) { throw EvidenceException.denied(); }
            if (!body.path("success").asBoolean()) continue;
            for (JsonNode item:body.path("evidence")) {
                String original=item.path("evidenceId").asText();
                String bounded=original.substring(0,original.offsetByCodePoints(0,Math.min(300,original.codePointCount(0,original.length()))));
                String identity=bounded.codePointCount(0,bounded.length())<=128?bounded:"source-"+EvidenceJson.sha(bounded);
                if (!sourceId.equals(identity)) continue;
                String locator=item.path("uriOrChunkKey").asText();
                String tool=(String)row.get("tool_name");
                String scope=tool;
                Integer allowed=db.queryForObject("SELECT count(*) FROM agent_workflow_run WHERE run_id=? AND ?=ANY(requested_scopes)",Integer.class,g.runId(),scope);
                if (allowed!=1) throw EvidenceException.denied();
                if (tool.equals("web_search")) {
                    if (!(locator.startsWith("https://") || locator.startsWith("http://"))) throw EvidenceException.denied();
                    return new Candidate(sourceId,"web",locator,null,null,null,item.path("title").asText(),(String)row.get("call_id"));
                }
                String[] parts=(locator.startsWith("ragflow:")?"kb:"+locator:locator).split(":",-1);
                if (parts.length!=5 || !parts[0].equals("kb") || !parts[1].equals("ragflow")
                        || parts[2].isBlank() || parts[3].isBlank() || parts[4].isBlank()) throw EvidenceException.denied();
                return new Candidate(sourceId,"knowledge",null,parts[2],parts[3],parts[4],item.path("title").asText(),(String)row.get("call_id"));
            }
        }
        throw EvidenceException.denied();
    }
    @Override public PublicationReadPermit publicationRead(Grant g,JsonNode evidence) {
        return tx.execute(status->{
            lock(g); if (!active(g)) throw EvidenceException.denied();
            if (!purpose(g).equals("PUBLICATION") || !g.runId().equals(evidence.path("run_id").asText())
                    || !g.projectId().equals(evidence.path("project_id").asText())
                    || !g.principal().tenantId().equals(evidence.path("tenant_id").asText())
                    || !g.principal().userId().equals(evidence.path("owner_id").asText())
                    || !"knowledge".equals(evidence.path("source").path("kind").asText())) throw EvidenceException.denied();
            String evidenceId=EvidenceJson.id(evidence.path("evidence_id").asText());
            String expected=evidence.path("snapshot").path("sha256").asText();
            if (!expected.equals(EvidenceJson.sha(evidence.path("snapshot").path("text").asText()))) throw EvidenceException.denied();
            String fingerprint=EvidenceJson.sha(EvidenceJson.canonical(EvidenceJson.object("run",g.runId(),"project",g.projectId(),
                    "task",g.taskId(),"call",g.callId(),"evidence",evidenceId,"snapshot",expected,"receipt",evidence.path("receipt_id").asText())));
            String key="server:publication-read:"+EvidenceJson.sha(g.runId()+":"+g.callId()+":"+evidenceId).substring(0,48);
            var prior=db.queryForList("SELECT request_hash,status,snapshot_hash FROM agent_research_source_validation WHERE run_id=? AND operation_id=?",g.runId(),key);
            if (!prior.isEmpty()) {
                var previous=prior.get(0);
                if (!fingerprint.equals(previous.get("request_hash"))) throw EvidenceException.denied();
                if (previous.get("status").equals("COMPLETED")) return new PublicationReadPermit(key,(String)previous.get("snapshot_hash"));
                throw new EvidenceException("PUBLICATION_READ_UNKNOWN");
            }
            var row=db.queryForMap("SELECT budget,(SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='TOOL') AS n FROM agent_workflow_run WHERE run_id=?",g.runId(),g.runId());
            JsonNode budget;
            try { budget=EvidenceJson.JSON.readTree(row.get("budget").toString()); }
            catch (Exception invalid) { throw EvidenceException.denied(); }
            if (((Number)row.get("n")).longValue()>=budget.path("maxToolCalls").asLong())
                throw new EvidenceException("AGENT_BUDGET_EXCEEDED");
            db.update("""
                INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token)
                VALUES (?,?,1,'TOOL','PUBLICATION',?,'RESERVED',0,0,?::uuid)
                """,g.runId(),key,fingerprint,g.claimToken());
            db.update("""
                INSERT INTO agent_research_source_validation(run_id,operation_id,parent_call_id,task_id,evidence_id,project_id,tenant_id,owner_id,
                    request_hash,expected_snapshot_hash,status,claim_token)
                VALUES (?,?,?,?,?,?,?,?,?,?,'EXECUTING',?::uuid)
                """,g.runId(),key,g.callId(),g.taskId(),evidenceId,g.projectId(),g.principal().tenantId(),g.principal().userId(),fingerprint,expected,g.claimToken());
            return new PublicationReadPermit(key,null);
        });
    }
    @Override public void completePublicationRead(Grant g,PublicationReadPermit permit,String snapshotHash,String errorCode) {
        tx.executeWithoutResult(status->{
            lock(g);if (!active(g) || !purpose(g).equals("PUBLICATION") || permit==null) throw EvidenceException.denied();
            if ((errorCode==null && (snapshotHash==null || !snapshotHash.matches("[0-9a-f]{64}")))
                    || (errorCode!=null && (snapshotHash!=null || !errorCode.matches("[A-Z0-9_]{1,64}")))) throw EvidenceException.denied();
            int n=db.update("""
                UPDATE agent_research_source_validation SET status=?,snapshot_hash=?,error_code=?,completed_at=now()
                WHERE run_id=? AND operation_id=? AND parent_call_id=? AND task_id=? AND project_id=? AND tenant_id=? AND owner_id=?
                    AND status='EXECUTING' AND claim_token=?::uuid
                """,errorCode==null?"COMPLETED":"FAILED",snapshotHash,errorCode,g.runId(),permit.operationId(),g.callId(),g.taskId(),g.projectId(),
                    g.principal().tenantId(),g.principal().userId(),g.claimToken());
            if (n!=1) throw EvidenceException.denied();
            JsonNode result=EvidenceJson.object("snapshot_sha256",snapshotHash,"error_code",errorCode);
            n=db.update("""
                UPDATE agent_research_operation SET status='SETTLED',safe_result=CAST(? AS jsonb),actual_usage='{}'::jsonb,settled_at=now()
                WHERE run_id=? AND operation_key=? AND status='RESERVED' AND claim_token=?::uuid
                """,EvidenceJson.canonical(result),g.runId(),permit.operationId(),g.claimToken());
            if (n!=1) throw EvidenceException.denied();
        });
    }
    void lock(Grant g) { db.queryForList("SELECT run_id FROM agent_workflow_run WHERE run_id=? FOR UPDATE",g.runId()); }
    @Override public void commitRead(Grant g,String sourceId,JsonNode evidence,String receiptId) {
        lock(g); if (!active(g) || !receiptId.equals(evidence.path("receipt_id").asText())
                || !sourceId.equals(evidence.path("source").path("source_id").asText())) throw EvidenceException.denied();
        JsonNode result=EvidenceJson.object("records",List.of(evidence));
        settle(g,result);
    }
    void settle(Grant g,JsonNode result) {
        int n=db.update("""
            UPDATE agent_research_operation SET status='SETTLED',safe_result=CAST(? AS jsonb),actual_usage='{}'::jsonb,settled_at=now()
            WHERE run_id=? AND operation_key=? AND kind='TOOL' AND status='RESERVED' AND claim_token=?::uuid
            """,EvidenceJson.canonical(result),g.runId(),g.callId(),g.claimToken());
        if (n!=1) throw EvidenceException.denied();
    }
    @Override public String modelReceipt(Grant g,String checkId,String requestHash,String modelId,String responseHash) {
        if (!active(g)) throw EvidenceException.denied();
        var rows=db.queryForList("SELECT safe_result::text AS body FROM agent_research_operation WHERE run_id=? AND operation_key=? AND kind='MODEL' AND purpose='CHECK' AND status='SETTLED' ORDER BY attempt DESC LIMIT 1",g.runId(),modelId);
        if (rows.size()!=1) throw EvidenceException.denied();
        try {
            JsonNode value=EvidenceJson.JSON.readTree((String)rows.get(0).get("body"));
            var binding=value.path("request_binding");
            if (!checkId.equals(binding.path("check_id").asText()) || !requestHash.equals(binding.path("request_sha256").asText())
                    || !responseHash.equals(binding.path("response_sha256").asText())
                    || !responseHash.equals(EvidenceJson.sha(EvidenceJson.canonical(value.path("value"))))) throw EvidenceException.denied();
            return "assessment-"+EvidenceJson.sha(g.runId()+":"+modelId+":"+responseHash);
        } catch (EvidenceException denied) { throw denied; }
        catch (Exception invalid) { throw EvidenceException.denied(); }
    }
}

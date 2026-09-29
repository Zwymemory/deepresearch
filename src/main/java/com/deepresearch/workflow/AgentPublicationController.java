package com.deepresearch.workflow;

import com.deepresearch.evidence.EvidenceDtos;
import com.deepresearch.evidence.EvidenceException;
import com.deepresearch.evidence.EvidenceJson;
import com.deepresearch.evidence.EvidenceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Seals only the exact deterministic answer returned by the server evidence publisher. */
@RestController
@ConditionalOnProperty(name="deepresearch.agent.evidence.enabled",havingValue="true")
public class AgentPublicationController {
    private final AgentEvidenceAuthority authority;
    private final EvidenceService evidence;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public AgentPublicationController(AgentEvidenceAuthority authority,EvidenceService evidence,JdbcTemplate db,
                                      org.springframework.transaction.PlatformTransactionManager manager) {
        this.authority=authority;this.evidence=evidence;this.db=db;this.tx=new TransactionTemplate(manager);
    }
    @ExceptionHandler(EvidenceException.class)
    public ResponseEntity<Map<String,String>> rejected(EvidenceException failure) {
        int status=failure.code().equals("AGENT_BUDGET_EXCEEDED")?429:409;
        return ResponseEntity.status(status).body(Map.of("errorCode",failure.code()));
    }
    @PostMapping("/internal/agent/publication")
    public JsonNode publish(@RequestHeader("Authorization") String header,@RequestBody EvidenceDtos.ReportRequest request) {
        var g=authority.authorize(header,"publish_evidence",request.identifiers());
        String fingerprint=sha("whole-report:"+canonical(JSON.valueToTree(request)));
        JsonNode replay=tx.execute(status->{
            authority.lock(g); if (!authority.active(g)) throw EvidenceException.denied();
            var prior=db.queryForList("SELECT request_hash,status,result::text AS result FROM agent_research_publication WHERE run_id=? AND call_id=?",g.runId(),g.callId());
            if (!prior.isEmpty()) {
                var row=prior.get(0);
                if (!fingerprint.equals(row.get("request_hash"))) throw new EvidenceException("PUBLICATION_IDEMPOTENCY_CONFLICT");
                if (!row.get("status").equals("COMPLETED")) throw new EvidenceException("PUBLICATION_RESULT_UNKNOWN");
                try { return JSON.readTree((String)row.get("result")); }
                catch (Exception invalid) { throw new EvidenceException("PUBLICATION_INVALID"); }
            }
            db.update("INSERT INTO agent_research_publication(run_id,call_id,request_hash,status) VALUES (?,?,?,'EXECUTING')",g.runId(),g.callId(),fingerprint);
            return null;
        });
        if (replay!=null) return replay;
        try {
            JsonNode checked=evidence.report(header,request);
            String answer=checked.path("answer").asText();
            String reportStatus=checked.path("report_status").asText();
            String terminalStatus=checked.path("terminal_status").asText();
            if (!checked.path("approved").asBoolean() || answer.isBlank()
                    || answer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768
                    || !sha(answer).equals(checked.path("answer_sha256").asText())
                    || !java.util.Set.of("complete","partial","insufficient").contains(reportStatus)
                    || !java.util.Set.of("SUCCEEDED","INSUFFICIENT_EVIDENCE").contains(terminalStatus)
                    || (reportStatus.equals("complete")!=terminalStatus.equals("SUCCEEDED")))
                throw new EvidenceException("PUBLICATION_INVALID");
            var citations=new ArrayList<String>();
            for (JsonNode citation:checked.path("citations")) {
                JsonNode locator=citation.path("source").path("locator");
                String identity=switch(citation.path("source").path("kind").asText()) {
                    case "web" -> locator.path("uri").asText();
                    case "knowledge" -> "kb:ragflow:"+locator.path("dataset_id").asText()+":"+locator.path("document_id").asText()+":"+locator.path("chunk_id").asText();
                    default -> throw EvidenceException.denied();
                };
                if (identity.isBlank()) throw new EvidenceException("PUBLICATION_INVALID");
                if (!citations.contains(identity)) citations.add(identity);
            }
            if (citations.size()>32 || (terminalStatus.equals("SUCCEEDED") && citations.isEmpty()))
                throw new EvidenceException("PUBLICATION_INVALID");
            var result=((com.fasterxml.jackson.databind.node.ObjectNode)checked).deepCopy();
            result.set("citation_details",checked.path("citations"));
            result.set("citations",JSON.valueToTree(citations));
            return tx.execute(status->{
                authority.lock(g); if (!authority.active(g)) throw EvidenceException.denied();
                db.update("""
                    UPDATE agent_research_publication SET status='COMPLETED',answer_hash=?,citations=CAST(? AS jsonb),result=CAST(? AS jsonb),proof=CAST(? AS jsonb),completed_at=now()
                    WHERE run_id=? AND call_id=? AND status='EXECUTING'
                    """,sha(answer),canonical(JSON.valueToTree(citations)),canonical(result),canonical(checked),g.runId(),g.callId());
                authority.settle(g,result); return result;
            });
        } catch (RuntimeException failure) {
            tx.executeWithoutResult(status->{
                authority.lock(g);
                db.update("UPDATE agent_research_publication SET status='UNKNOWN' WHERE run_id=? AND call_id=? AND status='EXECUTING'",g.runId(),g.callId());
                db.update("""
                    UPDATE agent_research_operation o SET status='UNKNOWN',settled_at=now()
                    FROM agent_research_source_validation v WHERE v.run_id=o.run_id AND v.operation_id=o.operation_key
                    AND v.run_id=? AND v.parent_call_id=? AND v.status='EXECUTING' AND o.status='RESERVED'
                    """,g.runId(),g.callId());
                db.update("UPDATE agent_research_source_validation SET status='UNKNOWN',completed_at=now() WHERE run_id=? AND parent_call_id=? AND status='EXECUTING'",g.runId(),g.callId());
            });
            throw failure;
        }
    }
    /** Legacy callers cannot use their selection to hide other investigations. */
    public JsonNode publish(String header,EvidenceDtos.PublishRequest request) {
        return publish(header,new EvidenceDtos.ReportRequest(request.identifiers()));
    }
}

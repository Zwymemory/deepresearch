package com.deepresearch.evidence.publicview;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

/** All child reads use the server-derived four-part identity; never uses an execution grant. */
@ConditionalOnProperty(name = {"deepresearch.workflow.enabled", "deepresearch.agent.evidence.enabled"}, havingValue = "true")
@Repository
public class EvidenceViewQuery {
    static final int RECORDS = 256, CHECKS = 128, READS = 128, BLOCKED = 128, RESPONSE_BYTES = 262144;
    private final JdbcTemplate db;
    public EvidenceViewQuery(JdbcTemplate db) { this.db = db; }
    record Scope(String tenant, String owner, String project, String run) {
        Object[] args() { return new Object[]{tenant, owner, project, run}; }
    }
    Scope scope(String run, String tenant, String owner) {
        return db.query("SELECT project_id FROM agent_research_run WHERE run_id=? AND tenant_id=? AND owner_id=?",
                (rs, n) -> new Scope(tenant, owner, rs.getString(1), run), run, tenant, owner)
                .stream().findFirst().orElse(null);
    }
    List<Map<String, Object>> records(Scope s) {
        return rows(s, """
                SELECT record_type,record_id,version,payload_sha256,read_receipt_id,payload::text AS payload,created_at
                FROM agent_evidence_record WHERE tenant_id=? AND owner_id=? AND project_id=? AND run_id=?
                ORDER BY record_type,record_id,version LIMIT 257
                """, RECORDS);
    }
    List<Map<String, Object>> checks(Scope s) {
        return rows(s, """
                SELECT check_id,investigation,dispute_round,parent_check_id,status,request_sha256,
                    request::text AS request,result::text AS result,created_at,completed_at
                FROM agent_evidence_check WHERE tenant_id=? AND owner_id=? AND project_id=? AND run_id=?
                ORDER BY investigation,dispute_round,check_id LIMIT 129
                """, CHECKS);
    }
    List<Map<String, Object>> reads(Scope s) {
        return rows(s, """
                SELECT receipt_id,source_id,parent_receipt_id,status,record_json::text AS record_json,
                    metadata::text AS metadata FROM agent_evidence_read_receipt
                WHERE tenant_id=? AND owner_id=? AND project_id=? AND run_id=? ORDER BY receipt_id LIMIT 129
                """, READS);
    }
    List<Map<String, Object>> blocked(Scope s) {
        return rows(s, """
                SELECT attempt_id,investigation,payload::text AS payload,payload_sha256,created_at
                FROM agent_evidence_blocked_attempt WHERE tenant_id=? AND owner_id=? AND project_id=? AND run_id=?
                ORDER BY attempt_id LIMIT 129
                """, BLOCKED);
    }
    // The finalized public response is verified against its existing seal, without selecting unpublished prose.
    List<Map<String, Object>> publications(Scope s) {
        return rows(s, """
                SELECT p.answer_hash,p.citations::text AS citations,p.result::text AS result,p.proof::text AS proof
                FROM agent_research_publication p JOIN agent_research_run a ON a.run_id=p.run_id
                WHERE a.tenant_id=? AND a.owner_id=? AND a.project_id=? AND a.run_id=? AND p.status='COMPLETED'
                ORDER BY p.completed_at,p.call_id LIMIT 129
                """, CHECKS);
    }
    private List<Map<String, Object>> rows(Scope s, String sql, int limit) {
        var rows = db.queryForList(sql, s.args());
        if (rows.size() > limit) throw new CapacityFailure();
        return rows;
    }
    static final class CapacityFailure extends RuntimeException { }
}

package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Fenced database transactions; provider I/O happens outside these transactions. */
public final class JdbcEvidenceStore implements EvidenceStore {
    private static final Map<String, String> IDS = Map.of("Evidence", "evidence_id", "Claim", "claim_id",
            "DecisionRecord", "decision_id", "Challenge", "challenge_id", "ResearchPacket", "packet_id");
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    public JdbcEvidenceStore(JdbcTemplate db, TransactionTemplate transactions) { this.db = db; this.transactions = transactions; }
    @Override public <T> T transaction(EvidenceAuthority.Grant g, Supplier<T> work) {
        return transactions.execute(tx -> {
            var rows = db.query("""
                SELECT run_id FROM agent_workflow_run WHERE run_id=? AND user_id=? AND claim_token=?
                    AND lease_until>now() AND deadline_at>now() AND cancel_requested=false
                    AND status NOT IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED','DISPATCH_UNKNOWN')
                FOR UPDATE
                """, (rs, n) -> rs.getString(1), g.runId(), g.principal().storageUserId(), UUID.fromString(g.claimToken()));
            if (rows.isEmpty()) throw EvidenceException.denied();
            return work.get();
        });
    }
    @Override public ReadState beginRead(EvidenceAuthority.Grant g, String source, String parent, String fingerprint) {
        String receipt = "read-" + UUID.randomUUID();
        db.update("""
                INSERT INTO agent_evidence_read_receipt(receipt_id,tenant_id,owner_id,project_id,run_id,task_id,call_id,
                    claim_token,source_id,parent_receipt_id,request_fingerprint,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'EXECUTING') ON CONFLICT(run_id,call_id) DO NOTHING
                """, receipt, g.principal().tenantId(), g.principal().userId(), g.projectId(), g.runId(), g.taskId(), g.callId(),
                UUID.fromString(g.claimToken()), source, parent, fingerprint);
        return db.query("""
                SELECT receipt_id,status,request_fingerprint,record_json,task_id,source_id FROM agent_evidence_read_receipt
                WHERE tenant_id=? AND owner_id=? AND project_id=? AND run_id=? AND call_id=?
                """, (rs, n) -> {
            if (!g.taskId().equals(rs.getString("task_id")) || !source.equals(rs.getString("source_id"))) throw new EvidenceException("READ_IDEMPOTENCY_CONFLICT");
            String id = rs.getString("receipt_id"), status = rs.getString("status");
            return new ReadState(id, id.equals(receipt) ? "NEW" : status, rs.getString("request_fingerprint"), parse(rs.getString("record_json")));
        }, g.principal().tenantId(), g.principal().userId(), g.projectId(), g.runId(), g.callId()).stream().findFirst().orElseThrow(EvidenceException::denied);
    }
    @Override public void completeRead(EvidenceAuthority.Grant g, String receipt, String fingerprint, JsonNode record, JsonNode metadata) {
        int changed = db.update("""
                UPDATE agent_evidence_read_receipt SET status='COMPLETED',record_json=?::jsonb,metadata=?::jsonb,completed_at=now()
                WHERE receipt_id=? AND tenant_id=? AND owner_id=? AND project_id=? AND run_id=? AND task_id=?
                    AND call_id=? AND claim_token=? AND request_fingerprint=? AND status='EXECUTING'
                """, EvidenceJson.canonical(record), EvidenceJson.canonical(metadata), receipt, g.principal().tenantId(), g.principal().userId(),
                g.projectId(), g.runId(), g.taskId(), g.callId(), UUID.fromString(g.claimToken()), fingerprint);
        if (changed != 1) throw new EvidenceException("READ_COMPLETION_CONFLICT");
        put(g, record);
    }
    @Override public void failRead(EvidenceAuthority.Grant g, String receipt, String code) {
        db.update("""
                UPDATE agent_evidence_read_receipt SET status='FAILED',error_code=?,completed_at=now()
                WHERE receipt_id=? AND tenant_id=? AND owner_id=? AND project_id=? AND run_id=? AND status='EXECUTING'
                """, code, receipt, g.principal().tenantId(), g.principal().userId(), g.projectId(), g.runId());
    }
    @Override public JsonNode readMetadata(EvidenceAuthority.Grant g, String receipt) {
        return db.query("""
                SELECT metadata FROM agent_evidence_read_receipt WHERE receipt_id=? AND tenant_id=? AND owner_id=?
                    AND project_id=? AND run_id=? AND status='COMPLETED'
                """, (rs, n) -> parse(rs.getString(1)), receipt, g.principal().tenantId(), g.principal().userId(), g.projectId(), g.runId())
                .stream().findFirst().orElseThrow(EvidenceException::denied);
    }
    @Override public void put(EvidenceAuthority.Grant g, JsonNode record) {
        String kind = record.path("record_type").asText(), id = record.path(IDS.getOrDefault(kind, "")).asText();
        if (!IDS.containsKey(kind) || !record.path("tenant_id").asText().equals(g.principal().tenantId())
                || !record.path("owner_id").asText().equals(g.principal().userId()) || !record.path("project_id").asText().equals(g.projectId())
                || !record.path("run_id").asText().equals(g.runId())) throw EvidenceException.denied();
        String raw = EvidenceJson.canonical(record), hash = EvidenceJson.sha(raw);
        db.update("""
                INSERT INTO agent_evidence_record(tenant_id,owner_id,project_id,record_type,record_id,version,run_id,read_receipt_id,payload,payload_sha256)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING
                """, g.principal().tenantId(), g.principal().userId(), g.projectId(), kind, EvidenceJson.id(id), record.path("version").asInt(1),
                g.runId(), kind.equals("Evidence") ? record.path("receipt_id").asText() : null, raw, hash);
        if (!hash.equals(EvidenceJson.sha(EvidenceJson.canonical(get(g, kind, id))))) throw new EvidenceException("RECORD_IMMUTABLE_CONFLICT");
    }
    @Override public JsonNode get(EvidenceAuthority.Grant g, String type, String id) {
        return db.query("""
                SELECT payload,payload_sha256 FROM agent_evidence_record WHERE tenant_id=? AND owner_id=? AND project_id=?
                    AND run_id=? AND record_type=? AND record_id=? ORDER BY version DESC LIMIT 1
                """, (rs, n) -> {
            JsonNode record = parse(rs.getString(1));
            if (!rs.getString(2).equals(EvidenceJson.sha(EvidenceJson.canonical(record)))) throw new EvidenceException("RECORD_HASH_CHANGED");
            return record;
        }, g.principal().tenantId(), g.principal().userId(), g.projectId(), g.runId(), type, id).stream().findFirst().orElseThrow(EvidenceException::denied);
    }
    @Override public CheckState prepare(EvidenceAuthority.Grant g, String id, String investigation, int round, String parent,
                                        String fingerprint, String hash, JsonNode request) {
        if (round > 0) {
            var previous = db.query("""
                SELECT dispute_round FROM agent_evidence_check WHERE check_id=? AND tenant_id=? AND owner_id=? AND project_id=?
                    AND run_id=? AND investigation=? AND status='COMPLETED'
                """, (rs,n) -> rs.getInt(1), parent, g.principal().tenantId(), g.principal().userId(), g.projectId(), g.runId(), investigation);
            if (previous.size() != 1 || previous.get(0) != round - 1) throw new EvidenceException("DISPUTE_PARENT_INVALID");
        }
        int changed = db.update("""
                INSERT INTO agent_evidence_check(check_id,tenant_id,owner_id,project_id,run_id,task_id,call_id,investigation,
                    dispute_round,parent_check_id,request_fingerprint,request_sha256,request,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,'AWAITING_MODEL') ON CONFLICT DO NOTHING
                """, id, g.principal().tenantId(), g.principal().userId(), g.projectId(), g.runId(), g.taskId(), g.callId(),
                investigation, round, parent, fingerprint, hash, EvidenceJson.canonical(request));
        if (changed == 0) {
            var fingerprints = db.query("SELECT request_fingerprint,task_id,call_id FROM agent_evidence_check WHERE check_id=?",
                    (rs, n) -> rs.getString(1).equals(fingerprint) && rs.getString(2).equals(g.taskId()) && rs.getString(3).equals(g.callId()), id);
            if (fingerprints.size() != 1 || !fingerprints.get(0)) throw new EvidenceException("CHECK_IDEMPOTENCY_CONFLICT");
        }
        return check(g, id);
    }
    @Override public CheckState check(EvidenceAuthority.Grant g, String id) {
        return db.query("""
                SELECT check_id,status,request_sha256,request,response_sha256,result FROM agent_evidence_check
                WHERE check_id=? AND tenant_id=? AND owner_id=? AND project_id=? AND run_id=?
                """, (rs,n) -> new CheckState(rs.getString(1),rs.getString(2),rs.getString(3),parse(rs.getString(4)),rs.getString(5),parse(rs.getString(6))),
                id,g.principal().tenantId(),g.principal().userId(),g.projectId(),g.runId()).stream().findFirst().orElseThrow(EvidenceException::denied);
    }
    @Override public void completeCheck(EvidenceAuthority.Grant g, String id, String responseHash, String assessment, JsonNode result) {
        int changed = db.update("""
                UPDATE agent_evidence_check SET status='COMPLETED',response_sha256=?,assessment_id=?,result=?::jsonb,completed_at=now()
                WHERE check_id=? AND tenant_id=? AND owner_id=? AND project_id=? AND run_id=? AND status='AWAITING_MODEL'
                """, responseHash,assessment,EvidenceJson.canonical(result),id,g.principal().tenantId(),g.principal().userId(),g.projectId(),g.runId());
        if (changed != 1) throw new EvidenceException("CHECK_COMPLETION_CONFLICT");
    }
    private static JsonNode parse(String raw) {
        if (raw == null) return null;
        try { return EvidenceJson.JSON.readTree(raw); }
        catch (Exception invalid) { throw new EvidenceException("EVIDENCE_STORED_JSON_INVALID"); }
    }
}

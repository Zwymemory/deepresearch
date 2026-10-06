package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Uses existing project/run/session ownership. Progress never enters evidence tables. */
@Repository
public class ResearchProgressRepository {
    private final JdbcTemplate db;
    public ResearchProgressRepository(JdbcTemplate db) { this.db=db; }
    String projectForRun(String run, AuthPrincipal p) {
        var rows=db.queryForList("""
            SELECT r.project_id FROM agent_research_run r
            JOIN agent_workflow_run w ON w.run_id=r.run_id
            JOIN research_project p ON p.project_id=r.project_id AND p.tenant_id=r.tenant_id AND p.owner_id=r.owner_id
            WHERE r.run_id=? AND r.tenant_id=? AND r.owner_id=? AND w.user_id=?
            """,String.class,run,p.tenantId(),p.userId(),p.storageUserId());
        if(rows.size()!=1) throw unavailable();
        return rows.get(0);
    }
    String createSession(AuthPrincipal p) {
        String session=UUID.randomUUID().toString();
        db.update("INSERT INTO agent_session(session_id,user_id,title) VALUES (?,?,?)",
            session,p.storageUserId(),"研究进度上下文（尚未开始研究）");
        return session;
    }
    void requireProject(String project, AuthPrincipal p) {
        if (db.queryForObject("SELECT count(*) FROM research_project WHERE project_id=? AND tenant_id=? AND owner_id=?",
                Integer.class, project, p.tenantId(), p.userId()) != 1) throw unavailable();
    }
    // A fresh owned session may request this project's context explicitly. A session already
    // associated with another project must not receive it, even for the same owner.
    void requireSession(String project, String session, AuthPrincipal p) {
        if (db.queryForObject("""
                SELECT count(*) FROM agent_session s WHERE s.session_id=? AND s.user_id=?
                AND NOT EXISTS (SELECT 1 FROM research_project p WHERE p.session_id=s.session_id
                    AND p.tenant_id=? AND p.owner_id=? AND p.project_id<>?)
                AND NOT EXISTS (SELECT 1 FROM agent_workflow_run w JOIN agent_research_run r ON r.run_id=w.run_id
                    WHERE w.session_id=s.session_id AND r.project_id<>?)
                """, Integer.class, session, p.storageUserId(), p.tenantId(), p.userId(), project, project) != 1)
            throw unavailable();
    }
    Map<String,Object> ownedRun(String project, String run, AuthPrincipal p, boolean lock) {
        var rows=db.queryForList("""
            SELECT w.question,w.status,w.error_code,w.session_id
            FROM agent_workflow_run w JOIN agent_research_run r ON r.run_id=w.run_id
            JOIN research_project p ON p.project_id=r.project_id AND p.tenant_id=r.tenant_id AND p.owner_id=r.owner_id
            WHERE r.project_id=? AND r.run_id=? AND r.tenant_id=? AND r.owner_id=? AND w.user_id=?
            """+(lock?" FOR SHARE OF w":""),project,run,p.tenantId(),p.userId(),p.storageUserId());
        if(rows.size()!=1) throw unavailable();
        return rows.get(0);
    }
    void save(String project,String run,AuthPrincipal p,JsonNode payload) {
        db.update("""
            INSERT INTO research_progress_memory(project_id,run_id,tenant_id,owner_id,payload)
            VALUES (?,?,?,?,?::jsonb)
            ON CONFLICT (project_id,run_id) DO UPDATE SET payload=EXCLUDED.payload,saved_at=now()
            WHERE research_progress_memory.tenant_id=EXCLUDED.tenant_id AND research_progress_memory.owner_id=EXCLUDED.owner_id
              AND research_progress_memory.payload IS DISTINCT FROM EXCLUDED.payload
            """,project,run,p.tenantId(),p.userId(),canonical(payload));
    }
    List<JsonNode> saved(String project,AuthPrincipal p,String run) {
        return db.query("""
            SELECT m.payload::text FROM research_progress_memory m
            JOIN agent_research_run r ON r.run_id=m.run_id AND r.project_id=m.project_id
              AND r.tenant_id=m.tenant_id AND r.owner_id=m.owner_id
            JOIN agent_workflow_run w ON w.run_id=r.run_id
            JOIN research_project p ON p.project_id=m.project_id AND p.tenant_id=m.tenant_id AND p.owner_id=m.owner_id
            WHERE m.tenant_id=? AND m.owner_id=? AND w.user_id=?
            """+(project==null?"":" AND m.project_id=?")
                +(run==null?" ORDER BY m.saved_at DESC,m.run_id LIMIT 20":" AND m.run_id=?"),
            (rs,n)->parse(rs.getString(1)),savedArguments(project,run,p));
    }
    private Object[] savedArguments(String project,String run,AuthPrincipal p) {
        var args=new ArrayList<Object>(List.of(p.tenantId(),p.userId(),p.storageUserId()));
        if(project!=null) args.add(project);
        if(run!=null) args.add(run);
        return args.toArray();
    }
    List<JsonNode> evidence(String project,String run,AuthPrincipal p) {
        return db.query("""
            SELECT DISTINCT ON (record_id) payload::text FROM agent_evidence_record
            WHERE project_id=? AND run_id=? AND tenant_id=? AND owner_id=? AND record_type='Evidence'
            ORDER BY record_id,version DESC
            """,(rs,n)->parse(rs.getString(1)),project,run,p.tenantId(),p.userId());
    }
    boolean referencesAccessible(String project,AuthPrincipal p,JsonNode payload) {
        return referencesAccessible(project,p,payload,new HashSet<>(),new HashMap<>(),new HashSet<>());
    }
    private boolean referencesAccessible(String project,AuthPrincipal p,JsonNode payload,Set<String> trail,
            Map<String,JsonNode> current,Set<String> validated) {
        String run=payload.path("source_run_id").asText();
        if(!trail.add(run)) return false;
        if(validated.contains(run)) return true;
        current.putIfAbsent(run,payload);
        if(current.size()>20) return false;
        for(var ref:payload.path("prior_memory_refs")) {
            String ancestor=ref.path("source_run_id").asText();JsonNode record=current.get(ancestor);
            if(record==null) {
                if(current.size()>=20) return false;
                var rows=saved(project,p,ancestor);
                if(rows.size()!=1) return false;
                record=rows.get(0);current.put(ancestor,record);
            }
            if(!sha(canonical(record)).equals(ref.path("snapshot_sha256").asText())
                || !referencesAccessible(project,p,record,new HashSet<>(trail),current,validated)) return false;
        }
        var evidenceById=new HashMap<String,JsonNode>();
        for(var row:evidence(project,run,p)) evidenceById.put(row.path("evidence_id").asText(),row);
        for(var ref:payload.path("source_evidence")) {
            var row=evidenceById.get(ref.path("evidence_id").asText());
            if(row==null || !"available".equals(row.path("availability").asText()) || !"fresh".equals(row.path("freshness").asText())
                    || !ref.path("snapshot_sha256").equals(row.path("snapshot").path("sha256"))) return false;
        }
        var claims=new HashMap<String,JsonNode>();
        for(var row:claimStates(project,run,p)) claims.put(row.path("claim_id").asText(),row);
        for(var ref:payload.path("source_claims")) if(!ref.equals(claims.get(ref.path("claim_id").asText()))) return false;
        validated.add(run);
        return true;
    }
    List<JsonNode> claimStates(String project,String run,AuthPrincipal p) {
        return db.query("""
            SELECT DISTINCT ON (record_id) record_id,payload_sha256,payload->>'decision_status' AS status,payload->>'freshness' AS freshness
            FROM agent_evidence_record WHERE project_id=? AND run_id=? AND tenant_id=? AND owner_id=? AND record_type='Claim'
            ORDER BY record_id,version DESC
            """,(rs,n)->object("claim_id",rs.getString("record_id"),"record_sha256",rs.getString("payload_sha256"),
                "decision_status",rs.getString("status"),"freshness",rs.getString("freshness")),project,run,p.tenantId(),p.userId());
    }
    Set<String> adoptedEvidence(String project,String run,AuthPrincipal p,String claim) {
        var rows=db.query("""
            SELECT payload::text FROM agent_evidence_record
            WHERE project_id=? AND run_id=? AND tenant_id=? AND owner_id=? AND record_type='DecisionRecord'
              AND payload->>'claim_id'=? ORDER BY version DESC LIMIT 1
            """,(rs,n)->parse(rs.getString(1)),project,run,p.tenantId(),p.userId(),claim);
        var result=new HashSet<String>();
        if(!rows.isEmpty()) rows.get(0).path("adopted_evidence_ids").forEach(e->result.add(e.asText()));
        return result;
    }
    List<JsonNode> frozenPredecessors(String project,String run,AuthPrincipal p) {
        JsonNode context=parse(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",String.class,run));
        var result=new ArrayList<JsonNode>();
        for(var selected:context.path("prior_progress").path("records")) {
            var rows=saved(project,p,selected.path("source_run_id").asText());
            if(rows.size()!=1 || !sha(canonical(rows.get(0))).equals(selected.path("snapshot_sha256").asText())
                    || !referencesAccessible(project,p,rows.get(0))) throw new IllegalStateException("PREDECESSOR_CHANGED");
            result.add(rows.get(0));
        }
        return result;
    }
    void lockSaveOutcome(String run) {
        db.update("INSERT INTO research_progress_auto_save(run_id,status) SELECT run_id,'PENDING' FROM agent_workflow_run WHERE run_id=? AND status IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED') ON CONFLICT(run_id) DO NOTHING",run);
        db.queryForList("SELECT run_id FROM research_progress_auto_save WHERE run_id=? FOR UPDATE",run);
    }
    void markSaved(String run) {
        db.update("UPDATE research_progress_auto_save SET status='SAVED',error_code=NULL,updated_at=now() WHERE run_id=?",run);
    }
    void markDeleted(String run) {
        db.update("INSERT INTO research_progress_auto_save(run_id,status) VALUES (?,'DELETED') ON CONFLICT(run_id) DO UPDATE SET status='DELETED',error_code=NULL,updated_at=now()",run);
    }
    boolean delete(String project,String run,AuthPrincipal p) {
        return db.update("DELETE FROM research_progress_memory WHERE project_id=? AND run_id=? AND tenant_id=? AND owner_id=?",
            project,run,p.tenantId(),p.userId())>0;
    }
    static ResponseStatusException unavailable() { return new ResponseStatusException(NOT_FOUND,"研究进度不可用"); }
    private static JsonNode parse(String text) {
        try { return JSON.readTree(text); } catch(Exception invalid) { throw new IllegalStateException("Invalid stored progress JSON"); }
    }
}

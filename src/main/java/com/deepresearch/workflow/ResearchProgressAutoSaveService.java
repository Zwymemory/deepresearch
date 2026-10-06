package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.service.UserContextService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Bounded durable terminal-run reconciliation; no model calls and no dependency on request identity. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="deepresearch.workflow.enabled",havingValue="true")
public class ResearchProgressAutoSaveService {
    private final JdbcTemplate db;
    private final ResearchProgressService progress;
    private final ResearchProgressRepository repository;
    private final UserContextService users;
    private final TransactionTemplate tx;
    private final boolean scheduled;
    public ResearchProgressAutoSaveService(JdbcTemplate db,ResearchProgressService progress,
            ResearchProgressRepository repository,UserContextService users,PlatformTransactionManager manager,
            @Value("${deepresearch.memory.auto-save.scheduler-enabled:true}") boolean scheduled) {
        this.db=db;this.progress=progress;this.repository=repository;this.users=users;
        this.tx=new TransactionTemplate(manager);this.scheduled=scheduled;
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Scheduled(fixedDelayString="${deepresearch.memory.auto-save.interval-ms:2000}")
    public void tick() { if(scheduled) reconcile(); }
    public void reconcile() {
        db.update("""
            INSERT INTO research_progress_auto_save(run_id,status)
            SELECT r.run_id,'PENDING' FROM agent_research_run r JOIN agent_workflow_run w USING(run_id)
            WHERE w.endpoint='/api/research/agents' AND w.context_snapshot->'project_progress_policy'->>'enabled'='true'
              AND w.status IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED')
              AND NOT EXISTS(SELECT 1 FROM research_progress_auto_save s WHERE s.run_id=r.run_id)
            ORDER BY w.updated_at,r.run_id LIMIT 20 ON CONFLICT(run_id) DO NOTHING
            """);
        var pending=db.queryForList("SELECT run_id FROM research_progress_auto_save WHERE status='PENDING' ORDER BY updated_at,run_id LIMIT 5",String.class);
        for(String run:pending) process(run);
    }
    private void process(String run) {
        tx.executeWithoutResult(transaction->{
            // All writers acquire run -> outcome -> snapshot, avoiding delete/save deadlocks.
            var owners=db.queryForList("SELECT project_id,tenant_id,owner_id FROM agent_research_run WHERE run_id=?",run);
            if(owners.size()!=1) return;
            var owner=owners.get(0);String project=(String)owner.get("project_id");
            var p=new AuthPrincipal((String)owner.get("tenant_id"),(String)owner.get("owner_id"),List.of("USER"));
            repository.ownedRun(project,run,p,true);
            var status=db.queryForList("SELECT status FROM research_progress_auto_save WHERE run_id=? FOR UPDATE SKIP LOCKED",String.class,run);
            if(status.size()!=1 || !"PENDING".equals(status.get(0))) return;
            Object savepoint=transaction.createSavepoint();String result="SAVED",error=null;
            try {progress.saveForOwner(project,run,p,"automatic");}
            catch(RuntimeException failure) {
                transaction.rollbackToSavepoint(savepoint);result="FAILED";
                error=failure instanceof org.springframework.web.server.ResponseStatusException response && response.getStatusCode().value()==413
                    ?"PROGRESS_CAPACITY_EXCEEDED":"PROGRESS_SAVE_FAILED";
            } finally {transaction.releaseSavepoint(savepoint);}
            db.update("UPDATE research_progress_auto_save SET status=?,error_code=?,updated_at=now() WHERE run_id=?",result,error,run);
            db.update("""
                INSERT INTO agent_workflow_event(run_id,event_key,role,type,safe_payload)
                VALUES (?,?,'SYSTEM','RESEARCH_PROGRESS_AUTO_SAVE',?::jsonb) ON CONFLICT(run_id,event_key) DO NOTHING
                """,run,"progress:auto-save",canonical(object("status",result,"error_code",error,"project_id",project,"trusted_as_evidence",false)));
        });
    }
    public JsonNode view(String run) {
        var p=users.currentPrincipalRequired();String project=repository.projectForRun(run,p);
        var row=repository.ownedRun(project,run,p,false);
        boolean enabled=Boolean.TRUE.equals(db.queryForObject("SELECT context_snapshot->'project_progress_policy'->>'enabled'='true' FROM agent_workflow_run WHERE run_id=?",Boolean.class,run));
        var outcomes=db.queryForList("SELECT status,error_code FROM research_progress_auto_save WHERE run_id=?",run);
        String status=outcomes.isEmpty()?(enabled?(WorkflowStatus.valueOf((String)row.get("status")).terminal()?"PENDING":"WAITING"):"NOT_ENABLED"):(String)outcomes.get(0).get("status");
        var saved=repository.saved(project,p,run);
        boolean accessible=saved.size()==1 && repository.referencesAccessible(project,p,saved.get(0));
        String error=outcomes.isEmpty()?null:(String)outcomes.get(0).get("error_code");
        if("SAVED".equals(status) && !accessible) {status="UNAVAILABLE";error="PROGRESS_SOURCE_CHANGED";}
        return object("schema_version","research-progress-save/1","run_id",run,"project_id",project,
            "status",status,"enabled",enabled,"saved",accessible,"error_code",error,
            "save_origin",accessible?saved.get(0).path("save_origin").asText("manual"):null,"trusted_as_evidence",false);
    }
}

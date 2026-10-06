package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static com.deepresearch.workflow.ResearchProgressSelectionDtos.*;

/** Frozen cross-project history with source ownership and use-time revocation checks. */
@Service
public class ResearchRecallService {
    private final ResearchProgressRepository repository;
    private final JdbcTemplate db;
    public ResearchRecallService(ResearchProgressRepository repository,JdbcTemplate db) {this.repository=repository;this.db=db;}
    JsonNode select(String question,String session,String explicitProject,AuthPrincipal principal) {
        var candidates=repository.saved(null,principal,null).stream()
            .filter(row->explicitProject==null || !explicitProject.equals(row.path("project_id").asText())).toList();
        return ResearchRecallAssembler.assemble(question,session,candidates,row->sources(row,principal));
    }
    private List<JsonNode> sources(JsonNode row,AuthPrincipal principal) {
        String project=row.path("project_id").asText();
        if(!repository.referencesAccessible(project,principal,row)) return null;
        var sources=new TreeMap<String,JsonNode>();var visited=new HashSet<String>();var pending=new ArrayDeque<JsonNode>();pending.add(row);
        while(!pending.isEmpty()) {
            var snap=pending.remove();String run=snap.path("source_run_id").asText();if(!visited.add(run)) continue;
            if(visited.size()>20) return null;
            var refs=new HashSet<String>();snap.path("source_evidence").forEach(e->refs.add(e.path("evidence_id").asText()));
            for(var evidence:repository.evidence(project,run,principal)) if(refs.contains(evidence.path("evidence_id").asText())) {
                var source=evidence.path("source");String hash=evidence.path("snapshot").path("sha256").asText();
                // Same original content/location counts once even if multiple memory records cite it.
                String key=sha(canonical(object("kind",source.path("kind"),"locator",source.path("locator")))+":"+hash);
                sources.putIfAbsent(key,object("source_key",key,"source",source,"snapshot_sha256",hash,
                    "source_run_id",run,"evidence_id",evidence.path("evidence_id").asText(),"independent_evidence",false));
            }
            for(var ref:snap.path("prior_memory_refs")) {
                var ancestors=repository.saved(project,principal,ref.path("source_run_id").asText());
                if(ancestors.size()!=1) return null;pending.add(ancestors.get(0));
            }
        }
        return new ArrayList<>(sources.values());
    }
    boolean accessible(JsonNode envelope,AuthPrincipal principal) {
        if(!validEnvelope(envelope)) return false;
        var seen=new HashSet<String>();
        for(var row:envelope.path("records")) {
            String project=row.path("source_project_id").asText(),run=row.path("source_run_id").asText();
            if(!seen.add(run) || !ResearchProgressContextAssembler.valid(row.path("snapshot"),project)
                || !run.equals(row.path("snapshot").path("source_run_id").asText())
                || !sha(canonical(row.path("snapshot"))).equals(row.path("snapshot_sha256").asText())) return false;
            var current=repository.saved(project,principal,run);
            if(current.size()!=1 || !sha(canonical(current.get(0))).equals(row.path("snapshot_sha256").asText())) return false;
            var sourceRefs=sources(current.get(0),principal);
            if(sourceRefs==null || !JSON.valueToTree(sourceRefs).equals(row.path("source_refs"))) return false;
        }
        return true;
    }
    static boolean validEnvelope(JsonNode value) {
        return value.isObject() && "research-recall-context/1".equals(value.path("schema_version").asText())
            && "recalled_progress".equals(value.path("context_kind").asText())
            && value.path("trusted_as_evidence").isBoolean() && !value.path("trusted_as_evidence").asBoolean()
            && value.path("records").isArray() && value.path("records").size()<=3
            && ResearchRecallAssembler.bytes(value)<=ResearchRecallAssembler.LIMIT_BYTES;
    }
    @Transactional
    public ValidationResult validate(ValidationRequest request) {
        if(request==null || request.runId()==null || request.claimToken()==null || request.projectionSha256()==null)
            throw failure("INVALID");
        var targets=db.queryForList("""
            SELECT a.project_id,a.tenant_id,a.owner_id,w.session_id,w.context_snapshot::text AS context
            FROM agent_workflow_run w JOIN agent_research_run a USING(run_id)
            JOIN research_project p ON p.project_id=a.project_id AND p.tenant_id=a.tenant_id AND p.owner_id=a.owner_id
            JOIN agent_session s ON s.session_id=w.session_id AND s.user_id=w.user_id
            WHERE w.run_id=? AND w.user_id=a.tenant_id||':'||a.owner_id FOR SHARE OF w
            """,request.runId());
        if(targets.size()!=1) throw failure("NOT_FOUND");var target=targets.get(0);
        String project=(String)target.get("project_id"),session=(String)target.get("session_id");
        var principal=new AuthPrincipal((String)target.get("tenant_id"),(String)target.get("owner_id"),List.of());
        JsonNode context=parse((String)target.get("context")),envelope=context.path("recalled_progress"),binding=context.path("recalled_progress_binding");
        String hash=sha(canonical(envelope));
        if(!validEnvelope(envelope) || !project.equals(binding.path("project_id").asText())
            || !hash.equals(request.projectionSha256()) || !hash.equals(binding.path("projection_sha256").asText())
            || !binding.path("canonical_bytes").isIntegralNumber() || binding.path("canonical_bytes").asInt()!=ResearchRecallAssembler.bytes(envelope)) throw failure("INVALID");
        try {repository.requireProject(project,principal);repository.requireSession(project,session,principal);}
        catch(org.springframework.web.server.ResponseStatusException denied) {throw failure("REVOKED");}
        for(var row:envelope.path("records")) if(session.equals(row.path("snapshot").path("source_session_id").asText())) throw failure("INVALID");
        if(!accessible(envelope,principal)) throw failure("REVOKED");
        var times=db.query("""
            SELECT clock_timestamp() AS checked_at FROM agent_workflow_run w
            JOIN agent_workflow_grant g ON g.run_id=w.run_id AND g.grant_id=w.grant_id AND g.subject=w.user_id
            WHERE w.run_id=? AND w.claim_token=? AND w.user_id=? AND w.session_id=?
              AND w.lease_until>clock_timestamp() AND w.deadline_at>clock_timestamp()
              AND g.expires_at>clock_timestamp() AND g.revoked_at IS NULL AND NOT w.cancel_requested
              AND w.status IN ('QUEUED','PLANNING','WORKING','REVIEWING','SYNTHESIZING')
            """,(rs,n)->rs.getObject("checked_at",OffsetDateTime.class).toInstant(),request.runId(),request.claimToken(),principal.storageUserId(),session);
        if(times.size()!=1) throw failure("CLAIM_INVALID");return new ValidationResult(project,hash,times.get(0));
    }
    JsonNode view(String run,AuthPrincipal principal) {
        String project=repository.projectForRun(run,principal);
        JsonNode context=parse(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",String.class,run));
        var envelope=context.path("recalled_progress");String hash=context.path("recalled_progress_binding").path("projection_sha256").asText();
        boolean available=accessible(envelope,principal);
        Integer recorded=hash.isBlank()?0:db.queryForObject("""
            SELECT count(*) FROM agent_research_operation WHERE run_id=? AND purpose='DECISION' AND status='SETTLED'
              AND safe_result->'request_binding'->>'recalled_progress_sha256'=?
            """,Integer.class,run,hash);
        String status=!context.has("recalled_progress")?"DISABLED":!available?"UNAVAILABLE":envelope.path("records").isEmpty()?"EMPTY":recorded!=null && recorded>0?"USED":"SELECTED";
        return object("schema_version","research-recall-view/1","run_id",run,"project_id",project,"status",status,
            "planner_input_recorded",available && recorded!=null && recorded>0,"trusted_as_evidence",false,
            "records",available?envelope.path("records"):List.of(),"selection",available?envelope.path("selection"):null,
            "message",status.equals("UNAVAILABLE")?"旧记录已变更或不可访问，需重新开始研究。":"旧研究仅作调查线索；时间、版本和条件均需重新核查。");
    }
    private static JsonNode parse(String value) {try{return JSON.readTree(value);}catch(Exception e){throw failure("INVALID");}}
    private static ResearchMemoryException failure(String code) {return new ResearchMemoryException("RESEARCH_MEMORY_"+code,!code.equals("CLAIM_INVALID"));}
}

package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static com.deepresearch.workflow.ResearchProgressSelectionDtos.*;

/** Explicit project memory, validated before each use. Validation does not authorize a dispatch. */
@Service
public class ResearchProgressSelectionService {
    private final ResearchProgressRepository repository;
    private final JdbcTemplate db;
    public ResearchProgressSelectionService(ResearchProgressRepository repository,JdbcTemplate db) {
        this.repository=repository;this.db=db;
    }
    @Transactional
    public Selection select(SelectionRequest request) {
        if(request==null || request.principal()==null || blank(request.projectId()) || blank(request.targetSessionId()))
            throw failure("INVALID",true);
        var principal=request.principal();
        owned(request.projectId(),request.targetSessionId(),principal,"NOT_FOUND");
        var projection=ResearchProgressContextAssembler.assemble(request.projectId(),request.targetSessionId(),
            repository.saved(request.projectId(),principal,null),
            row->repository.referencesAccessible(request.projectId(),principal,row));
        return new Selection(projection.priorProgress(),projection.projectionSha256(),projection.canonicalBytes());
    }
    @Transactional
    public ValidationResult validate(ValidationRequest request) {
        if(request==null || blank(request.runId()) || request.claimToken()==null || !hash(request.projectionSha256()))
            throw failure("INVALID",true);
        // The target lock can wait behind a writer. Authority is checked with wall time afterwards.
        // No historical source claim/grant is created or required, and no task is needed at first planning.
        var targets=db.queryForList("""
            SELECT a.project_id,a.tenant_id,a.owner_id,w.session_id,w.context_snapshot::text AS context
            FROM agent_workflow_run w JOIN agent_research_run a ON a.run_id=w.run_id
            JOIN research_project p ON p.project_id=a.project_id AND p.tenant_id=a.tenant_id AND p.owner_id=a.owner_id
            JOIN agent_session s ON s.session_id=w.session_id AND s.user_id=w.user_id
            WHERE w.run_id=? AND w.user_id=a.tenant_id||':'||a.owner_id FOR SHARE OF w
            """,request.runId());
        if(targets.size()!=1) throw failure("NOT_FOUND",true);
        var target=targets.get(0);
        String project=(String)target.get("project_id"),session=(String)target.get("session_id");
        var principal=new AuthPrincipal((String)target.get("tenant_id"),(String)target.get("owner_id"),List.of());
        owned(project,session,principal,"REVOKED");
        JsonNode context=parse((String)target.get("context"));
        JsonNode envelope=context.path("prior_progress"),binding=context.path("prior_progress_binding");
        String serialized=canonical(envelope);
        long bytes=serialized.getBytes(StandardCharsets.UTF_8).length;
        String projectionHash=sha(serialized);
        if(bytes>ResearchProgressContextAssembler.LIMIT_BYTES) throw failure("INPUT_TOO_LARGE",true);
        if(!envelope.isObject() || !binding.isObject()
            || !"research-progress-context/1".equals(envelope.path("schema_version").asText())
            || !"prior_progress".equals(envelope.path("context_kind").asText())
            || !envelope.path("trusted_as_evidence").isBoolean() || envelope.path("trusted_as_evidence").asBoolean()
            || !project.equals(envelope.path("project_id").asText()) || !project.equals(binding.path("project_id").asText())
            || !projectionHash.equals(request.projectionSha256()) || !projectionHash.equals(binding.path("projection_sha256").asText())
            || !binding.path("canonical_bytes").isIntegralNumber() || binding.path("canonical_bytes").asLong()!=bytes
            || !envelope.path("records").isArray() || envelope.path("records").isEmpty()
            || envelope.path("records").size()>ResearchProgressContextAssembler.MAX_RECORDS) throw failure("INVALID",true);
        var seen=new HashSet<String>();
        for(var record:envelope.path("records")) {
            String sourceRun=record.path("source_run_id").asText();
            var snapshot=record.path("snapshot");
            String snapshotHash=record.path("snapshot_sha256").asText();
            if(!ResearchProgressContextAssembler.valid(snapshot,project) || !seen.add(sourceRun)
                || !sourceRun.equals(snapshot.path("source_run_id").asText())
                || session.equals(snapshot.path("source_session_id").asText())
                || !hash(snapshotHash) || !snapshotHash.equals(sha(canonical(snapshot)))) throw failure("INVALID",true);
            var current=repository.saved(project,principal,sourceRun);
            if(current.size()!=1 || !snapshotHash.equals(sha(canonical(current.get(0))))
                || !repository.referencesAccessible(project,principal,current.get(0))) throw failure("REVOKED",true);
        }
        // This is the completion of a local check, not an atomic check-and-provider-send permit.
        var times=db.query("""
            SELECT clock_timestamp() AS checked_at FROM agent_workflow_run w
            JOIN agent_research_run a ON a.run_id=w.run_id
            JOIN research_project p ON p.project_id=a.project_id AND p.tenant_id=a.tenant_id AND p.owner_id=a.owner_id
            JOIN agent_session s ON s.session_id=w.session_id AND s.user_id=w.user_id
            JOIN agent_workflow_grant g ON g.run_id=w.run_id AND g.grant_id=w.grant_id AND g.subject=w.user_id
            WHERE w.run_id=? AND w.claim_token=? AND w.user_id=? AND w.session_id=?
              AND a.project_id=? AND a.tenant_id=? AND a.owner_id=?
              AND w.lease_until>clock_timestamp() AND w.deadline_at>clock_timestamp()
              AND g.expires_at>clock_timestamp() AND g.revoked_at IS NULL AND NOT w.cancel_requested
              AND w.status IN ('QUEUED','PLANNING','WORKING','REVIEWING','SYNTHESIZING')
            """,(rs,n)->rs.getObject("checked_at",OffsetDateTime.class).toInstant(),
            request.runId(),request.claimToken(),principal.storageUserId(),session,project,principal.tenantId(),principal.userId());
        if(times.size()!=1) throw failure("CLAIM_INVALID",false);
        return new ValidationResult(project,projectionHash,times.get(0));
    }
    private void owned(String project,String session,AuthPrincipal principal,String code) {
        try { repository.requireProject(project,principal);repository.requireSession(project,session,principal); }
        catch(ResponseStatusException denied) { throw failure(code,true); }
    }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static boolean hash(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
    private static ResearchMemoryException failure(String code,boolean reselection) {
        return new ResearchMemoryException("RESEARCH_MEMORY_"+code,reselection);
    }
    private static JsonNode parse(String value) {
        try { return JSON.readTree(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw failure("INVALID",true); }
    }
}

package com.deepresearch.workflow;

import com.deepresearch.service.UserContextService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;

/** Explicit deterministic progress projection. No LLM, retrieval or implicit context injection. */
@Service
public class ResearchProgressService {
    private final ResearchProgressRepository repository;
    private final UserContextService users;
    private final AgentCompletionService completion;
    public ResearchProgressService(ResearchProgressRepository repository,UserContextService users,JdbcTemplate db) {
        this.repository=repository;this.users=users;this.completion=new AgentCompletionService(db);
    }
    @Transactional(readOnly=true)
    public JsonNode projectForRun(String run) {
        var p=users.currentPrincipalRequired();
        return object("project_id",repository.projectForRun(run,p),"run_id",run);
    }
    @Transactional(readOnly=true)
    public JsonNode list() {
        var p=users.currentPrincipalRequired();
        var rows=repository.saved(null,p,null).stream()
            .filter(r->repository.referencesAccessible(r.path("project_id").asText(),p,r)).toList();
        return object("schema_version","research-progress-list/1","items",rows,"candidate_limit",20);
    }
    @Transactional
    public JsonNode resumeInNewSession(String project) {
        var p=users.currentPrincipalRequired();repository.requireProject(project,p);
        String session=repository.createSession(p);
        return resume(project,session);
    }
    @Transactional
    public JsonNode save(String project,String run) {
        var p=users.currentPrincipalRequired();repository.requireProject(project,p);
        var row=repository.ownedRun(project,run,p,true);
        var refs=new ArrayList<JsonNode>();var available=new HashSet<String>();
        for(var e:repository.evidence(project,run,p)) if("available".equals(e.path("availability").asText()) && "fresh".equals(e.path("freshness").asText())) {
            available.add(e.path("evidence_id").asText());
            refs.add(object("evidence_id",e.path("evidence_id"),"receipt_id",e.path("receipt_id"),
                "snapshot_sha256",e.path("snapshot").path("sha256"),"source_id",e.path("source").path("source_id")));
        }
        var completed=new ArrayList<JsonNode>();var unresolved=new ArrayList<JsonNode>();
        for(var goal:completion.goalsForRun(run)) {
            var required=new HashSet<String>();boolean accessible=true;
            for(var criterion:goal.criteria()) for(String claim:criterion.claimIds()) {
                var adopted=repository.adoptedEvidence(project,run,p,claim);
                if(adopted.isEmpty() || !available.containsAll(adopted)) accessible=false;
                required.addAll(adopted);
            }
            boolean established=goal.completionVerified() && accessible && !required.isEmpty();
            var gaps=new ArrayList<>(goal.gaps());
            if(goal.completionVerified() && !established) gaps.add("完成证明缺少可访问且有效的原文引用");
            var item=object("task_id",goal.taskId(),"goal",goal.text(),"status",goal.status(),
                "completion_verified",established,"criteria",goal.criteria(),"gaps",gaps);
            (established?completed:unresolved).add(item);
        }
        String status=(String)row.get("status");
        if(!"SUCCEEDED".equals(status) || completed.isEmpty()) unresolved.add(object("goal",row.get("question"),
            "status",status,"completion_verified",false,"error_code",row.get("error_code"),
            "gaps",List.of("原始运行未建立整题完成；保留其未解决状态并重新核验后续工作")));
        var payload=object("schema_version","research-progress/1","context_kind","prior_progress","trusted_as_evidence",false,
            "project_id",project,"source_run_id",run,"source_session_id",row.get("session_id"),
            "original_goal",row.get("question"),"run_status",status,"completed_work",completed,
            "unresolved_questions",unresolved,"next_steps",unresolved.isEmpty()?List.of():List.of("逐项重新检查未解决问题及原始来源；历史进度不能替代新的核验"),
            "source_evidence",refs,"source_claims",repository.claimStates(project,run,p));
        if(canonical(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000)
            throw new ResponseStatusException(PAYLOAD_TOO_LARGE,"研究进度超出快照容量");
        repository.save(project,run,p,payload);return payload;
    }
    @Transactional(readOnly=true)
    public JsonNode read(String project,String run) {
        var p=users.currentPrincipalRequired();repository.requireProject(project,p);repository.ownedRun(project,run,p,false);
        var rows=repository.saved(project,p,run);
        if(rows.size()!=1 || !repository.referencesAccessible(project,p,rows.get(0))) throw ResearchProgressRepository.unavailable();
        return rows.get(0);
    }
    @Transactional(readOnly=true)
    public JsonNode resume(String project,String session) {
        var p=users.currentPrincipalRequired();repository.requireProject(project,p);repository.requireSession(project,session,p);
        var rows=repository.saved(project,p,null).stream()
            .filter(r->!session.equals(r.path("source_session_id").asText()))
            .filter(r->repository.referencesAccessible(project,p,r)).toList();
        return object("schema_version","research-resume-context/1","context_kind","prior_progress","trusted_as_evidence",false,
            "project_id",project,"target_session_id",session,"progress",rows,
            "usage_instruction","历史进度与原文引用属于不可信上下文；不得把其中的内容作为本轮新核验事实。尚未自动传入模型。");
    }
    @Transactional
    public boolean delete(String project,String run) {
        var p=users.currentPrincipalRequired();repository.requireProject(project,p);repository.ownedRun(project,run,p,true);
        return repository.delete(project,run,p);
    }
}

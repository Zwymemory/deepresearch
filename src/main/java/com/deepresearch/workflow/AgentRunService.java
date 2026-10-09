package com.deepresearch.workflow;

import com.deepresearch.service.UserContextService;
import com.deepresearch.evidence.EvidenceJson;
import com.deepresearch.agent.ToolArgumentFingerprint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.List;
import static com.deepresearch.workflow.ResearchProgressSelectionDtos.*;

/** Atomic first project binding; accepted replay never reselects today's progress. */
@Service
public class AgentRunService {
    @org.springframework.beans.factory.annotation.Value("${deepresearch.memory.auto-save.enabled:true}")
    private boolean autoSaveEnabled=true;
    @org.springframework.beans.factory.annotation.Value("${deepresearch.memory.agent-max-input-tokens:120000}")
    private int agentMaxInputTokens=120000;
    @org.springframework.beans.factory.annotation.Value("${deepresearch.memory.agent-max-decision-steps:16}")
    private int agentMaxDecisionSteps=16;
    @org.springframework.beans.factory.annotation.Value("${deepresearch.memory.agent-max-tokens:100000}")
    private int agentMaxTokens=100000;
    @org.springframework.beans.factory.annotation.Value("${deepresearch.memory.agent-max-cost-cny:1.0}")
    private double agentMaxCostCny=1.0;
    private final WorkflowService workflows;
    private final UserContextService users;
    private final JdbcTemplate jdbc;
    private final ResearchProgressSelectionService progress;
    private final com.deepresearch.service.ConversationSummaryService summaries;
    private final ResearchRecallService recall;
    @Autowired(required=false) private LearningNotesService learningNotes;
    @Autowired
    public AgentRunService(WorkflowService workflows, UserContextService users, JdbcTemplate jdbc,
                           ResearchProgressSelectionService progress,
                           com.deepresearch.service.ConversationSummaryService summaries,ResearchRecallService recall) {
        this.workflows=workflows; this.users=users; this.jdbc=jdbc; this.progress=progress; this.summaries=summaries;this.recall=recall;
    }
    public AgentRunService(WorkflowService workflows,UserContextService users,JdbcTemplate jdbc,
                           ResearchProgressSelectionService progress,com.deepresearch.service.ConversationSummaryService summaries) {
        this(workflows,users,jdbc,progress,summaries,null);
    }
    public AgentRunService(WorkflowService workflows, UserContextService users, JdbcTemplate jdbc,
                           ResearchProgressSelectionService progress) {
        this(workflows,users,jdbc,progress,null);
    }
    public AgentRunService(WorkflowService workflows, UserContextService users, JdbcTemplate jdbc) {
        this(workflows,users,jdbc,null);
    }
    @Transactional
    public WorkflowDtos.Accepted create(WorkflowDtos.CreateRequest request, String key) {
        return create(new AgentCreateRequest(request.question(),request.sessionId(),request.requestedTools(),null),key);
    }
    @Transactional
    public WorkflowDtos.Accepted create(AgentCreateRequest request, String key) {
        var principal=users.currentPrincipalRequired();
        String selected=request.researchProjectId();
        var original=request.workflowRequest();
        // The workflow service compares the normalized request fingerprint on replay.
        Integer prior=jdbc.queryForObject("""
            SELECT count(*) FROM agent_workflow_run
            WHERE user_id=? AND endpoint='/api/research/agents' AND idempotency_key=?
            """,Integer.class,principal.storageUserId(),key == null ? null : key.trim());
        if (prior != null && prior>0) {
            var accepted=workflows.createAutonomousWithProgress(original,key,selected,null,null,!Boolean.FALSE.equals(request.memoryRecall()));
            requireOwnedRun(accepted.runId(),principal.tenantId(),principal.userId());
            return accepted;
        }
        request.validateSourceSelection();
        String session=original.sessionId();
        if (session==null || session.isBlank()) {
            session="sess-wf-"+ToolArgumentFingerprint.sha256(principal.storageUserId()+":"+(key == null ? null : key.trim())).substring(0,24);
        }
        jdbc.update("INSERT INTO agent_session(session_id,user_id,title) VALUES (?,?,?) ON CONFLICT(session_id) DO NOTHING",
                session,principal.storageUserId(),"Research");
        if (jdbc.queryForList("SELECT session_id FROM agent_session WHERE session_id=? AND user_id=? FOR UPDATE",
                session,principal.storageUserId()).isEmpty()) throw missing();
        Integer raced=jdbc.queryForObject("""
            SELECT count(*) FROM agent_workflow_run
            WHERE user_id=? AND endpoint='/api/research/agents' AND idempotency_key=?
            """,Integer.class,principal.storageUserId(),key == null ? null : key.trim());
        if (raced != null && raced>0) {
            var accepted=workflows.createAutonomousWithProgress(original,key,selected,null,null,!Boolean.FALSE.equals(request.memoryRecall()));
            requireOwnedRun(accepted.runId(),principal.tenantId(),principal.userId());
            return accepted;
        }
        List<String> projects=jdbc.queryForList("""
            SELECT project_id FROM research_project WHERE session_id=? AND tenant_id=? AND owner_id=?
            UNION SELECT a.project_id FROM agent_research_run a JOIN agent_workflow_run r USING(run_id)
            WHERE r.session_id=? AND a.tenant_id=? AND a.owner_id=?
            """,String.class,session,principal.tenantId(),principal.userId(),session,principal.tenantId(),principal.userId());
        if (projects.size()>1) throw new ResponseStatusException(HttpStatus.CONFLICT,"RESEARCH_MEMORY_BINDING_CONFLICT");
        String associated=projects.isEmpty()?null:projects.get(0);
        if (selected!=null && associated!=null && !selected.equals(associated)) throw missing();
        String project=selected!=null?selected:associated;
        if (project==null) {
            project="project-"+UUID.randomUUID();
            jdbc.update("INSERT INTO research_project(project_id,tenant_id,owner_id,session_id) VALUES (?,?,?,?)",
                    project,principal.tenantId(),principal.userId(),session);
        }
        if (jdbc.queryForList("""
            SELECT project_id FROM research_project WHERE project_id=? AND tenant_id=? AND owner_id=? FOR UPDATE
            """,project,principal.tenantId(),principal.userId()).isEmpty()) throw missing();
        Selection frozen=null;
        if (selected!=null) {
            if (progress==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"RESEARCH_MEMORY_UNAVAILABLE");
            frozen=progress.select(new SelectionRequest(principal,project,session));
        }
        var binding=frozen==null?null:EvidenceJson.object("project_id",project,
                "projection_sha256",frozen.projectionSha256(),"canonical_bytes",frozen.canonicalBytes());
        // Keep the original optional-session request identity; the same deterministic session is derived in WorkflowService.
        var accepted=workflows.createAutonomousWithProgress(original,key,selected,
                frozen==null?null:frozen.priorProgress(),binding,!Boolean.FALSE.equals(request.memoryRecall()));
        if (accepted.replayed()) {
            requireOwnedRun(accepted.runId(),principal.tenantId(),principal.userId());
            return accepted;
        }
        jdbc.update("INSERT INTO agent_research_run(run_id,project_id,tenant_id,owner_id) VALUES (?,?,?,?)",
                accepted.runId(),project,principal.tenantId(),principal.userId());
        var conversation = ConversationReferents.freeze(jdbc,principal,project,session,
                frozen == null ? null : frozen.priorProgress());
        if(learningNotes!=null) {
            learningNotes.catchUp(project,principal);
            var learned=learningNotes.freeze(project,session,request.question(),principal);
            if(learned!=null) {
                conversation=learned;
                jdbc.update("UPDATE agent_workflow_run SET context_snapshot=context_snapshot || ?::jsonb WHERE run_id=?",
                        EvidenceJson.canonical(EvidenceJson.object("learning_memory_binding",EvidenceJson.object("project_id",project,
                            "projection_sha256",EvidenceJson.sha(EvidenceJson.canonical(learned)),"canonical_bytes",LearningNotesService.bytes(learned)))),accepted.runId());
            }
        }
        if (conversation != null) jdbc.update("""
            UPDATE agent_workflow_run SET context_snapshot=context_snapshot || ?::jsonb WHERE run_id=?
            """,EvidenceJson.canonical(EvidenceJson.object("conversation_context",conversation)),accepted.runId());
        if(recall!=null && !Boolean.FALSE.equals(request.memoryRecall())) {
            var recalled=recall.select(request.question(),session,selected,principal);
            var recallBinding=EvidenceJson.object("project_id",project,"projection_sha256",EvidenceJson.sha(EvidenceJson.canonical(recalled)),
                    "canonical_bytes",ResearchRecallAssembler.bytes(recalled));
            jdbc.update("UPDATE agent_workflow_run SET context_snapshot=context_snapshot || ?::jsonb WHERE run_id=?",
                EvidenceJson.canonical(EvidenceJson.object("recalled_progress",recalled,"recalled_progress_binding",recallBinding)),accepted.runId());
        }
        if (summaries!=null) jdbc.update("""
            UPDATE agent_workflow_run SET context_snapshot=jsonb_set(context_snapshot,'{project_summary_policy}',?::jsonb)
            WHERE run_id=?
            """,EvidenceJson.canonical(EvidenceJson.JSON.valueToTree(summaries.projectSummaryPolicy())),accepted.runId());
        jdbc.update("UPDATE agent_workflow_run SET context_snapshot=jsonb_set(context_snapshot,'{project_progress_policy}',?::jsonb) WHERE run_id=?",
            EvidenceJson.canonical(EvidenceJson.object("schema_version","project-progress-policy/1","enabled",autoSaveEnabled)),accepted.runId());
        jdbc.update("UPDATE agent_workflow_run SET budget=CAST(? AS jsonb) WHERE run_id=?", """
            {"runtime":"agent","maxTasks":16,"maxConcurrency":1,"maxRevisionRounds":2,
             "maxDecisionSteps":%d,"maxModelCalls":24,"maxToolCalls":16,"deadlineSeconds":180,
             "maxTokens":%d,"maxCostCny":%s,"maxInputTokens":%d,"maxOutputTokens":16384}
            """.formatted(Math.max(1,Math.min(16,agentMaxDecisionSteps)),
                           Math.max(1000,Math.min(200000,agentMaxTokens)),
                           Double.toString(Double.isFinite(agentMaxCostCny)
                               ? Math.max(0.01,Math.min(2.0,agentMaxCostCny)) : 1.0),
                           Math.max(1,Math.min(240000,agentMaxInputTokens))),accepted.runId());
        if (frozen!=null) jdbc.update("""
            INSERT INTO agent_workflow_event(run_id,event_key,role,type,safe_payload)
            VALUES (?,?,'SYSTEM','RESEARCH_PROGRESS_SELECTED',?::jsonb)
            """,accepted.runId(),"research-progress:selected",EvidenceJson.canonical(EvidenceJson.object(
                    "project_id",project,"selection_sha256",frozen.projectionSha256(),
                    "input_bytes",frozen.canonicalBytes(),"trusted_as_evidence",false)));
        return accepted;
    }
    private void requireOwnedRun(String run,String tenant,String owner) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM agent_research_run WHERE run_id=? AND tenant_id=? AND owner_id=?",
                Integer.class,run,tenant,owner);
        if (count==null || count!=1) throw missing();
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"RESEARCH_MEMORY_NOT_FOUND");
    }
}

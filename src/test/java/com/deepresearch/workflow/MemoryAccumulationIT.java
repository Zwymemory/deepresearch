package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.*;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.assertThat;

/** M3 real JWT/HTTP/PostgreSQL and Python planner; only model/retrieval transport is controlled. */
class MemoryAccumulationIT extends MemoryContinuationIT {
    @org.springframework.test.context.DynamicPropertySource
    static void autoSaveSchedule(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("deepresearch.memory.auto-save.scheduler-enabled",()->Boolean.getBoolean("memory.demo.schedulerEnabled"));
    }
    @Autowired ResearchProgressAutoSaveService saves;
    @Autowired ResearchProgressService progress;
    @Autowired ResearchProgressRepository memories;
    @Autowired com.deepresearch.service.UserContextService identities;
    @Autowired PlatformTransactionManager transactions;
    Path results() throws Exception {
        Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m3")).toAbsolutePath();
        Files.createDirectories(dir);return dir;
    }
    Run last(String owner) {
        String id=db.queryForObject("SELECT run_id FROM agent_workflow_run WHERE user_id=? ORDER BY created_at DESC LIMIT 1",String.class,"tenant-http:"+owner);
        return new Run(id,db.queryForObject("SELECT project_id FROM agent_research_run WHERE run_id=?",String.class,id),
            db.queryForObject("SELECT claim_token::text FROM agent_workflow_run WHERE run_id=?",String.class,id),owner);
    }
    Reply outcome(Run run) throws Exception {return request("GET","/api/research/agents/"+run.id()+"/progress-save",user(run.user()),null);}
    Reply snapshot(Run run) throws Exception {return request("GET","/api/research/projects/"+run.project()+"/progress/runs/"+run.id(),user(run.user()),null);}
    Reply correction(Run run,String note) throws Exception {return request("PATCH","/api/research/projects/"+run.project()+"/progress/runs/"+run.id(),user(run.user()),object("note",note));}
    void settleSave(Run run) throws Exception {
        if(!Boolean.getBoolean("memory.demo.schedulerEnabled")) {saves.reconcile();return;}
        // In the held demo exercise the actual timer, with no direct reconcile on initial save.
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end && "PENDING".equals(outcome(run).body().path("status").asText())) Thread.sleep(100);
        assertThat(outcome(run).body().path("status").asText()).isEqualTo("SAVED");
    }
    Run failed(String owner,boolean huge) throws Exception {
        return failed(owner,huge,null,null);
    }
    Run failed(String owner,boolean huge,String project,String session) throws Exception {
        Run run=create(owner,List.of("kb_search"),project,session);
        if(huge) db.update("UPDATE agent_research_task SET objective=? WHERE run_id=?","未核实的原始待办".repeat(12000),run.id());
        var body=object("claimToken",run.claim(),"status","FAILED","answer","","citations",List.of(),"errorCode","SOURCE_UNAVAILABLE");
        assertThat(request("POST","/internal/research/workflows/"+run.id()+"/finalize",service(),body).status()).isEqualTo(200);
        return run;
    }
    @Test void autoSaveUpdatesNativeCompletionCarriesPendingAndCorrectionEntersNewPlanner() throws Exception {
        Path dir=results();String owner="m3-"+UUID.randomUUID();
        criterionHttpScenario("partial",owner,null,null,true);Run old=last(owner);
        Files.copy(Path.of("target/criterion-http-partial.json"),dir.resolve("native-partial.json"),StandardCopyOption.REPLACE_EXISTING);
        settleSave(old);assertThat(outcome(old).body().path("status").asText()).isEqualTo("SAVED");
        var first=snapshot(old);assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().path("completed_work")).hasSize(1);
        assertThat(first.body().path("completed_work").get(0).path("goal").asText()).isEqualTo("Verify the API document version");
        assertThat(first.body().path("unresolved_questions").toString()).contains("Verify authentication header","per-minute");
        var load=request("POST","/api/research/projects/"+old.project()+"/resume-context",user(owner),object());
        assertThat(load.status()).isEqualTo(200);
        criterionHttpScenario("complete",owner,old.project(),load.body().path("target_session_id").asText());Run updated=last(owner);
        Files.copy(Path.of("target/criterion-http-complete.json"),dir.resolve("native-complete.json"),StandardCopyOption.REPLACE_EXISTING);
        settleSave(updated);var second=snapshot(updated);assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().path("completed_work")).hasSize(3);
        assertThat(second.body().path("completed_work").get(0).path("completion_verified").asBoolean()).isTrue();
        assertThat(second.body().path("unresolved_questions")).hasSize(2);
        assertThat(second.body().path("unresolved_questions").toString()).contains("Verify authentication header");
        // Same goal text with unknown old criteria cannot be closed by a narrower native proof.
        assertThat(second.body().path("unresolved_questions")).anyMatch(g->
            g.path("goal").asText().equals("Verify API document version and per-minute request rate") && !g.has("criteria") && g.path("historical").asBoolean());
        assertThat(second.body().path("unresolved_questions")).noneMatch(g->
            g.path("goal").asText().equals("Verify the per-minute request rate"));
        String note="下一次使用机器 C 核查认证要求；旧条件不适用于这次研究。";
        var patched=correction(updated,note);assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.body().path("completed_work")).isEqualTo(second.body().path("completed_work"));
        assertThat(patched.body().path("user_correction").asText()).isEqualTo(note);
        assertThat(correction(updated,"x".repeat(2001)).status()).isEqualTo(400);
        assertThat(request("PATCH","/api/research/projects/"+updated.project()+"/progress/runs/"+updated.id(),user("foreign"),object("note","foreign")).status()).isEqualTo(404);
        assertThat(request("GET","/api/research/agents/"+updated.id()+"/progress-save",user("foreign"),null).status()).isEqualTo(404);
        // Recreated coordinator simulates application recovery from the same durable terminal/outcome rows.
        new ResearchProgressAutoSaveService(db,progress,memories,identities,transactions,false).reconcile();saves.reconcile();
        assertThat(db.queryForObject("SELECT count(*) FROM research_progress_memory WHERE run_id=?",Integer.class,updated.id())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_workflow_event WHERE run_id=? AND event_key='progress:auto-save'",Integer.class,updated.id())).isEqualTo(1);
        var next=request("POST","/api/research/projects/"+updated.project()+"/resume-context",user(owner),object());
        var created=post("/api/research/agents",owner,"m3-next-"+UUID.randomUUID(),object("question","核查机器 C 条件下的 API 认证要求。",
            "sessionId",next.body().path("target_session_id").asText(),"requestedTools",List.of("kb_search"),"researchProjectId",updated.project()));
        assertThat(created.status()).withFailMessage(created.body().toString()).isEqualTo(202);Run continuation=last(owner);
        Path config=Files.createTempFile("memory-m3-",".json");Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var settings=object("url","http://127.0.0.1:"+port,"token",service(),"viewer_token",user(owner),"run",continuation.id(),
            "provider","controlled","scenario","m3","output",dir.resolve("native-next-planner.json").toString());
        Process worker=null;
        try {
            Files.writeString(config,canonical(settings));worker=probe(config,dir.resolve("native-next-planner.log"),false);
            assertThat(worker.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(worker.exitValue()).withFailMessage(Files.readString(dir.resolve("native-next-planner.log"))).isZero();
            var captured=JSON.readTree(Files.readString(dir.resolve("native-next-planner.json")));
            var wire=JSON.readTree(captured.path("calls").get(0).path("wire").path("messages").get(1).path("content").asText());
            assertThat(wire.path("prior_progress").toString()).contains(note,"Verify authentication header","completion_verified");
            assertThat(wire.path("prior_progress").path("trusted_as_evidence").asBoolean()).isFalse();
            settleSave(continuation);var third=snapshot(continuation);assertThat(third.status()).withFailMessage(third.body().toString()).isEqualTo(200);
            assertThat(third.body().path("unresolved_questions").toString()).contains("Verify authentication header","机器 C");
            assertThat(third.body().path("historical_completed_work")).hasSize(3);
            assertThat(third.body().path("user_correction").asText()).isEqualTo(note);
            assertThat(third.body().path("completed_work")).isEmpty();
            Files.writeString(dir.resolve("http-examples.json"),canonical(object("first_snapshot",first.body(),"updated_snapshot",patched.body(),
                "new_snapshot",third.body(),"save_status",outcome(continuation).body(),"scenario","controlled originals/provider, real native proofs and new planner process")));
            long keep=Long.getLong("memory.demo.keepAliveSeconds",0L);
            if(keep>0) {
                Run broken=failed(owner,true);saves.reconcile();
                Files.writeString(dir.resolve("demo-private.json"),canonical(object("base_url","http://127.0.0.1:"+port,
                    "viewer_token",users.issue("tenant-http",owner,List.of("USER"),keep+120).authorizationHeader(),"project_id",updated.project(),
                    "run_id",continuation.id(),"updated_run_id",updated.id(),"failed_save_run_id",broken.id(),"source_run_id",old.id(),"provider","controlled","fixture_only",true)));
                Files.setPosixFilePermissions(dir.resolve("demo-private.json"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                Files.writeString(dir.resolve("demo-ready.json"),canonical(object("base_url","http://127.0.0.1:"+port,"project_id",updated.project(),
                    "run_id",continuation.id(),"updated_run_id",updated.id(),"failed_save_run_id",broken.id(),"provider","controlled","fixture_only",true)));
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(keep);
                while(System.nanoTime()<until && !Files.exists(dir.resolve("demo-stop"))) Thread.sleep(1000);
            }
            // Ancestor changes revoke dependent aggregates; deletion is durable and never silently restored.
            assertThat(correction(updated,"机器 D，已替代机器 C").status()).isEqualTo(200);
            assertThat(snapshot(continuation).status()).isEqualTo(404);
            assertThat(outcome(continuation).body().path("status").asText()).isEqualTo("UNAVAILABLE");
            assertThat(request("DELETE","/api/research/projects/"+updated.project()+"/progress/runs/"+updated.id(),user(owner),null).status()).isEqualTo(200);
            saves.reconcile();new ResearchProgressAutoSaveService(db,progress,memories,identities,transactions,false).reconcile();
            assertThat(snapshot(updated).status()).isEqualTo(404);assertThat(outcome(updated).body().path("status").asText()).isEqualTo("DELETED");
            var available=request("POST","/api/research/projects/"+updated.project()+"/resume-context",user(owner),object());
            assertThat(available.body().toString()).doesNotContain(updated.id(),continuation.id(),"机器 C","机器 D");
            Files.writeString(dir.resolve("revocation-result.json"),canonical(object("dependent_status",outcome(continuation).body(),"deleted_status",outcome(updated).body(),"remaining_selection",available.body())));
        } finally {if(worker!=null&&worker.isAlive())worker.destroy();Files.deleteIfExists(config);}
    }
    @Test void failedSaveNeverRollsBackRunAndPreSaveDeletionAndDisabledRunsStayExcluded() throws Exception {
        String owner="m3-failure-"+UUID.randomUUID();Run failed=failed(owner,true);
        saves.reconcile();var state=outcome(failed);assertThat(state.body().path("status").asText()).isEqualTo("FAILED");
        assertThat(state.body().path("error_code").asText()).isEqualTo("PROGRESS_CAPACITY_EXCEEDED");
        var original=request("GET","/api/research/workflows/"+failed.id(),user(owner),null);
        assertThat(original.body().path("status").asText()).isEqualTo("FAILED");
        assertThat(original.body().path("errorCode").asText()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(snapshot(failed).status()).isEqualTo(404);
        saves.reconcile();assertThat(outcome(failed).body()).isEqualTo(state.body());
        Run deleted=failed(owner,false);
        assertThat(request("DELETE","/api/research/projects/"+deleted.project()+"/progress/runs/"+deleted.id(),user(owner),null).status()).isEqualTo(200);
        saves.reconcile();assertThat(outcome(deleted).body().path("status").asText()).isEqualTo("DELETED");assertThat(snapshot(deleted).status()).isEqualTo(404);
        Run disabled=failed(owner,false);db.update("UPDATE agent_workflow_run SET context_snapshot=context_snapshot-'project_progress_policy' WHERE run_id=?",disabled.id());
        saves.reconcile();assertThat(outcome(disabled).body().path("status").asText()).isEqualTo("NOT_ENABLED");assertThat(snapshot(disabled).status()).isEqualTo(404);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='MODEL'",Integer.class,failed.id())).isZero();
        Files.writeString(results().resolve("failure-cases.json"),canonical(object("save_failure",state.body(),"unchanged_run",original.body(),"pre_save_delete",outcome(deleted).body(),"disabled",outcome(disabled).body())));
    }
    @Test void sharedAncestorValidationHasGlobalQueryBoundAndRevokesChangedOrDeletedSource() throws Exception {
        String owner="m3-dag-"+UUID.randomUUID();var principal=new com.deepresearch.security.AuthPrincipal("tenant-http",owner,List.of("USER"));
        Run initial=failed(owner,false);
        var records=new ArrayList<JsonNode>();var runs=new ArrayList<Run>();
        // Synthetic pending snapshots test graph shape only; no native completed fact is fabricated.
        for(int i=0;i<21;i++) {
            Run run=i==0?initial:failed(owner,false,initial.project(),memories.createSession(principal));runs.add(run);
            var refs=new ArrayList<JsonNode>();
            for(int j=Math.max(0,i-3);j<i;j++) refs.add(object("source_run_id",runs.get(j).id(),"snapshot_sha256",sha(canonical(records.get(j)))));
            JsonNode row=object("schema_version","research-progress/1","context_kind","prior_progress","trusted_as_evidence",false,
                "source_run_id",run.id(),"source_session_id",db.queryForObject("SELECT session_id FROM agent_workflow_run WHERE run_id=?",String.class,run.id()),
                "project_id",run.project(),"original_goal","Synthetic pending graph","next_steps",List.of(),
                "completed_work",List.of(),"unresolved_questions",List.of(),"source_evidence",List.of(),"source_claims",List.of(),"prior_memory_refs",refs);
            memories.save(run.project(),run.id(),principal,row);records.add(row);
        }
        class CountedRepository extends ResearchProgressRepository {
            int reads;
            CountedRepository(){super(db);}
            @Override List<JsonNode> saved(String project,com.deepresearch.security.AuthPrincipal p,String run){reads++;return super.saved(project,p,run);}
            @Override List<JsonNode> evidence(String project,String run,com.deepresearch.security.AuthPrincipal p){reads++;return super.evidence(project,run,p);}
            @Override List<JsonNode> claimStates(String project,String run,com.deepresearch.security.AuthPrincipal p){reads++;return super.claimStates(project,run,p);}
        }
        var bounded=new CountedRepository();
        assertThat(bounded.referencesAccessible(initial.project(),principal,records.get(19))).isTrue();
        assertThat(bounded.reads).isEqualTo(59); // 19 ancestor loads + evidence/claims once per 20 nodes.
        bounded.reads=0;assertThat(bounded.referencesAccessible(initial.project(),principal,records.get(20))).isFalse();
        assertThat(bounded.reads).isLessThanOrEqualTo(59);
        var changed=records.get(0).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("user_correction","Changed annotation");
        memories.save(initial.project(),initial.id(),principal,changed);
        assertThat(bounded.referencesAccessible(initial.project(),principal,records.get(19))).isFalse();
        memories.delete(initial.project(),initial.id(),principal);
        assertThat(bounded.referencesAccessible(initial.project(),principal,records.get(19))).isFalse();
        Files.writeString(results().resolve("dependency-bound.json"),canonical(object("fixture_only",true,"unique_snapshot_limit",20,"valid_dag_queries",59,"over_limit_rejected",true,"changed_source_rejected",true,"deleted_source_rejected",true)));
    }
}

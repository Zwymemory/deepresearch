package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

/** M4 fixed six cases over native owned HTTP/PG and real production planner wire. */
class MemoryRecallIT extends MemoryContinuationIT {
    @org.springframework.beans.factory.annotation.Autowired ResearchProgressAutoSaveService saves;
    Run recallHistory(String owner) throws Exception {
        var run=history(owner);var p=new AuthPrincipal("tenant-http",owner,List.of("USER"));
        var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)memory.saved(run.project(),p,run.id()).get(0);
        snapshot.put("original_goal","Kafka 检索效果与延迟 v1.0");snapshot.put("current_question","Kafka 检索效果与延迟 v1.0");
        memory.save(run.project(),run.id(),p,snapshot);return run;
    }
    String fresh(String owner,String question,boolean enabled,String key) throws Exception {
        var accepted=post("/api/research/agents",owner,key,object("question",question,"requestedTools",List.of("kb_search"),"memoryRecall",enabled));
        assertThat(accepted.status()).withFailMessage(accepted.body().toString()).isEqualTo(202);return accepted.body().path("runId").asText();
    }
    JsonNode context(String run) throws Exception {return JSON.readTree(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",String.class,run));}
    JsonNode capture(String owner,String run,Path dir,String name,JsonNode extra) throws Exception {
        Files.createDirectories(dir);Path cfg=Files.createTempFile("memory-m4-",".json"),output=dir.resolve(name+"-provider.json");
        var settings=object("url","http://127.0.0.1:"+port,"token",service(),"viewer_token",user(owner),"run",run,
            "output",output.toString(),"provider","controlled","scenario","m4");
        if(extra!=null)settings.setAll((com.fasterxml.jackson.databind.node.ObjectNode)extra);
        Files.setPosixFilePermissions(cfg,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));Files.writeString(cfg,canonical(settings));
        try {var worker=probe(cfg,dir.resolve(name+"-probe.log"),false);
            assertThat(worker.waitFor(180,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(worker.exitValue()).withFailMessage(Files.readString(dir.resolve(name+"-probe.log"))).isZero();
            return JSON.readTree(Files.readString(output));
        } finally {Files.deleteIfExists(cfg);}
    }
    @Test void fixedSixCasesProductionWireAndSameBudgetComparison() throws Exception {
        String owner="m4-"+UUID.randomUUID();Run old=recallHistory(owner),foreign=recallHistory("foreign-"+owner);
        var p=new AuthPrincipal("tenant-http",owner,List.of("USER"));
        var deleted=recallHistory(owner);assertThat(request("DELETE","/api/research/projects/"+deleted.project()+"/progress/runs/"+deleted.id(),user(owner),null).status()).isEqualTo(200);
        String question="Kafka v2.0 的检索延迟如何在同数据集、同硬件下核查？";
        String enabled=fresh(owner,question,true,"enabled-"+UUID.randomUUID());
        var recall=context(enabled).path("recalled_progress");assertThat(recall.path("records").size()).isEqualTo(1);
        assertThat(recall.toString()).contains(old.id(),"VERSION_DIFFERENCE","DISPUTED_OR_UNVERIFIED","同硬件").doesNotContain(foreign.id(),deleted.id());
        assertThat(db.queryForObject("SELECT project_id FROM agent_research_run WHERE run_id=?",String.class,enabled)).isNotEqualTo(old.project());
        assertThat(request("GET","/api/research/agents/"+enabled+"/memory-recall",user(owner),null).body().path("status").asText()).isEqualTo("SELECTED");
        assertThat(request("GET","/api/research/agents/"+enabled+"/memory-recall",user("outsider"),null).status()).isEqualTo(404);
        String unrelated=fresh(owner,"香蕉种植土壤与浇水要求",true,"unrelated-"+UUID.randomUUID());
        assertThat(context(unrelated).path("recalled_progress").path("records")).isEmpty();
        assertThat(request("GET","/api/research/agents/"+unrelated+"/memory-recall",user(owner),null).body().path("status").asText()).isEqualTo("EMPTY");
        // Leave only the specific A/B run queued for the real worker claim_next.
        db.update("UPDATE agent_workflow_run SET status='CANCELLED',stage='CANCELLED' WHERE run_id=?",unrelated);
        Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m4")).toAbsolutePath();
        var with=capture(owner,enabled,dir,"with-memory",null);
        String wire=with.path("calls").get(0).path("wire").path("messages").get(1).path("content").asText();
        assertThat(wire).contains("recalled_progress",old.id(),"VERSION_DIFFERENCE","contested").doesNotContain(foreign.id(),deleted.id());
        var used=request("GET","/api/research/agents/"+enabled+"/memory-recall",user(owner),null);
        assertThat(used.body().path("status").asText()).isEqualTo("USED");assertThat(used.body().path("planner_input_recorded").asBoolean()).isTrue();
        String key="disabled-"+UUID.randomUUID(),disabled=fresh(owner,question,false,key);
        var without=capture(owner,disabled,dir,"without-memory",null);
        String baseline=without.path("calls").get(0).path("wire").path("messages").get(1).path("content").asText();
        assertThat(baseline).doesNotContain("recalled_progress",old.id());
        assertThat(context(disabled).has("recalled_progress")).isFalse();
        assertThat(request("GET","/api/research/agents/"+disabled+"/memory-recall",user(owner),null).body().path("status").asText()).isEqualTo("DISABLED");
        assertThat(post("/api/research/agents",owner,key,object("question",question,"requestedTools",List.of("kb_search"),"memoryRecall",true)).status()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT budget::text FROM agent_workflow_run WHERE run_id=?",String.class,enabled)).isEqualTo(db.queryForObject("SELECT budget::text FROM agent_workflow_run WHERE run_id=?",String.class,disabled));
        assertThat(request("DELETE","/api/research/projects/"+old.project()+"/progress/runs/"+old.id(),user(owner),null).status()).isEqualTo(200);
        var unavailable=request("GET","/api/research/agents/"+enabled+"/memory-recall",user(owner),null);
        assertThat(unavailable.body().path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(unavailable.body().path("records")).isEmpty();
        String claim=UUID.randomUUID().toString();db.update("UPDATE agent_workflow_run SET status='PLANNING',claim_token=?::uuid,lease_until=now()+interval '90 seconds' WHERE run_id=?",claim,enabled);
        var req=object("runId",enabled,"claimToken",claim,"projectionSha256",context(enabled).path("recalled_progress_binding").path("projection_sha256"));
        assertThat(request("POST","/internal/agent/memory/recall/validate",user(owner),req).status()).isEqualTo(401);
        var revoked=request("POST","/internal/agent/memory/recall/validate",service(),req);assertThat(revoked.status()).isEqualTo(409);assertThat(revoked.body().path("errorCode").asText()).isEqualTo("RESEARCH_MEMORY_REVOKED");
        db.update("UPDATE agent_workflow_run SET status='INSUFFICIENT_EVIDENCE',claim_token=NULL,lease_until=NULL WHERE run_id=?",enabled);
        Files.writeString(dir.resolve("fixed-six-cases.json"),canonical(object("related",true,"unrelated_empty",true,"version_recheck",true,"dispute_preserved",true,"deleted_excluded",true,"foreign_excluded",true,
            "used_view",used.body(),"revoked_view",unavailable.body(),"with_input_bytes",wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            "without_input_bytes",baseline.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"provider","controlled","same_question_and_budget",true,
            "plan_quality_observation","Controlled fixture preserves current obligations in both; recall rationale references historical version/dispute. This is a mechanism check, not model quality uplift.",
            "repeated_retrieval_with",0,"repeated_retrieval_without",0,"limitations","No retrieval allowed by the controlled fixture; no repeated-search improvement claim.")));
        long keep=Long.getLong("memory.demo.keepAliveSeconds",0L);
        if(keep>0) {
            var demo=recallHistory(owner);
            // Actual native original/Claim publication, then owned manual snapshot: screenshot source disclosure.
            criterionHttpScenario("complete",owner,null,null);
            String nativeRun=db.queryForObject("SELECT run_id FROM agent_workflow_run WHERE user_id=? ORDER BY created_at DESC LIMIT 1",String.class,"tenant-http:"+owner);
            String nativeProject=memory.projectForRun(nativeRun,p);
            assertThat(request("PUT","/api/research/projects/"+nativeProject+"/progress/runs/"+nativeRun,user(owner),null).status()).isEqualTo(200);
            Files.writeString(dir.resolve("demo-private.json"),canonical(object("base_url","http://127.0.0.1:"+port,
                "viewer_token",users.issue("tenant-http",owner,List.of("USER"),keep+120).authorizationHeader(),"project_id",demo.project(),"source_run_id",demo.id(),"provider","controlled","question",question,
                "native_source_run_id",nativeRun,"native_source_project_id",nativeProject,
                "native_source_question","Verify API document version and per-minute request rate for version 3.0")));
            Files.setPosixFilePermissions(dir.resolve("demo-private.json"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Path cfg=Files.createTempFile("memory-m4-demo-",".json");Files.setPosixFilePermissions(cfg,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            var settings=object("url","http://127.0.0.1:"+port,"token",service(),"viewer_token",user(owner),"run",disabled,"output",dir.resolve("demo-provider.json").toString(),"provider","controlled","scenario","m4");Files.writeString(cfg,canonical(settings));
            var worker=probe(cfg,dir.resolve("demo-worker.log"),true);
            Files.writeString(dir.resolve("demo-ready.json"),canonical(object("base_url","http://127.0.0.1:"+port,"provider","controlled","fixture_only",true,"question",question)));
            try {long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(keep);
                while(System.nanoTime()<until && !Files.exists(dir.resolve("demo-stop"))) {
                    settings.put("token",service());Path next=Files.createTempFile(cfg.getParent(),"memory-m4-token-",".json");Files.setPosixFilePermissions(next,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));Files.writeString(next,canonical(settings));Files.move(next,cfg,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Thread.sleep(20000);
                }
            } finally {worker.destroy();Files.deleteIfExists(cfg);}
        }
    }
    @Test void deletionOnRestartBlocksSettledPlannerReplayBeforeProvider() throws Exception {
        String owner="m4-restart-"+UUID.randomUUID();var old=recallHistory(owner);
        String run=fresh(owner,"Kafka 检索延迟 v2.0",true,"restart-"+UUID.randomUUID());Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m4")).toAbsolutePath();
        var first=capture(owner,run,dir,"restart-first",object("interrupt_after_decision",true));assertThat(first.path("interrupted").asBoolean()).isTrue();assertThat(first.path("calls")).isNotEmpty();
        memory.delete(old.project(),old.id(),new AuthPrincipal("tenant-http",owner,List.of()));
        db.update("UPDATE agent_workflow_run SET status='QUEUED',stage='QUEUED',lease_until=NULL,claim_token=NULL WHERE run_id=?",run);
        var second=capture(owner,run,dir,"restart-revoked",object("expected_status","FAILED","expected_error","RESEARCH_MEMORY_REVOKED"));assertThat(second.path("calls")).isEmpty();
    }
    @Test void nativeOriginalSourcesAreDeduplicatedAcrossOwnedStudies() throws Exception {
        String owner="m4-sources-"+UUID.randomUUID();
        criterionHttpScenario("complete",owner,null,null);saves.reconcile();
        criterionHttpScenario("complete",owner,null,null);saves.reconcile();
        String run=fresh(owner,"Verify API document version and per-minute request rate for version 3.0",true,"source-"+UUID.randomUUID());
        var recall=context(run).path("recalled_progress");
        assertThat(recall.path("records")).hasSize(1);
        assertThat(recall.path("selection").path("duplicates").asInt()).isEqualTo(1);
        assertThat(recall.path("records").get(0).path("source_refs")).hasSize(1);
        assertThat(recall.path("records").get(0).path("source_refs").get(0).path("independent_evidence").asBoolean()).isFalse();
        Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m4")).toAbsolutePath();Files.createDirectories(dir);
        Files.writeString(dir.resolve("native-source-dedup.json"),canonical(recall));
        db.update("UPDATE agent_workflow_run SET status='CANCELLED',stage='CANCELLED' WHERE run_id=?",run);
    }

    @Test void liveSameQuestionAndBudgetComparison() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("live-deepseek".equals(System.getProperty("memory.provider")));
        String owner="m4-live-"+UUID.randomUUID();recallHistory(owner);
        String question="Kafka v2.0 的检索延迟如何在同数据集、同硬件下核查？保留未测量事项与效果争议，不编造测量值。";
        Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m4-live")).toAbsolutePath();
        var extra=object("provider","live-deepseek","private_credentials",Objects.requireNonNull(System.getenv("MEMORY_PRIVATE_CREDENTIALS")));
        var results=new ArrayList<JsonNode>();
        for(boolean enabled:List.of(false,true)) {
            String run=fresh(owner,question,enabled,"live-"+UUID.randomUUID());
            var observed=capture(owner,run,dir,enabled?"with-memory":"without-memory",extra);
            var usage=db.queryForMap("SELECT COALESCE(sum((actual_usage->>'input_tokens')::bigint),0) AS input_tokens,COALESCE(sum((actual_usage->>'output_tokens')::bigint),0) AS output_tokens,count(*) AS model_calls FROM agent_research_operation WHERE run_id=? AND kind='MODEL' AND status='SETTLED'",run);
            var toolRows=db.queryForList("SELECT operation_key,request_hash,status FROM agent_research_operation WHERE run_id=? AND kind='TOOL' ORDER BY created_at",run);
            results.add(object("memory_enabled",enabled,"run_id",run,"question",question,"budget",JSON.readTree(db.queryForObject("SELECT budget::text FROM agent_workflow_run WHERE run_id=?",String.class,run)),
                "usage",usage,"tool_operations",toolRows,"recall_view",request("GET","/api/research/agents/"+run+"/memory-recall",user(owner),null).body(),"status",observed.path("status").path("status")));
        }
        assertThat(results.get(0).path("budget")).isEqualTo(results.get(1).path("budget"));Files.writeString(dir.resolve("ab-native-results.json"),canonical(JSON.valueToTree(results)));
    }

    @Test void explicitContinuationIsNotDuplicatedAndReplayNeverReselects() throws Exception {
        String owner="m4-explicit-"+UUID.randomUUID();var old=recallHistory(owner);
        var body=object("question","Kafka 检索延迟 v2.0","requestedTools",List.of("kb_search"),"researchProjectId",old.project());
        String key="explicit-"+UUID.randomUUID();var accepted=post("/api/research/agents",owner,key,body);
        assertThat(accepted.status()).isEqualTo(202);String run=accepted.body().path("runId").asText();var before=context(run);
        assertThat(before.path("prior_progress").path("records")).hasSize(1);assertThat(before.path("recalled_progress").path("records")).isEmpty();
        var p=new AuthPrincipal("tenant-http",owner,List.of());memory.delete(old.project(),old.id(),p);
        var replay=post("/api/research/agents",owner,key,body);assertThat(replay.status()).isEqualTo(202);assertThat(replay.body().path("runId").asText()).isEqualTo(run);assertThat(context(run)).isEqualTo(before);
        db.update("UPDATE agent_workflow_run SET status='CANCELLED',stage='CANCELLED' WHERE run_id=?",run);
    }

    @Test void disabledRecallKeepsLongHistorySummaryWithinBudgetAndRestartDoesNotRepeatIt() throws Exception {
        String owner="m4-summary-off-"+UUID.randomUUID();var old=history(owner);String session=longSession(owner,old);
        var accepted=post("/api/research/agents",owner,"summary-off-"+UUID.randomUUID(),object("question","接着做，先推进尚未完成的部分。",
            "sessionId",session,"requestedTools",List.of("kb_search"),"researchProjectId",old.project(),"memoryRecall",false));
        assertThat(accepted.status()).isEqualTo(202);String run=accepted.body().path("runId").asText();
        Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m4")).toAbsolutePath();
        var first=capture(owner,run,dir,"disabled-summary-first",object("scenario","m1","interrupt_after_decision",true));
        assertThat(first.path("interrupted").asBoolean()).isTrue();
        db.update("UPDATE agent_workflow_run SET lease_until=now()-interval '1 second' WHERE run_id=?",run);
        var second=capture(owner,run,dir,"disabled-summary-restart",object("scenario","m1"));
        assertThat(second.path("calls").toString()).doesNotContain("source_segments");
        var view=request("GET","/api/research/agents/"+run+"/context-summary",user(owner),null).body();
        assertThat(view.path("status").asText()).isEqualTo("READY");assertThat(view.path("planner_input_recorded").asBoolean()).isTrue();
        assertThat(view.path("measurement").path("after_bytes").asInt()).isLessThanOrEqualTo(24000);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND purpose='SUMMARY'",Integer.class,run)).isEqualTo(1);
        Files.writeString(dir.resolve("disabled-summary-view.json"),canonical(view));
    }

}

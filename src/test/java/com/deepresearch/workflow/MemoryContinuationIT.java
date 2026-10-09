package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

/** M1 real native HTTP, isolated Flyway/PG, Python checkpoint/ledger and production adapter. */
class MemoryContinuationIT extends AgentHttpPostgresIT {
    @Autowired ResearchProgressRepository memory;
    final List<JsonNode> examples=new ArrayList<>();
    @Test void exerciseReferentSurvivesBothSessionFollowupAndExplicitProjectResume() throws Exception {
        String owner="exercise-"+UUID.randomUUID();
        Run old=history(owner);
        String answer="题目：写一个 OrderService，构造器注入 PaymentGateway；说明为何无需手动 new。";
        db.update("UPDATE agent_workflow_run SET status='SUCCEEDED',stage='SUCCEEDED',final_response=?::jsonb WHERE run_id=?",
                canonical(object("answer",answer)),old.id());
        String originalSession=db.queryForObject("SELECT session_id FROM agent_workflow_run WHERE run_id=?",String.class,old.id());
        var same=post("/api/research/agents",owner,"same-"+UUID.randomUUID(),object(
                "question","这道题的答案是？","sessionId",originalSession,"requestedTools",List.of("web_search")));
        assertThat(same.status()).isEqualTo(202);
        var sameContext=JSON.readTree(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",
                String.class,same.body().path("runId").asText()));
        assertThat(sameContext.path("conversation_context").path("reports").get(0).path("answer").asText()).isEqualTo(answer);
        assertThat(sameContext.path("conversation_context").path("schema_version").asText()).isEqualTo("conversation-referents/2");
        assertThat(sameContext.path("conversation_context").path("reports").get(0).path("origin").asText()).isEqualTo("learning_project");
        assertThat(sameContext.has("learning_memory_binding")).isTrue();

        db.update("UPDATE agent_workflow_run SET status='INSUFFICIENT_EVIDENCE',stage='INSUFFICIENT_EVIDENCE',final_response=?::jsonb WHERE run_id=?",
                canonical(object("answer","尚无完成核查的主张","claims",null)),same.body().path("runId").asText());
        var repeated=post("/api/research/agents",owner,"same-after-failure-"+UUID.randomUUID(),object(
                "question","这道题的答案是？","sessionId",originalSession,"requestedTools",List.of("web_search")));
        assertThat(repeated.status()).isEqualTo(202);
        var repeatedContext=JSON.readTree(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",
                String.class,repeated.body().path("runId").asText()));
        assertThat(repeatedContext.path("conversation_context").path("reports")).hasSize(1);
        assertThat(repeatedContext.path("conversation_context").path("reports").get(0).path("answer").asText()).isEqualTo(answer);

        var loaded=request("POST","/api/research/projects/"+old.project()+"/resume-context",user(owner),object());
        String session=loaded.body().path("target_session_id").asText();
        var body=object("question","这道题的答案是？","sessionId",session,"requestedTools",List.of("web_search"),"researchProjectId",old.project());
        String key="exercise-"+UUID.randomUUID();
        var next=post("/api/research/agents",owner,key,body);
        assertThat(next.status()).isEqualTo(202);
        String run=next.body().path("runId").asText();
        var context=JSON.readTree(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",String.class,run));
        var conversation=context.path("conversation_context");
        assertThat(conversation.path("trusted_as_evidence").asBoolean()).isFalse();
        assertThat(conversation.path("reports").get(0).path("answer").asText()).isEqualTo(answer);
        assertThat(conversation.path("schema_version").asText()).isEqualTo("conversation-referents/2");
        assertThat(conversation.path("reports").get(0).path("origin").asText()).isEqualTo("learning_project");
        assertThat(conversation.path("reports").get(0).path("run_id").asText()).isEqualTo(old.id());
        // A later empty failure may consume the progress budget, but its bound
        // predecessor must still supply the exercise rather than the failure boilerplate.
        var principal=new AuthPrincipal("tenant-http",owner,List.of("USER"));
        var predecessor=memory.saved(old.project(),principal,old.id()).get(0);
        com.fasterxml.jackson.databind.node.ObjectNode failed=predecessor.deepCopy();
        failed.put("source_run_id",run).put("source_session_id",session);
        failed.set("source_claims",JSON.createArrayNode());
        failed.set("prior_memory_refs",JSON.valueToTree(List.of(object("source_run_id",old.id(),"snapshot_sha256",sha(canonical(predecessor))))));
        failed.put("user_correction","x".repeat(Math.max(0,15500-canonical(failed).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
        db.update("UPDATE agent_workflow_run SET status='INSUFFICIENT_EVIDENCE',stage='INSUFFICIENT_EVIDENCE',final_response=?::jsonb WHERE run_id=?",
                canonical(object("answer","尚无完成核查的主张","claims",List.of())),run);
        memory.save(old.project(),run,principal,failed);
        var reloaded=request("POST","/api/research/projects/"+old.project()+"/resume-context",user(owner),object());
        var afterFailure=post("/api/research/agents",owner,"after-failure-"+key,object("question","这道题的答案是？",
                "sessionId",reloaded.body().path("target_session_id").asText(),"requestedTools",List.of("web_search"),"researchProjectId",old.project()));
        assertThat(afterFailure.status()).isEqualTo(202);
        var afterContext=JSON.readTree(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",String.class,afterFailure.body().path("runId").asText()));
        assertThat(afterContext.path("prior_progress").path("records")).hasSize(1);
        assertThat(afterContext.path("conversation_context").path("reports").get(0).path("answer").asText()).isEqualTo(answer);
        // Accepted replay keeps exactly the original context, even if history later changes.
        db.update("UPDATE agent_workflow_run SET final_response=?::jsonb WHERE run_id=?",canonical(object("answer","changed")),old.id());
        assertThat(post("/api/research/agents",owner,key,body).body().path("runId").asText()).isEqualTo(run);
        assertThat(JSON.readTree(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",String.class,run))).isEqualTo(context);
        assertThat(post("/api/research/agents","foreign-"+owner,"foreign-"+key,body).status()).isEqualTo(404);
        // Arbitrary projects and recalled history are not used by this referent channel.
        var fresh=post("/api/research/agents",owner,"fresh-"+UUID.randomUUID(),object("question","这道题的答案是？","requestedTools",List.of("web_search")));
        var freshContext=JSON.readTree(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",String.class,fresh.body().path("runId").asText()));
        assertThat(freshContext.has("conversation_context")).isFalse();
    }
    Reply post(String path,String owner,String key,JsonNode body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .timeout(Duration.ofSeconds(20)).header("Authorization",user(owner))
            .header("Content-Type","application/json").header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofString(canonical(body))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        var value=JSON.readTree(response.body());
        examples.add(object("method","POST","path",path,"request",body,"status",response.statusCode(),"response",value));
        return new Reply(response.statusCode(),value);
    }
    Run history(String owner) throws Exception {
        Run run=create(owner);
        db.update("UPDATE agent_workflow_run SET status='INSUFFICIENT_EVIDENCE',stage='INSUFFICIENT_EVIDENCE',claim_token=NULL,lease_until=NULL,question=? WHERE run_id=?",
            "比较方案 A、B 的检索效果与延迟",run.id());
        var claim=object("claim_id","quality-disputed","decision_status","contested","freshness","fresh");
        db.update("INSERT INTO agent_evidence_record(tenant_id,owner_id,project_id,record_type,record_id,version,run_id,payload,payload_sha256) VALUES ('tenant-http',?,?,'Claim','quality-disputed',1,?,?::jsonb,?)",
            owner,run.project(),run.id(),canonical(claim),sha(canonical(claim)));
        String session=db.queryForObject("SELECT session_id FROM agent_workflow_run WHERE run_id=?",String.class,run.id());
        var snapshot=object("schema_version","research-progress/1","context_kind","prior_progress","trusted_as_evidence",false,
            "project_id",run.project(),"source_run_id",run.id(),"source_session_id",session,
            "original_goal","比较方案 A、B 的检索效果与延迟","run_status","INSUFFICIENT_EVIDENCE",
            "completed_work",List.of(object("goal","已整理两种方案的检索机制说明","completion_verified",false,"fixture_only",true)),
            "unresolved_questions",List.of(object("goal","在相同数据集、相同硬件下测量延迟",
                "criteria",List.of("同数据集","同硬件","无实测数据不得编造数值"),"gaps",List.of("延迟未测量","效果结论有争议"))),
            "next_steps",List.of("优先推进延迟测量；保留效果争议"),"source_evidence",List.of(),
            "source_claims",memory.claimStates(run.project(),run.id(),new AuthPrincipal("tenant-http",owner,List.of("USER"))));
        memory.save(run.project(),run.id(),new AuthPrincipal("tenant-http",owner,List.of("USER")),snapshot);
        return run;
    }
    @Test void crossSessionCreationValidationAndActualPythonProviderWire() throws Exception {
        String owner="m1-"+UUID.randomUUID(); Run old=history(owner),other=history(owner);
        int count=db.queryForObject("SELECT count(*) FROM agent_workflow_run",Integer.class);
        var loaded=request("POST","/api/research/projects/"+old.project()+"/resume-context",user(owner),object());
        assertThat(loaded.status()).isEqualTo(200);
        String session=loaded.body().path("target_session_id").asText();
        assertThat(session).isNotBlank();
        assertThat(request("GET","/api/research/projects/"+old.project()+"/resume-context?sessionId="+session,user(owner),null).status()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_workflow_run",Integer.class)).isEqualTo(count);
        var body=object("question","接着做，先推进尚未完成的部分。","sessionId",session,
            "requestedTools",List.of("kb_search"),"researchProjectId",old.project());
        String key="m1-"+UUID.randomUUID(); var accepted=post("/api/research/agents",owner,key,body);
        assertThat(accepted.status()).withFailMessage(accepted.body().toString()).isEqualTo(202);
        String run=accepted.body().path("runId").asText();
        assertThat(db.queryForObject("SELECT project_id FROM agent_research_run WHERE run_id=?",String.class,run)).isEqualTo(old.project());
        JsonNode context=JSON.readTree(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",String.class,run));
        assertThat(context.path("prior_progress").path("trusted_as_evidence").asBoolean()).isFalse();
        assertThat(context.path("prior_progress").toString()).contains("延迟未测量","contested","同硬件");
        var replay=post("/api/research/agents",owner,key,body);assertThat(replay.status()).isEqualTo(202);
        assertThat(replay.body().path("runId").asText()).isEqualTo(run);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_research_run WHERE run_id=?",Integer.class,run)).isEqualTo(1);
        assertThat(post("/api/research/agents",owner,"new-"+key,object("question","Continue","sessionId",session,
            "requestedTools",List.of("kb_search"),"researchProjectId",other.project())).status()).isEqualTo(404);
        assertThat(post("/api/research/agents","foreign-owner","foreign-"+key,body).status()).isEqualTo(404);
        assertThat(post("/api/research/agents",owner,"bad-"+key,object("question","Continue","prior_progress",context)).status()).isEqualTo(400);
        // Creation rollback and owner/session binding do not leave a ghost run.
        assertThat(db.queryForObject("SELECT count(*) FROM agent_workflow_run",Integer.class)).isEqualTo(count+1);
        String claim=UUID.randomUUID().toString();
        db.update("UPDATE agent_workflow_run SET status='PLANNING',stage='PLANNING',claim_token=?::uuid,lease_until=now()+interval '120 seconds' WHERE run_id=?",claim,run);
        var validate=object("runId",run,"claimToken",claim,"projectionSha256",context.path("prior_progress_binding").path("projection_sha256"));
        assertThat(request("POST","/internal/agent/memory/validate",user(owner),validate).status()).isEqualTo(401);
        assertThat(request("POST","/internal/agent/memory/validate",service(),validate).status()).isEqualTo(200);
        // Worker owns its random claim through the normal claim_next route.
        db.update("UPDATE agent_workflow_run SET status='QUEUED',stage='QUEUED',claim_token=NULL,lease_until=NULL WHERE run_id=?",run);
        Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m1")).toAbsolutePath();Files.createDirectories(dir);
        Path cfg=Files.createTempFile("memory-m1-",".json"),output=dir.resolve("native-python-provider.json");
        Files.setPosixFilePermissions(cfg,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        String provider=System.getProperty("memory.provider","controlled");
        var settings=object("url","http://127.0.0.1:"+port,"token",service(),"viewer_token",user(owner),
            "run",run,"output",output.toString(),"provider",provider);
        if(provider.equals("live-deepseek")) ((com.fasterxml.jackson.databind.node.ObjectNode)settings)
            .put("private_credentials",Objects.requireNonNull(System.getenv("MEMORY_PRIVATE_CREDENTIALS")));
        Files.writeString(cfg,canonical(settings));
        Process worker=null;
        try {
            worker=probe(cfg,dir.resolve("python-probe.log"),false);
            assertThat(worker.waitFor(180,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(worker.exitValue()).withFailMessage(Files.readString(dir.resolve("python-probe.log"))).isZero();
            var observed=JSON.readTree(Files.readString(output));
            assertThat(observed.path("calls")).isNotEmpty();
            assertThat(observed.path("calls").get(0).path("wire").path("messages").get(1).path("content").asText()).contains("prior_progress","延迟未测量","contested");
            assertThat(observed.path("status").path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='MODEL' AND status='SETTLED'",Integer.class,run)).isPositive();
            if(provider.equals("controlled")) assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='TOOL' AND purpose='TOOL'",Integer.class,run)).isZero();
            assertThat(request("DELETE","/api/research/projects/"+old.project()+"/progress/runs/"+old.id(),user(owner),null).status()).isEqualTo(200);
            db.update("UPDATE agent_workflow_run SET status='PLANNING',stage='PLANNING',claim_token=?::uuid,lease_until=now()+interval '120 seconds' WHERE run_id=?",claim,run);
            var revoked=request("POST","/internal/agent/memory/validate",service(),validate);
            assertThat(revoked.status()).isEqualTo(409);assertThat(revoked.body().path("errorCode").asText()).isEqualTo("RESEARCH_MEMORY_REVOKED");
            db.update("UPDATE agent_workflow_run SET status='INSUFFICIENT_EVIDENCE',stage='INSUFFICIENT_EVIDENCE' WHERE run_id=?",run);
            examples.add(object("method","POST","path","/api/research/projects/"+old.project()+"/resume-context","status",loaded.status(),"response",loaded.body()));
            examples.add(object("method","POST","path","/internal/agent/memory/validate","status",revoked.status(),"response",revoked.body()));
            Files.writeString(dir.resolve("http-examples.json"),canonical(JSON.valueToTree(examples)));
            // Optional fresh, explicitly synthetic UI fixture; never reuse another DB/service.
            long keep=Long.getLong("memory.demo.keepAliveSeconds",0L);
            if(keep>0) {
                Run demo=history(owner);
                Files.writeString(dir.resolve("demo-private.json"),canonical(object("base_url","http://127.0.0.1:"+port,
                    "viewer_token",users.issue("tenant-http",owner,List.of("USER"),keep+120).authorizationHeader(),"project_id",demo.project(),"source_run_id",demo.id(),
                    "provider","controlled","expires_seconds",keep)));
                Files.setPosixFilePermissions(dir.resolve("demo-private.json"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                ((com.fasterxml.jackson.databind.node.ObjectNode)settings).put("provider","controlled").put("output",dir.resolve("demo-provider-captures.json").toString());
                Files.writeString(cfg,canonical(settings));worker=probe(cfg,dir.resolve("demo-worker.log"),true);
                Files.writeString(dir.resolve("demo-ready.json"),canonical(object("base_url","http://127.0.0.1:"+port,
                    "project_id",demo.project(),"provider","controlled","fixture_only",true)));
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(keep);
                while(System.nanoTime()<until && !Files.exists(dir.resolve("demo-stop"))) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode)settings).put("token",service());
                    Path updated=Files.createTempFile(cfg.getParent(),"memory-token-",".json");
                    Files.setPosixFilePermissions(updated,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                    Files.writeString(updated,canonical(settings));
                    Files.move(updated,cfg,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Thread.sleep(20000);
                }
            }
        } finally { if(worker!=null && worker.isAlive()) worker.destroy();Files.deleteIfExists(cfg); }
    }
    @Test void processRestartRevalidatesDeletedMemoryBeforeSettledDecisionReplay() throws Exception {
        String owner="restart-"+UUID.randomUUID();Run old=history(owner);
        var load=request("POST","/api/research/projects/"+old.project()+"/resume-context",user(owner),object());
        String session=load.body().path("target_session_id").asText();
        var accepted=post("/api/research/agents",owner,"restart-"+UUID.randomUUID(),object("question","接着做，先推进尚未完成的部分。",
            "sessionId",session,"requestedTools",List.of("kb_search"),"researchProjectId",old.project()));
        assertThat(accepted.status()).isEqualTo(202);String run=accepted.body().path("runId").asText();
        Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m1")).toAbsolutePath();Files.createDirectories(dir);
        Path config=Files.createTempFile("memory-restart-",".json");
        Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var settings=object("url","http://127.0.0.1:"+port,"token",service(),"viewer_token",user(owner),"run",run,
            "output",dir.resolve("restart-first-process.json").toString(),"interrupt_after_decision",true);
        try {
            Files.writeString(config,canonical(settings));var first=probe(config,dir.resolve("restart-first.log"),false);
            assertThat(first.waitFor(50,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(first.exitValue()).withFailMessage(Files.readString(dir.resolve("restart-first.log"))).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='MODEL' AND status='SETTLED'",Integer.class,run)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM langgraph.checkpoints WHERE thread_id=?",Integer.class,run)).isPositive();
            assertThat(request("DELETE","/api/research/projects/"+old.project()+"/progress/runs/"+old.id(),user(owner),null).status()).isEqualTo(200);
            db.update("UPDATE agent_workflow_run SET lease_until=now()-interval '1 second' WHERE run_id=?",run);
            ((com.fasterxml.jackson.databind.node.ObjectNode)settings).put("interrupt_after_decision",false)
                .put("token",service()).put("expected_status","FAILED").put("expected_error","RESEARCH_MEMORY_REVOKED")
                .put("output",dir.resolve("restart-second-process.json").toString());
            Files.writeString(config,canonical(settings));var second=probe(config,dir.resolve("restart-second.log"),false);
            assertThat(second.waitFor(50,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.exitValue()).withFailMessage(Files.readString(dir.resolve("restart-second.log"))).isZero();
            assertThat(JSON.readTree(Files.readString(dir.resolve("restart-second-process.json"))).path("calls")).isEmpty();
            assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='MODEL'",Integer.class,run)).isEqualTo(1);
        } finally {Files.deleteIfExists(config);}
    }
    String longSession(String owner,Run old) throws Exception { return longSession(owner,old,16); }
    String longSession(String owner,Run old,int repetitions) throws Exception {
        var load=request("POST","/api/research/projects/"+old.project()+"/resume-context",user(owner),object());
        assertThat(load.status()).isEqualTo(200);String session=load.body().path("target_session_id").asText();
        String legacy="m2-legacy-"+UUID.randomUUID();
        db.update("INSERT INTO agent_run(run_id,session_id,user_id,question,answer,rounds,finished) VALUES (?,?,?,'合成研究过程','尚无实测数据',1,false)",
            legacy,session,db.queryForObject("SELECT user_id FROM agent_session WHERE session_id=?",String.class,session));
        // Synthetic original messages, persisted before creation; no checkpoint/model substitutions.
        for(int i=0;i<8;i++) {
            String content=(i==0?"原目标：比较方案 A、B；同数据集、同硬件；无实测数据不得编造数值。":
                i==1?"已记录检索机制；效果有争议，延迟未测量；失败尝试缺少测量数据。":
                i==6?"最新修正：下一次测量使用机器 C，旧数字无效。":
                i==7?"最新记录：延迟仍未测量，保留争议和待办。":"较早原始观察：尚无新测量数据。")
                +"合成过程记录，重复描述检索机制与尚未完成的测量，不构成事实证据。".repeat(repetitions);
            db.update("""
                INSERT INTO agent_message(message_id,session_id,run_id,role,content,created_at)
                VALUES (?,?,?,?,?,clock_timestamp()+?*interval '1 millisecond')
                ""","m2-message-"+UUID.randomUUID(),session,legacy,i%2==0?"user":"assistant",content,i);
        }
        return session;
    }
    @Test void sourcedSummaryEntersActualPlanningAndOwnedReadView() throws Exception {
        String owner="m2-"+UUID.randomUUID();Run old=history(owner);String session=longSession(owner,old);
        var accepted=post("/api/research/agents",owner,"m2-"+UUID.randomUUID(),object("question","接着做，先推进尚未完成的部分。",
            "sessionId",session,"requestedTools",List.of("kb_search"),"researchProjectId",old.project()));
        assertThat(accepted.status()).isEqualTo(202);String run=accepted.body().path("runId").asText();
        Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m2")).toAbsolutePath();Files.createDirectories(dir);
        Path config=Files.createTempFile("memory-m2-",".json");Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var settings=object("url","http://127.0.0.1:"+port,"token",service(),"viewer_token",user(owner),"run",run,
            "provider",System.getProperty("memory.provider","controlled"),"output",dir.resolve("native-summary-provider.json").toString());
        if(settings.path("provider").asText().equals("live-deepseek")) settings.put("private_credentials",Objects.requireNonNull(System.getenv("MEMORY_PRIVATE_CREDENTIALS")));
        try {
            Files.writeString(config,canonical(settings));var worker=probe(config,dir.resolve("native-summary.log"),false);
            assertThat(worker.waitFor(180,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(worker.exitValue()).withFailMessage(Files.readString(dir.resolve("native-summary.log"))).isZero();
            var summary=request("GET","/api/research/agents/"+run+"/context-summary",user(owner),null);
            assertThat(summary.status()).isEqualTo(200);var view=summary.body();
            Files.writeString(dir.resolve("summary-view.json"),canonical(view));
            assertThat(view.path("status").asText()).isEqualTo("READY");
            assertThat(view.path("planner_input_recorded").asBoolean()).isTrue();
            assertThat(view.path("measurement").path("method").asText()).isEqualTo("utf8-canonical-bytes/1");
            assertThat(view.path("measurement").path("after_bytes").asLong()).isLessThan(view.path("measurement").path("before_bytes").asLong()).isLessThanOrEqualTo(24000);
            assertThat(canonical(view.path("summary"))).contains("同数据集","同硬件","未测量","contested","无实测数据不得编造数值");
            var calls=JSON.readTree(Files.readString(dir.resolve("native-summary-provider.json"))).path("calls");
            boolean nextPlanning=false;
            for(var call:calls) {
                var payload=JSON.readTree(call.path("wire").path("messages").get(1).path("content").asText());
                if(payload.path("project_summary").path("summary_sha256").equals(view.path("summary").path("summary_sha256"))) {
                    assertThat(payload.path("context_encoding").asText()).isEqualTo("shared-context-values/1");
                    assertThat(view.path("summary").path("schema_version").asText()).isEqualTo("project-context-summary/2");
                    assertThat(canonical(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                        .isEqualTo(view.path("measurement").path("after_bytes").asInt()).isLessThanOrEqualTo(24000);
                    var encoded=view.path("summary");
                    assertThat(encoded.path("original_encoding").asText()).isEqualTo("source-record-dictionary/1");
                    for(var original:encoded.path("original_records")) {
                        StringBuilder restored=new StringBuilder();
                        for(var part:original.path("parts")) restored.append(encoded.path("source_dictionary").path(part.path("text_ref").asText()).asText().repeat(part.path("repeat").asInt()));
                        var source=java.util.stream.StreamSupport.stream(view.path("sources").spliterator(),false)
                            .filter(row->row.path("source_ref").equals(original.path("source_ref"))).findFirst().orElseThrow();
                        assertThat(restored.toString()).isEqualTo(source.path("value").asText());
                    }
                    assertThat(canonical(call.path("decoded_payload").path("prior_context").path("recentConversation"))).contains("最新修正","机器 C","最新记录");
                    nextPlanning=true;
                }
            }
            assertThat(nextPlanning).isTrue();
            assertThat(request("GET","/api/research/agents/"+run+"/context-summary",user("foreign-"+UUID.randomUUID()),null).status()).isEqualTo(404);
            int before=db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=?",Integer.class,run);
            assertThat(request("GET","/api/research/agents/"+run+"/context-summary",user(owner),null).body()).isEqualTo(view);
            assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=?",Integer.class,run)).isEqualTo(before);
            Files.writeString(dir.resolve("summary-view.json"),canonical(view));
            long keep=Long.getLong("memory.demo.keepAliveSeconds",0L);
            if(keep>0) {
                Files.writeString(dir.resolve("demo-private.json"),canonical(object("base_url","http://127.0.0.1:"+port,
                    "viewer_token",users.issue("tenant-http",owner,List.of("USER"),keep+120).authorizationHeader(),
                    "project_id",old.project(),"session_id",session,"run_id",run,"provider","controlled","fixture_only",true)));
                Files.setPosixFilePermissions(dir.resolve("demo-private.json"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                Files.writeString(dir.resolve("demo-ready.json"),canonical(object("base_url","http://127.0.0.1:"+port,"project_id",old.project(),"run_id",run,"provider","controlled","fixture_only",true)));
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(keep);
                while(System.nanoTime()<until && !Files.exists(dir.resolve("demo-stop"))) Thread.sleep(20000);
            }
        } finally {Files.deleteIfExists(config);}
    }
    @Test void summarySurvivesRealProcessRestartWithoutRepeatedSummaryCall() throws Exception {
        String owner="m2-restart-"+UUID.randomUUID();Run old=history(owner);String session=longSession(owner,old);
        var accepted=post("/api/research/agents",owner,"m2-restart-"+UUID.randomUUID(),object("question","接着做，先推进尚未完成的部分。",
            "sessionId",session,"requestedTools",List.of("kb_search"),"researchProjectId",old.project()));
        assertThat(accepted.status()).isEqualTo(202);String run=accepted.body().path("runId").asText();
        Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m2")).toAbsolutePath();Files.createDirectories(dir);
        Path config=Files.createTempFile("m2-restart-",".json");Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var settings=object("url","http://127.0.0.1:"+port,"token",service(),"viewer_token",user(owner),"run",run,
            "output",dir.resolve("summary-restart-first.json").toString(),"interrupt_after_decision",true);
        try {
            Files.writeString(config,canonical(settings));var first=probe(config,dir.resolve("summary-restart-first.log"),false);
            assertThat(first.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(first.exitValue()).withFailMessage(Files.readString(dir.resolve("summary-restart-first.log"))).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND purpose='SUMMARY'",Integer.class,run)).isZero();
            db.update("UPDATE agent_workflow_run SET lease_until=now()-interval '1 second' WHERE run_id=?",run);
            settings.put("interrupt_after_decision",false).put("token",service()).put("output",dir.resolve("summary-restart-second.json").toString());
            Files.writeString(config,canonical(settings));var second=probe(config,dir.resolve("summary-restart-second.log"),false);
            assertThat(second.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.exitValue()).withFailMessage(Files.readString(dir.resolve("summary-restart-second.log"))).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND purpose='SUMMARY'",Integer.class,run)).isZero();
            var view=request("GET","/api/research/agents/"+run+"/context-summary",user(owner),null);
            assertThat(view.body().path("planner_input_recorded").asBoolean()).isTrue();
            for(var call:JSON.readTree(Files.readString(dir.resolve("summary-restart-second.json"))).path("calls"))
                assertThat(JSON.readTree(call.path("wire").path("messages").get(1).path("content").asText()).has("source_segments")).isFalse();
        } finally {Files.deleteIfExists(config);}
    }
    @Test void oversizedLosslessHistoryStopsBeforeProviderWithoutClaimingReady() throws Exception {
        String owner="shared-overflow-"+UUID.randomUUID(); Run old=history(owner);
        // Retain the original 80-repeat stress case: lossless sharing cannot
        // promise an arbitrary compression ratio under the same 24 KB budget.
        String session=longSession(owner,old,80);
        var accepted=post("/api/research/agents",owner,"overflow-"+UUID.randomUUID(),object(
            "question","接着做，先推进尚未完成的部分。","sessionId",session,
            "requestedTools",List.of("kb_search"),"researchProjectId",old.project()));
        assertThat(accepted.status()).isEqualTo(202);
        String run=accepted.body().path("runId").asText();
        Path dir=Path.of("target/memory-shared-overflow").toAbsolutePath();Files.createDirectories(dir);
        Path config=Files.createTempFile("shared-overflow-",".json");
        Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try {
            Files.writeString(config,canonical(object("url","http://127.0.0.1:"+port,
                "token",service(),"viewer_token",user(owner),"run",run,
                "output",dir.resolve("result.json").toString(),"provider","controlled")));
            var worker=probe(config,dir.resolve("worker.log"),false);
            assertThat(worker.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(worker.exitValue()).withFailMessage(Files.readString(dir.resolve("worker.log"))).isZero();
            var view=request("GET","/api/research/agents/"+run+"/context-summary",user(owner),null).body();
            assertThat(view.path("status").asText()).isEqualTo("FAILED");
            assertThat(view.path("error_code").asText()).isEqualTo("PROJECT_SUMMARY_BUDGET");
            assertThat(view.path("within_budget").asBoolean()).isFalse();
            assertThat(view.path("planner_input_recorded").asBoolean()).isFalse();
            assertThat(view.path("measurement").path("after_bytes").asInt()).isGreaterThan(24000);
            assertThat(JSON.readTree(Files.readString(dir.resolve("result.json"))).path("calls")).isEmpty();
            assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND purpose IN ('SUMMARY','DECISION')",Integer.class,run)).isZero();
        } finally {Files.deleteIfExists(config);}
    }
    @Test void expiredSelectedMemoryRunRetainsTimeoutTerminalStatus() throws Exception {
        String owner="timeout-"+UUID.randomUUID();Run old=history(owner);
        var load=request("POST","/api/research/projects/"+old.project()+"/resume-context",user(owner),object());
        var accepted=post("/api/research/agents",owner,"timeout-"+UUID.randomUUID(),object("question","Continue",
            "sessionId",load.body().path("target_session_id"),"requestedTools",List.of("kb_search"),"researchProjectId",old.project()));
        assertThat(accepted.status()).isEqualTo(202);String run=accepted.body().path("runId").asText();
        db.update("UPDATE agent_workflow_run SET deadline_at=now()-interval '1 second' WHERE run_id=?",run);
        Path dir=Path.of(System.getProperty("memory.resultsDir","target/memory-m1")).toAbsolutePath();Files.createDirectories(dir);
        Path config=Files.createTempFile("memory-timeout-",".json");
        Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try {
            Files.writeString(config,canonical(object("url","http://127.0.0.1:"+port,"token",service(),"viewer_token",user(owner),"run",run,
                "output",dir.resolve("expired-run.json").toString(),"expected_status","TIMED_OUT","expected_error","TIMED_OUT")));
            var process=probe(config,dir.resolve("expired-run.log"),false);
            assertThat(process.waitFor(50,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(dir.resolve("expired-run.log"))).isZero();
            assertThat(JSON.readTree(Files.readString(dir.resolve("expired-run.json"))).path("calls")).isEmpty();
            assertThat(db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=?",Integer.class,run)).isZero();
        } finally {Files.deleteIfExists(config);}
    }
    Process probe(Path config,Path log,boolean serve) throws Exception {
        var command=new ArrayList<String>(List.of(System.getenv().getOrDefault("AGENT_PYTHON","python3"),"-B","tests/fixtures/progress_memory_http_probe.py"));
        if(serve) command.add("--serve");
        var builder=new ProcessBuilder(command).directory(Path.of("workflow-service").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("PYTHONPATH",Path.of("workflow-service/src").toAbsolutePath().toString());
        builder.environment().put("MEMORY_TEST_CONFIG",config.toString());
        builder.environment().put("TEST_AGENT_DATABASE_URL","postgresql://deepresearch_workflow:workflow-integration-test-password@"+PG.getHost()+":"+PG.getMappedPort(5432)+"/"+PG.getDatabaseName());
        return builder.start();
    }
}

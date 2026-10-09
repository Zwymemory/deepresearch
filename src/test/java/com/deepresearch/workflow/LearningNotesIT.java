package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

/** Native HTTP/JWT/Flyway/storage with explicitly synthetic 20-turn answer fixtures. */
class LearningNotesIT extends AgentHttpPostgresIT {
    @Autowired LearningNotesService learning;
    Reply ask(String owner,String session,String project,String question,String key) throws Exception {
        var body=object("question",question,"sessionId",session,"requestedTools",List.of("web_search"),"memoryRecall",false);
        if(project!=null)body.put("researchProjectId",project);
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/research/agents"))
            .header("Authorization",user(owner)).header("Content-Type","application/json").header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofString(canonical(body))).build();
        var result=http.send(req,HttpResponse.BodyHandlers.ofString());
        return new Reply(result.statusCode(),JSON.readTree(result.body()));
    }
    JsonNode context(String run) throws Exception {return JSON.readTree(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",String.class,run));}
    @Test void twentyTurnsRecoverEarlyExerciseAcrossTopicSwitchAndNewSessionWithCorrectionAndRevocation() throws Exception {
        String owner="learn-"+UUID.randomUUID(),session="learning-session-"+UUID.randomUUID();
        String first=null,project=null,last=null;
        String exercise="合成练习题：OrderService 需要 PaymentGateway。第1问：用构造器注入并存入 final 字段。第2问：为什么不用自己 new？";
        for(int i=0;i<20;i++) {
            boolean other=i==5||i>=18;
            String question=other?i==5?"先换个话题：请根据 MDN 官方网页，用一小段中文解释 HTTP 响应头 ETag 的用途。只回答这个问题，不出练习。":"HTTP 缓存 ETag 第"+i+"轮":i==0?"Spring Boot 构造器注入，给一道练习":"继续构造器注入：第"+i+"轮的条件";
            var accepted=ask(owner,session,null,question,"learning-"+UUID.randomUUID());
            assertThat(accepted.status()).withFailMessage(accepted.body().toString()).isEqualTo(202);
            last=accepted.body().path("runId").asText();if(first==null) first=last;
            if(i==5) assertThat(context(last).has("learning_memory_binding")).isFalse();
            project=db.queryForObject("SELECT project_id FROM agent_research_run WHERE run_id=?",String.class,last);
            String answer=i==0?exercise:other?"合成资料：HTTP 缓存使用 ETag 验证资源。":"合成资料：构造器接收必需的依赖。第"+i+"轮只讨论示例条件，不代表用户掌握。";
            db.update("UPDATE agent_workflow_run SET status='SUCCEEDED',stage='SUCCEEDED',final_response=?::jsonb,updated_at=clock_timestamp() WHERE run_id=?",
                    canonical(object("answer",answer)),last);
            learning.capture(last);
        }
        var view=request("GET","/api/research/projects/"+project+"/learning-notes",user(owner),null);
        assertThat(view.status()).isEqualTo(200);assertThat(view.body().path("items")).hasSize(2);
        assertThat(view.body().path("items").get(0).path("entries").get(0).path("created_at").asText()).endsWith("Z");
        JsonNode spring=null;for(var topic:view.body().path("items")) if(topic.path("title").asText().contains("构造器"))spring=topic;
        assertThat(spring).isNotNull();assertThat(spring.path("entry_count").asInt()).isEqualTo(17);
        assertThat(spring.path("digest").path("schema_version").asText()).isEqualTo("learning-note-digest/1");
        assertThat(spring.path("digest").path("stats").path("merged_count").asInt()).isGreaterThan(10);
        // Grouped points still lead to the unchanged, owned originals through real HTTP.
        for(var point:spring.path("digest").path("points")) {
            var ref=point.path("representative");
            var original=request("GET","/api/research/projects/"+project+"/learning-notes/sources/"+ref.path("run_id").asText(),user(owner),null);
            assertThat(original.body().path("answer").asText().substring(ref.path("start").asInt(),ref.path("end").asInt())).isEqualTo(point.path("text").asText());
        }
        assertThat(request("GET","/api/research/projects/"+project+"/learning-notes/sources/"+first,user(owner),null).body().path("answer").asText()).isEqualTo(exercise);
        assertThat(request("GET","/api/research/projects/"+project+"/learning-notes",user("foreign"),null).status()).isEqualTo(404);
        String topic=spring.path("topic_id").asText(),note="为什么无需自己 new 我还没理解；请保留 PaymentGateway 原题。";
        assertThat(request("PATCH","/api/research/projects/"+project+"/learning-notes/"+topic,user(owner),object("note",note)).status()).isEqualTo(200);
        // Save ordinary progress through the real API; synthetic reports have no completion proof.
        assertThat(request("PUT","/api/research/projects/"+project+"/progress/runs/"+last,user(owner),object()).status()).isEqualTo(200);
        var resumed=request("POST","/api/research/projects/"+project+"/resume-context",user(owner),object());
        String nextSession=resumed.body().path("target_session_id").asText(),key="learning-resume-"+UUID.randomUUID();
        String question="回到构造器注入那道练习，第二问为什么不用自己 new？";
        var created=ask(owner,nextSession,project,question,key);
        assertThat(created.status()).withFailMessage(created.body().toString()).isEqualTo(202);
        String run=created.body().path("runId").asText();var frozen=context(run);
        var conversation=frozen.path("conversation_context");
        assertThat(conversation.path("schema_version").asText()).isEqualTo("conversation-referents/2");
        assertThat(conversation.path("learning_notes").path("correction").asText()).isEqualTo(note);
        assertThat(conversation.path("learning_notes").path("summary_method").asText()).isEqualTo(LearningNoteDigest.METHOD);
        var excerpts=new ArrayList<String>();
        conversation.path("learning_notes").path("entries").forEach(e->e.path("discussed").forEach(s->excerpts.add(s.path("text").asText())));
        assertThat(new HashSet<>(excerpts)).hasSize(excerpts.size());
        assertThat(conversation.path("reports").get(0).path("answer").asText()).isEqualTo(exercise);
        assertThat(conversation.toString()).doesNotContain("ETag");
        assertThat(LearningNotesService.bytes(conversation)).isLessThanOrEqualTo(32768);
        Path dir=Path.of("target/memory-m5");Files.createDirectories(dir);
        Files.writeString(dir.resolve("twenty-turn-context.json"),canonical(object("fixture_only",true,"turns",20,"topics",view.body(),"context",frozen)));
        String claim=UUID.randomUUID().toString();
        db.update("UPDATE agent_workflow_run SET status='PLANNING',stage='PLANNING',claim_token=?::uuid,lease_until=now()+interval '120 seconds' WHERE run_id=?",claim,run);
        var validation=object("runId",run,"claimToken",claim,"projectionSha256",frozen.path("learning_memory_binding").path("projection_sha256"));
        assertThat(request("POST","/internal/agent/memory/learning/validate",user(owner),validation).status()).isEqualTo(401);
        var valid=request("POST","/internal/agent/memory/learning/validate",service(),validation);
        assertThat(valid.status()).withFailMessage(valid.body().toString()).isEqualTo(200);
        // A correction invalidates active frozen notes; replay never silently adopts new content.
        request("PATCH","/api/research/projects/"+project+"/learning-notes/"+topic,user(owner),object("note","改为先解释 Bean 注册。"));
        assertThat(request("POST","/internal/agent/memory/learning/validate",service(),validation).body().path("errorCode").asText()).isEqualTo("RESEARCH_MEMORY_REVOKED");
        assertThat(ask(owner,nextSession,project,question,key).body().path("runId").asText()).isEqualTo(run);
        assertThat(context(run)).isEqualTo(frozen);
        assertThat(request("DELETE","/api/research/projects/"+project+"/learning-notes/"+topic,user(owner),null).status()).isEqualTo(200);
        learning.capture(first);
        assertThat(request("GET","/api/research/projects/"+project+"/learning-notes",user(owner),null).body().path("items")).hasSize(1);
        assertThat(request("GET","/api/research/projects/"+project+"/learning-notes/sources/"+first,user(owner),null).status()).isEqualTo(404);
    }
}

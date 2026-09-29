package com.deepresearch.workflow;

import com.deepresearch.security.JwtTokenService;
import com.deepresearch.service.RagflowClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Real HTTP, JWT filters/services, Flyway and PG. Only retrieval/provider transport is substituted. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "deepresearch.workflow.enabled=true", "deepresearch.agent.evidence.enabled=true",
    "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
    "spring.ai.openai.api-key=isolated-http-model-key", "spring.ai.zhipuai.api-key=isolated-http-embedding-key",
    "deepresearch.elasticsearch.url=http://127.0.0.1:1", "deepresearch.rerank.enabled=false",
    "server.shutdown=immediate", "spring.lifecycle.timeout-per-shutdown-phase=1s",
    "spring.config.import="
})
@ActiveProfiles("integration-test")
@Testcontainers
class AgentHttpPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
        .withDatabaseName("deepresearch").withUsername("deepresearch").withPassword("deepresearch")
        .withInitScript("init-workflow-role.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",PG::getJdbcUrl);
        properties.add("spring.datasource.username",PG::getUsername);
        properties.add("spring.datasource.password",PG::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired JwtTokenService users;
    @Autowired WorkflowTokenService services;
    @Autowired AgentEvidenceAuthority authority;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean RagflowClient ragflow;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String TEXT="Document version: 2.0\nVersion 2.0 allows 100 requests.\n";
    record Run(String id,String project,String claim,String user) {}
    record Reply(int status,JsonNode body) {}

    @BeforeEach void sourceTransport() {
        when(ragflow.datasets()).thenReturn(List.of("dataset-http"));
        when(ragflow.chunk("dataset-http","document-http","chunk-old"))
            .thenReturn(object("id","chunk-old","doc_id","document-http","content",TEXT));
        String document="http-managed-document";
        db.update("""
            INSERT INTO kb_document(doc_id,title,source_type,filename,raw_content,content_hash,version,status)
            VALUES (?,'managed','text','managed.md','text','h',1,'DONE') ON CONFLICT (doc_id) DO NOTHING
            """,document);
        db.update("""
            INSERT INTO kb_ragflow_document(legacy_doc_id,dataset_id,document_id,version,content_hash,sync_status)
            VALUES (?,'dataset-http','document-http',1,'h','DONE') ON CONFLICT DO NOTHING
            """,document);
    }
    String service() { return "Bearer "+services.issueServiceToken("workflow-sidecar",60).token(); }
    String user(String name) { return users.issue("tenant-http",name,List.of("USER"),60L).authorizationHeader(); }
    Reply request(String method,String path,String token,JsonNode body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if (token!=null) builder.header("Authorization",token);
        if (body!=null) builder.header("Content-Type","application/json");
        if (path.equals("/api/research/agents")) builder.header("Idempotency-Key","http-"+UUID.randomUUID());
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(canonical(body)));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),response.body().isBlank()?object():JSON.readTree(response.body()));
    }
    Run create(String owner) throws Exception {
        var reply=request("POST","/api/research/agents",user(owner),object("question","Investigate API limits","requestedTools",List.of("kb_search")));
        assertThat(reply.status()).withFailMessage(reply.body().toString()).isEqualTo(202);
        String run=reply.body().path("runId").asText(),claim=UUID.randomUUID().toString();
        db.update("UPDATE agent_workflow_run SET status='WORKING',stage='WORKING',claim_token=?::uuid,lease_until=now()+interval '120 seconds' WHERE run_id=?",claim,run);
        db.update("""
            INSERT INTO agent_research_task(run_id,task_id,objective,status,acceptance_criteria,plan_version,task_json,claim_token)
            VALUES (?,'task-main','Investigate API limits','running',ARRAY['Scoped source decision'],1,'{}',?::uuid)
            """,run,claim);
        return new Run(run,db.queryForObject("SELECT project_id FROM agent_research_run WHERE run_id=?",String.class,run),claim,owner);
    }
    JsonNode identifiers(Run run,String call) { return object("project_id",run.project(),"run_id",run.id(),"task_id","task-main","call_id",call,"claim_token",run.claim()); }
    String operation(Run run,int step,String purpose) {
        String key="tool-"+sha(run.id()+":"+step).substring(0,32);
        db.update("""
            INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token)
            VALUES (?,?,1,'TOOL',?,?,'RESERVED',0,0,?::uuid)
            """,run.id(),key,purpose,sha(key),run.claim());
        return key;
    }
    void search(Run run,String parent,String chunk) {
        JsonNode body=object("success",true,"tool","kb_search","evidence",List.of(object("evidenceId","source-managed","uriOrChunkKey","ragflow:dataset-http:document-http:"+chunk,"title","Original source")));
        db.update("""
            INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token)
            VALUES (?,?,1,'TOOL','TOOL',?,'RESERVED',0,0,?::uuid)
            """,run.id(),parent,sha(parent),run.claim());
        db.update("UPDATE agent_research_operation SET status='SETTLED',safe_result='{}',actual_usage='{}',settled_at=now() WHERE run_id=? AND operation_key=?",run.id(),parent);
        db.update("""
            INSERT INTO agent_workflow_tool_receipt(run_id,call_id,task_id,tool_name,request_fingerprint,status,safe_result,completed_at,claim_token,
                mcp_execution_status,mcp_safe_result,mcp_claim_token,mcp_started_at,mcp_completed_at)
            VALUES (?,?,'task-main','kb_search',?,'COMPLETED','{}',now(),?::uuid,'COMPLETED',?::jsonb,?::uuid,now(),now())
            """,run.id(),parent,sha(parent),run.claim(),canonical(body),run.claim());
    }
    void supportedCheck(Run run) throws Exception {
        search(run,"search-older","chunk-old");
        String read=operation(run,1,"TOOL");
        JsonNode payload=object("identifiers",identifiers(run,read),"source_id","source-managed");
        assertThat(request("POST","/internal/agent/evidence/read",user(run.user()),payload).status()).isEqualTo(401);
        assertThat(request("POST","/internal/agent/evidence/read","Bearer invalid-jwt",payload).status()).isEqualTo(401);
        var source=request("POST","/internal/agent/evidence/read",service(),payload);
        assertThat(source.status()).withFailMessage(source.body().toString()).isEqualTo(200);
        String check=operation(run,2,"TOOL");
        JsonNode scope=object("subject","API limits","version",known("2.0"),"valid_at",unknown("Date not established"),"conditions",List.of());
        var prepared=request("POST","/internal/agent/evidence/checks/prepare",service(),object("identifiers",identifiers(run,check),"claims",List.of(object("text","Version 2.0 allows 100 requests.","kind","factual","applicability",scope)),"evidence_ids",List.of(source.body().path("evidence_id").asText()),"dispute_round",0));
        assertThat(prepared.status()).withFailMessage(prepared.body().toString()).isEqualTo(200);
        String claim=prepared.body().path("request").path("claims").get(0).path("claim_id").asText();
        var response=object("claims",List.of(object("claim_id",claim,"relations",List.of(object("evidence_id",source.body().path("evidence_id").asText(),"relation","supports","quote",object("start",0,"end",TEXT.codePointCount(0,TEXT.length()),"text",TEXT,"sha256",sha(TEXT)),"reason","Complete synthetic source paragraph")),"limitations",List.of())),"follow_up_actions",List.of());
        var bound=object("value",response,"request_binding",object("check_id",prepared.body().path("check_id"),"request_sha256",prepared.body().path("request_sha256"),"response_sha256",sha(canonical(response))));
        db.update("""
            INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token)
            VALUES (?,'model:synthetic-check',1,'MODEL','CHECK',?,'RESERVED',100,100,?::uuid)
            """,run.id(),sha("synthetic-model"),run.claim());
        db.update("UPDATE agent_research_operation SET status='SETTLED',safe_result=?::jsonb,actual_usage='{}',settled_at=now() WHERE run_id=? AND operation_key='model:synthetic-check'",canonical(bound),run.id());
        String standard="Scoped source decision",criterion=AgentCompletionService.criterionId(run.id(),"task-main",0,standard);
        JsonNode expected=AgentCompletionService.normalize(prepared.body().path("request").path("claims").get(0));
        db.update("INSERT INTO agent_research_investigation_progress(run_id,investigation,current_call_id,claim_token) VALUES (?,?,?,?::uuid)",run.id(),prepared.body().path("investigation_id").asText(),check,run.claim());
        db.update("INSERT INTO agent_research_criterion(run_id,task_id,criterion_id,criterion_index,criterion_text,expected_claim,expected_hash,investigation,last_call_id,dependency_snapshot,claim_token) VALUES (?,'task-main',?,0,?,?::jsonb,?,?,?,'{}',?::uuid)",run.id(),criterion,standard,canonical(expected),sha(canonical(expected)),prepared.body().path("investigation_id").asText(),check,run.claim());
        var completed=request("POST","/internal/agent/evidence/checks/complete",service(),object("identifiers",identifiers(run,check),"check_id",prepared.body().path("check_id"),"model_call_id","model:synthetic-check","response",response));
        assertThat(completed.status()).withFailMessage(completed.body().toString()).isEqualTo(200);
        db.update("UPDATE agent_research_operation SET status='SETTLED',safe_result=?::jsonb,actual_usage='{}',settled_at=now() WHERE run_id=? AND operation_key=?",canonical(object("records",completed.body().path("records"),"check_id",prepared.body().path("check_id"),"gaps",List.of())),run.id(),check);
        db.update("UPDATE agent_research_task SET status='done' WHERE run_id=?",run.id());
    }
    Reply publish(Run run,int step) throws Exception {
        return request("POST","/internal/agent/publication",service(),object("identifiers",identifiers(run,operation(run,step,"PUBLICATION"))));
    }
    JsonNode finalizeBody(Run run,JsonNode report,String status) {
        return object("claimToken",run.claim(),"status",status,"answer",report.path("answer"),"citations",report.path("citations"));
    }

    @Test void partialReportPreservesReliableEvidenceAndBindsExactTerminalStatusOverHttp() throws Exception {
        Run run=create("partial-owner");
        assertThat(request("GET","/api/research/workflows/"+run.id(),user("another-owner"),null).status()).isEqualTo(404);
        supportedCheck(run);
        // A later same source ID hit points elsewhere. Publication must revalidate the old receipt.
        search(run,"search-later","chunk-new");
        db.update("INSERT INTO agent_research_task(run_id,task_id,objective,status,acceptance_criteria,plan_version,task_json,claim_token) SELECT run_id,'task-unfinished','Independent goal','pending',ARRAY['Independent scoped decision'],2,'{}',claim_token FROM agent_research_task WHERE run_id=? AND task_id='task-main'",run.id());
        var publication=publish(run,3);
        assertThat(publication.status()).withFailMessage(publication.body().toString()).isEqualTo(200);
        var report=publication.body();
        assertThat(report.path("report_status").asText()).isEqualTo("partial");
        assertThat(report.path("citations").get(0).asText()).isEqualTo("kb:ragflow:dataset-http:document-http:chunk-old");
        assertThat(report.path("unfinished_goals")).isNotEmpty();
        assertThat(report.path("answer").asText()).contains("Independent scoped decision");
        String path="/internal/research/workflows/"+run.id()+"/finalize";
        assertThat(request("POST",path,service(),finalizeBody(run,report,"SUCCEEDED")).status()).isEqualTo(400);
        var altered=finalizeBody(run,report,"INSUFFICIENT_EVIDENCE");
        ((com.fasterxml.jackson.databind.node.ObjectNode)altered).put("answer",report.path("answer").asText()+" Unchecked assertion");
        assertThat(request("POST",path,service(),altered).status()).isEqualTo(400);
        db.update("UPDATE agent_workflow_run SET status='FINALIZING',stage='FINALIZING' WHERE run_id=?",run.id());
        assertThat(request("POST",path,service(),finalizeBody(run,report,"INSUFFICIENT_EVIDENCE")).status()).isEqualTo(200);
        var view=request("GET","/api/research/workflows/"+run.id(),user(run.user()),null);
        assertThat(view.body().path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(view.body().path("finalResponse").path("report_status").asText()).isEqualTo("partial");
        assertThat(view.body().path("finalResponse").path("citations")).isEqualTo(report.path("citations"));
    }
    @Test void emptyReportIsDeterministicAndCannotForgeCrossRunProjectOrStaleLease() throws Exception {
        Run run=create("gap-owner"), other=create("other-run-owner");
        String call=operation(run,4,"PUBLICATION");
        var ids=identifiers(run,call);
        var forged=ids.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)forged).put("project_id",other.project());
        assertThat(request("POST","/internal/agent/publication",service(),object("identifiers",forged)).status()).isEqualTo(409);
        var report=request("POST","/internal/agent/publication",service(),object("identifiers",ids));
        assertThat(report.status()).withFailMessage(report.body().toString()).isEqualTo(200);
        assertThat(report.body().path("report_status").asText()).isEqualTo("insufficient");
        assertThat(report.body().path("citations").isEmpty()).isTrue();
        assertThat(request("POST","/internal/agent/publication",service(),object("identifiers",ids)).body()).isEqualTo(report.body());
        db.update("UPDATE agent_workflow_run SET lease_until=now()-interval '1 second' WHERE run_id=?",run.id());
        assertThat(publish(run,5).status()).isEqualTo(403);
        assertThat(request("POST","/api/research/workflows/"+other.id()+"/cancel",user(other.user()),object()).status()).isEqualTo(200);
        assertThat(publish(other,6).status()).isEqualTo(403);
    }
    @Test void originalReceiptMustBelongToThisRunAndPublicationReadsRespectUnifiedBudget() throws Exception {
        Run run=create("budget-owner"), other=create("receipt-owner");
        supportedCheck(run);
        search(other,"cross-run-receipt","chunk-old");
        String call=operation(run,7,"PUBLICATION");
        var grant=authority.authorize(service(),"publish_evidence",new com.deepresearch.evidence.EvidenceDtos.Identifiers(run.project(),run.id(),"task-main",call,run.claim()));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->authority.originalCandidate(grant,"source-managed","cross-run-receipt"))
            .isInstanceOf(com.deepresearch.evidence.EvidenceException.class);
        db.update("UPDATE agent_workflow_run SET budget=jsonb_set(budget,'{maxToolCalls}','4') WHERE run_id=?",run.id());
        var denied=request("POST","/internal/agent/publication",service(),object("identifiers",identifiers(run,call)));
        assertThat(denied.status()).withFailMessage(denied.body().toString()).isEqualTo(429);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_research_source_validation WHERE run_id=?",Integer.class,run.id())).isZero();
    }

    private JsonNode criterionHttpScenario(String mode) throws Exception {
        Run run=create("criteria-owner-"+mode);
        String objective=mode.equals("refuted")?"Verify API document version":"Verify API document version and per-minute request rate";
        var criteria=mode.equals("refuted")?List.of("Verify the API document version"):List.of("Verify the API document version","Verify the per-minute request rate");
        db.update("UPDATE agent_research_task SET objective=?,acceptance_criteria=CAST(? AS text[]) WHERE run_id=? AND task_id='task-main'",objective,"{\""+String.join("\",\"",criteria)+"\"}",run.id());
        String original=mode.equals("complete")?"Document version: 2.0\nVersion 2.0 allows 100 requests per minute.\n":"Document version: 2.0\nThis source specifies version 2.0 only; the per-minute request rate is not stated.\n";
        when(ragflow.chunk("dataset-http","document-http","chunk-old")).thenReturn(object("id","chunk-old","doc_id","document-http","content",original));
        search(run,"search-criteria-"+mode,"chunk-old");
        db.update("UPDATE agent_workflow_run SET status='PLANNING',stage='PLANNING' WHERE run_id=?",run.id());
        var config=java.nio.file.Files.createTempFile("completion-http-",".json");
        var output=java.nio.file.Path.of("target/criterion-http-"+mode+".json").toAbsolutePath();
        var log=java.nio.file.Path.of("target/criterion-http-"+mode+".log").toAbsolutePath();
        java.nio.file.Files.writeString(config,canonical(object("mode",mode,"run",run.id(),"claim",run.claim(),"url","http://127.0.0.1:"+port,
            "token",service(),"objective",objective,"criteria",criteria,"output",output.toString(),
            "budget",JSON.readTree(db.queryForObject("SELECT budget::text FROM agent_workflow_run WHERE run_id=?",String.class,run.id())),
            "grant",db.queryForObject("SELECT grant_id FROM agent_workflow_run WHERE run_id=?",String.class,run.id()))));
        try {
            var builder=new ProcessBuilder(System.getenv().getOrDefault("AGENT_PYTHON","python3"),"-B","tests/fixtures/completion_http_probe.py")
                .directory(java.nio.file.Path.of("workflow-service").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("PYTHONPATH",java.nio.file.Path.of("workflow-service/src").toAbsolutePath().toString());
            builder.environment().put("PYTHONDONTWRITEBYTECODE","1");builder.environment().put("COMPLETION_TEST_CONFIG",config.toString());
            builder.environment().put("TEST_AGENT_DATABASE_URL","postgresql://deepresearch_workflow:workflow-integration-test-password@"+PG.getHost()+":"+PG.getMappedPort(5432)+"/"+PG.getDatabaseName());
            var process=builder.start();assertThat(process.waitFor(50,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(java.nio.file.Files.readString(log)).isZero();
            var result=JSON.readTree(java.nio.file.Files.readString(output));var report=result.path("report");
            String status=mode.equals("complete")||mode.equals("refuted")?"SUCCEEDED":"INSUFFICIENT_EVIDENCE";
            assertThat(report.path("terminal_status").asText()).isEqualTo(status);
            if(status.equals("SUCCEEDED")) assertThat(report.path("unfinished_goals")).isEmpty();
            else {assertThat(report.path("unfinished_goals")).isNotEmpty();assertThat(report.path("answer").asText()).contains("Verify the per-minute request rate");}
            String path="/internal/research/workflows/"+run.id()+"/finalize";
            assertThat(request("POST",path,service(),finalizeBody(run,report,status.equals("SUCCEEDED")?"INSUFFICIENT_EVIDENCE":"SUCCEEDED")).status()).isEqualTo(400);
            var altered=finalizeBody(run,report,status);((com.fasterxml.jackson.databind.node.ObjectNode)altered).put("answer",report.path("answer").asText()+" unchecked addition");
            assertThat(request("POST",path,service(),altered).status()).isEqualTo(400);
            db.update("UPDATE agent_workflow_run SET status='FINALIZING',stage='FINALIZING' WHERE run_id=?",run.id());
            assertThat(request("POST",path,service(),finalizeBody(run,report,status)).status()).isEqualTo(200);
            assertThat(request("POST",path,service(),finalizeBody(run,report,status)).status()).isEqualTo(200);
            assertThat(request("GET","/api/research/workflows/"+run.id(),user(run.user()),null).body().path("status").asText()).isEqualTo(status);
            return report;
        } finally {java.nio.file.Files.deleteIfExists(config);}
    }
    @Test void uncoveredStoredRateCriterionStaysPartialThroughActualPythonJwtAndSql() throws Exception {criterionHttpScenario("partial");}
    @Test void allBoundCriteriaCanCompleteThroughActualPythonJwtAndSql() throws Exception {criterionHttpScenario("complete");}
    @Test void legacyUnboundChecksDoNotCompleteNativeStandardsThroughActualHttp() throws Exception {criterionHttpScenario("legacy");}
    @Test void validRefutationCanSatisfyVerificationCriterionThroughActualHttp() throws Exception {
        var report=criterionHttpScenario("refuted");assertThat(report.path("claims").get(0).path("claim").path("decision_status").asText()).isEqualTo("refuted");
    }
}

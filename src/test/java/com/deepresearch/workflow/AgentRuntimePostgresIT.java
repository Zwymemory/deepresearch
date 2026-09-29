package com.deepresearch.workflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.deepresearch.evidence.*;
import com.deepresearch.service.AgentStateService;
import com.deepresearch.service.UserContextService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Full V1-V17 migrations and real Python ledger against a disposable database. */
@Testcontainers
class AgentRuntimePostgresIT {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("deepresearch").withUsername("deepresearch").withPassword("deepresearch")
            .withInitScript("init-workflow-role.sql");

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).load().migrate();
    }

    @Test void pythonAdmissionReplayConcurrencyAndFencesUseMigratedDatabase() throws Exception {
        String python=System.getenv().getOrDefault("AGENT_PYTHON","python3");
        var output=Files.createTempFile("agent-postgres-test-",".txt");
        var builder=new ProcessBuilder(python,"-B","-m","pytest","tests/test_agent_postgres.py","-q")
                .directory(Path.of("workflow-service").toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("PYTHONPATH",Path.of("workflow-service/src").toAbsolutePath().toString());
        builder.environment().put("TEST_AGENT_DATABASE_URL","postgresql://"+PG.getUsername()+":"+PG.getPassword()+"@"+PG.getHost()+":"+PG.getMappedPort(5432)+"/"+PG.getDatabaseName());
        builder.environment().put("AGENT_TEST_ISOLATED","1");
        var process=builder.start();
        assertThat(process.waitFor(50,TimeUnit.SECONDS)).as("isolated ledger test timeout").isTrue();
        assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
        Files.deleteIfExists(output);
    }

    record Bridge(JdbcTemplate db,TransactionTemplate tx,AgentEvidenceAuthority authority,EvidenceService service,
                  AgentPublicationController publication,EvidenceDtos.Identifiers ids,AtomicInteger reads,String text) {}

    private Bridge bridge(int toolCap) {
        var data=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        var db=new JdbcTemplate(data);var manager=new DataSourceTransactionManager(data);var tx=new TransactionTemplate(manager);
        String run="wf-it-"+UUID.randomUUID(),session="sess-"+UUID.randomUUID(),grant="grant-"+UUID.randomUUID(),claim=UUID.randomUUID().toString();
        var ids=new EvidenceDtos.Identifiers(run,run,"task-main","tool-"+"1".repeat(32),claim);
        tx.executeWithoutResult(status->{
            db.execute("SET CONSTRAINTS ALL DEFERRED");
            db.update("INSERT INTO agent_session(session_id,user_id,title) VALUES (?,'tenant:opaque:owner','isolated test')",session);
            String budget="{\"runtime\":\"agent\",\"maxModelCalls\":16,\"maxToolCalls\":"+toolCap+",\"maxInputTokens\":64000,\"maxOutputTokens\":16384,\"maxDecisionSteps\":8}";
            db.update("""
                INSERT INTO agent_workflow_run(run_id,session_id,user_id,question,endpoint,idempotency_key,request_fingerprint,graph_thread_id,
                    status,stage,deadline_at,requested_scopes,grant_id,claim_token,lease_until,budget)
                VALUES (?,?,'tenant:opaque:owner','question','/api/research/agents',?,?,?,'WORKING','WORKING',now()+interval '180 seconds',
                    ARRAY['kb_search','read_source','check_claims'],?,?::uuid,now()+interval '120 seconds',?::jsonb)
                """,run,session,run,"a".repeat(64),run,grant,claim,budget);
            db.update("INSERT INTO agent_workflow_grant(grant_id,run_id,subject,scopes,expires_at) VALUES (?,?,'tenant:opaque:owner',ARRAY['kb_search','read_source','check_claims'],now()+interval '180 seconds')",grant,run);
            db.update("INSERT INTO research_project(project_id,tenant_id,owner_id,session_id) VALUES (?,'tenant','opaque:owner',?)",run,session);
            db.update("INSERT INTO agent_research_run(run_id,project_id,tenant_id,owner_id) VALUES (?,?,'tenant','opaque:owner')",run,run);
            db.update("INSERT INTO agent_research_task(run_id,task_id,objective,status,acceptance_criteria,plan_version,task_json,claim_token) VALUES (?,'task-main','goal','running',ARRAY['quote verified'],1,'{}',?::uuid)",run,claim);
            JsonNode body=object("success",true,"tool","kb_search","evidence",List.of(object("evidenceId","ragflow:dataset:document:chunk","uriOrChunkKey","ragflow:dataset:document:chunk","title","Managed source")));
            db.update("""
                INSERT INTO agent_workflow_tool_receipt(run_id,call_id,task_id,tool_name,request_fingerprint,status,safe_result,completed_at,claim_token,
                    mcp_execution_status,mcp_safe_result,mcp_claim_token,mcp_started_at,mcp_completed_at)
                VALUES (?,'search-parent','task-main','kb_search',?,'COMPLETED','{}',now(),?::uuid,'COMPLETED',?::jsonb,?::uuid,now(),now())
                """,run,"a".repeat(64),claim,canonical(body),claim);
            db.update("INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token) VALUES (?,'search-parent',1,'TOOL','TOOL',?,'RESERVED',0,0,?::uuid)",run,"c".repeat(64),claim);
            db.update("UPDATE agent_research_operation SET status='SETTLED',safe_result='{}',actual_usage='{}',settled_at=now() WHERE run_id=? AND operation_key='search-parent'",run);
        });
        var access=mock(WorkflowAccessService.class);
        var authority=new AgentEvidenceAuthority(new AgentRunAuthorization(access,db),db,manager);
        var reads=new AtomicInteger();String text="Document version: 2.0\nVersion 2.0 allows 100 requests.\n";
        SourceReader reader=(g,c)->{ reads.incrementAndGet();return new SourceReader.Document(text,"Managed source",
                object("kind","knowledge_chunk","dataset_id","dataset","document_id","document","chunk_id","chunk"),
                "document_chunk",Instant.now(),sha(text),false,false); };
        var service=new EvidenceService(authority,new JdbcEvidenceStore(db,tx),reader);
        return new Bridge(db,tx,authority,service,new AgentPublicationController(authority,service,db,manager),ids,reads,text);
    }
    private EvidenceDtos.Identifiers operation(Bridge b,int step,String purpose) {
        var ids=new EvidenceDtos.Identifiers(b.ids.project_id(),b.ids.run_id(),b.ids.task_id(),"tool-"+String.valueOf(step).repeat(32),b.ids.claim_token());
        b.db.update("INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token) VALUES (?,?,1,'TOOL',?,?,'RESERVED',0,0,?::uuid)",
                ids.run_id(),ids.call_id(),purpose,"a".repeat(64),ids.claim_token());
        return ids;
    }
    private EvidenceDtos.PublishRequest supportedPacket(Bridge b) {
        var read=operation(b,1,"TOOL");JsonNode evidence=b.service.read("Bearer fixture-service",new EvidenceDtos.ReadRequest(read,"ragflow:dataset:document:chunk"));
        assertThat(b.db.queryForObject("SELECT status FROM agent_research_operation WHERE run_id=? AND operation_key=?",String.class,read.run_id(),read.call_id())).isEqualTo("SETTLED");
        var check=operation(b,2,"TOOL");
        JsonNode applicability=object("subject","API limits","version",known("2.0"),"valid_at",unknown("No applicable date"),"conditions",List.of());
        var prepared=b.service.prepare("Bearer fixture-service",new EvidenceDtos.PrepareRequest(check,List.of(new EvidenceDtos.ClaimSpec("Version 2.0 allows 100 requests.","factual",applicability)),List.of(evidence.path("evidence_id").asText()),0,null));
        String claim=prepared.request().path("claims").get(0).path("claim_id").asText();
        var response=object("claims",List.of(object("claim_id",claim,"relations",List.of(object("evidence_id",evidence.path("evidence_id").asText(),
                "relation","supports","quote",object("start",0,"end",b.text.codePointCount(0,b.text.length()),"text",b.text,"sha256",sha(b.text)),"reason","Exact complete paragraph")),"limitations",List.of())),"follow_up_actions",List.of());
        var model=object("value",response,"request_binding",object("check_id",prepared.check_id(),"request_sha256",prepared.request_sha256(),"response_sha256",sha(canonical(response))));
        b.db.update("INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token) VALUES (?,'model:check',1,'MODEL','CHECK',?,'RESERVED',100,100,?::uuid)",check.run_id(),"b".repeat(64),check.claim_token());
        b.db.update("UPDATE agent_research_operation SET status='SETTLED',safe_result=?::jsonb,actual_usage='{}',settled_at=now() WHERE run_id=? AND operation_key='model:check'",canonical(model),check.run_id());
        registerCriterion(b,check,prepared,0,"quote verified");
        var outcome=b.service.complete("Bearer fixture-service",new EvidenceDtos.CompleteRequest(check,prepared.check_id(),"model:check",response));
        b.authority.settle(b.authority.authorize("Bearer fixture-service","check_claims",check),object("records",outcome.records(),"check_id",prepared.check_id(),"gaps",List.of()));
        b.db.update("UPDATE agent_research_task SET status='done' WHERE run_id=? AND task_id=?",check.run_id(),check.task_id());
        var packet=b.service.packet("Bearer fixture-service",new EvidenceDtos.PacketRequest(check,List.of(prepared.check_id())));
        var publish=operation(b,3,"PUBLICATION");
        return new EvidenceDtos.PublishRequest(publish,packet.path("packet_id").asText(),List.of(claim));
    }
    private void registerCriterion(Bridge b,EvidenceDtos.Identifiers check,EvidenceDtos.PreparedCheck prepared,int index,String criterionText) {
        String identity=AgentCompletionService.criterionId(check.run_id(),check.task_id(),index,criterionText);
        JsonNode expected=AgentCompletionService.normalize(prepared.request().path("claims").get(index));
        b.db.update("INSERT INTO agent_research_investigation_progress(run_id,investigation,current_call_id,claim_token) VALUES (?,?,?,?::uuid) ON CONFLICT (run_id,investigation) DO UPDATE SET current_call_id=EXCLUDED.current_call_id,claim_token=EXCLUDED.claim_token",check.run_id(),prepared.investigation_id(),check.call_id(),check.claim_token());
        b.db.update("INSERT INTO agent_research_criterion(run_id,task_id,criterion_id,criterion_index,criterion_text,expected_claim,expected_hash,investigation,last_call_id,dependency_snapshot,claim_token) VALUES (?,?,?,?,?,?::jsonb,?,?,?,?::jsonb,?::uuid)",check.run_id(),check.task_id(),identity,index,criterionText,canonical(expected),sha(canonical(expected)),prepared.investigation_id(),check.call_id(),b.db.queryForObject("SELECT agent_task_dependency_snapshot(?,?)::text",String.class,check.run_id(),check.task_id()),check.claim_token());
    }
    @Test void actualEvidenceBridgeSealsExactAnswerAndReplaysWithoutAdditionalReads() {
        Bridge b=bridge(16);var request=supportedPacket(b);
        var answer=b.publication.publish("Bearer fixture-service",request);
        assertThat(b.reads.get()).isEqualTo(2);
        assertThat(b.publication.publish("Bearer fixture-service",request)).isEqualTo(answer);
        assertThat(b.reads.get()).isEqualTo(2);
        var repository=new WorkflowRepository(b.db,JSON);
        var citations=List.of("kb:ragflow:dataset:document:chunk");
        assertThat(repository.sealedAgentPublication(b.ids.run_id(),answer.path("answer").asText(),citations)).isTrue();
        assertThat(repository.sealedAgentPublication(b.ids.run_id(),"Arbitrary new assertion [来源1]",citations)).isFalse();
        var workflows=new WorkflowService(repository,mock(AgentStateService.class),mock(UserContextService.class),JSON,true,Duration.ofSeconds(180));
        assertThatThrownBy(()->workflows.finalizeRun(b.ids.run_id(),new WorkflowDtos.FinalizeRequest(b.ids.claim_token(),"SUCCEEDED","Arbitrary new assertion [来源1]",citations,null,null,null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        b.db.update("UPDATE agent_workflow_run SET status='FINALIZING',stage='FINALIZING' WHERE run_id=?",b.ids.run_id());
        var finalRequest=new WorkflowDtos.FinalizeRequest(b.ids.claim_token(),"SUCCEEDED",answer.path("answer").asText(),citations,null,null,null);
        assertThat(workflows.finalizeRun(b.ids.run_id(),finalRequest).replayed()).isFalse();
        assertThat(workflows.finalizeRun(b.ids.run_id(),finalRequest).replayed()).isTrue();
    }
    @Test void publicationRevalidationIsIncludedInTheSharedToolLimit() {
        Bridge b=bridge(4);var request=supportedPacket(b);
        assertThatThrownBy(()->b.publication.publish("Bearer fixture-service",request)).isInstanceOf(EvidenceException.class).hasMessageContaining("AGENT_BUDGET_EXCEEDED");
        assertThat(b.reads.get()).isEqualTo(1);
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_publication WHERE run_id=? AND status='COMPLETED'",Integer.class,b.ids.run_id())).isZero();
    }
    @Test void authorizationRejectsSpoofedProjectAndCancelledRunBeforeReading() {
        Bridge b=bridge(16);var ids=operation(b,1,"TOOL");
        var spoofed=new EvidenceDtos.Identifiers("other-project",ids.run_id(),ids.task_id(),ids.call_id(),ids.claim_token());
        assertThatThrownBy(()->b.service.read("Bearer fixture-service",new EvidenceDtos.ReadRequest(spoofed,"ragflow:dataset:document:chunk"))).isInstanceOf(EvidenceException.class);
        b.db.update("UPDATE agent_workflow_run SET cancel_requested=true WHERE run_id=?",ids.run_id());
        assertThatThrownBy(()->b.service.read("Bearer fixture-service",new EvidenceDtos.ReadRequest(ids,"ragflow:dataset:document:chunk"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(b.reads.get()).isZero();
    }
    @Test void publicationReadPermitsReplayKnownHashesAndDenyInFlightOrForgedReceipts() {
        Bridge b=bridge(16);var read=operation(b,1,"TOOL");
        JsonNode evidence=b.service.read("Bearer fixture-service",new EvidenceDtos.ReadRequest(read,"ragflow:dataset:document:chunk"));
        var pub=operation(b,2,"PUBLICATION");var grant=b.authority.authorize("Bearer fixture-service","publish_evidence",pub);
        var permit=b.authority.publicationRead(grant,evidence);
        assertThat(permit.completedSnapshotHash()).isNull();
        assertThatThrownBy(()->b.authority.publicationRead(grant,evidence)).isInstanceOf(EvidenceException.class).hasMessageContaining("PUBLICATION_READ_UNKNOWN");
        b.authority.completePublicationRead(grant,permit,sha(b.text),null);
        assertThat(b.authority.publicationRead(grant,evidence).completedSnapshotHash()).isEqualTo(sha(b.text));
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='TOOL'",Integer.class,b.ids.run_id())).isEqualTo(4);
        assertThatThrownBy(()->b.tx.executeWithoutResult(status->{
            b.db.execute("SET LOCAL ROLE deepresearch_workflow");
            b.db.update("UPDATE agent_research_operation SET safe_result='{}'::jsonb WHERE run_id=? AND operation_key=?",b.ids.run_id(),permit.operationId());
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->b.tx.executeWithoutResult(status->{
            b.db.execute("SET LOCAL ROLE deepresearch_workflow");
            b.db.queryForList("SELECT * FROM agent_research_source_validation WHERE run_id=?",b.ids.run_id());
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(b.reads.get()).isEqualTo(1);
    }

    private EvidenceDtos.RecordResult completePrepared(Bridge b,EvidenceDtos.Identifiers ids,EvidenceDtos.PreparedCheck prepared,String modelId) {
        var proposals=new java.util.ArrayList<JsonNode>();
        for(var claim:prepared.request().path("claims")) {
            var relations=new java.util.ArrayList<JsonNode>();
            for(var evidence:prepared.request().path("evidence")) relations.add(object("evidence_id",evidence.path("evidence_id"),"relation","supports","quote",evidence.path("snapshot").path("text").asText(),"reason","Complete original contains the selected fact"));
            proposals.add(object("claim_id",claim.path("claim_id"),"relations",relations,"limitations",List.of()));
        }
        var response=object("claims",proposals,"follow_up_actions",List.of());
        var bound=object("value",response,"request_binding",object("check_id",prepared.check_id(),"request_sha256",prepared.request_sha256(),"response_sha256",sha(canonical(response))));
        b.db.update("INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token) VALUES (?,?,1,'MODEL','CHECK',?,'RESERVED',100,100,?::uuid)",ids.run_id(),modelId,sha(modelId),ids.claim_token());
        b.db.update("UPDATE agent_research_operation SET status='SETTLED',safe_result=?::jsonb,actual_usage='{}',settled_at=now() WHERE run_id=? AND operation_key=?",canonical(bound),ids.run_id(),modelId);
        var result=b.service.complete("Bearer fixture-service",new EvidenceDtos.CompleteRequest(ids,prepared.check_id(),modelId,response));
        b.authority.settle(b.authority.authorize("Bearer fixture-service","check_claims",ids),object("records",result.records(),"check_id",prepared.check_id(),"gaps",List.of()));
        return result;
    }
    @Test void storedDoneAndOneRealClaimCannotCoverAnAdditionalNativeStandard() {
        Bridge b=bridge(16);var publication=supportedPacket(b);
        b.db.update("UPDATE agent_research_task SET acceptance_criteria=ARRAY['quote verified','Verify the per-minute request rate'] WHERE run_id=?",b.ids.run_id());
        var report=b.publication.publish("Bearer fixture-service",publication);
        assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.path("answer").asText()).contains("Verify the per-minute request rate");
    }
    @Test void lostLegacyMappingDoesNotDefaultToSatisfiedAndCrossRunCriterionIdentityIsRejected() {
        Bridge b=bridge(16);var publication=supportedPacket(b);
        b.db.update("DELETE FROM agent_research_criterion WHERE run_id=?",b.ids.run_id());
        b.db.update("INSERT INTO agent_research_criterion(run_id,task_id,criterion_id,criterion_index,criterion_text,claim_token) VALUES (?,'task-main',?,0,'quote verified',?::uuid)",b.ids.run_id(),AgentCompletionService.criterionId("a-different-run","task-main",0,"quote verified"),b.ids.claim_token());
        var goals=b.authority.reportGoals(b.authority.authorize("Bearer fixture-service","publish_evidence",publication.identifiers()));
        assertThat(goals.get(0).completionVerified()).isFalse();assertThat(goals.get(0).criteria().get(0).status()).isEqualTo("uncovered");
        assertThat(b.publication.publish("Bearer fixture-service",publication).path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }
    @Test void criterionIdentityInitialScopeAndCurrentAttemptCannotBeRewrittenFromOldReceipts() {
        Bridge b=bridge(16);supportedPacket(b);
        assertThatThrownBy(()->b.db.update("UPDATE agent_research_criterion SET criterion_id='forged' WHERE run_id=?",b.ids.run_id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->b.db.update("UPDATE agent_research_criterion SET expected_claim='{}',expected_hash=? WHERE run_id=?",sha("{}"),b.ids.run_id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->b.db.update("UPDATE agent_research_investigation_progress SET current_call_id='search-parent' WHERE run_id=?",b.ids.run_id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->b.tx.executeWithoutResult(status->{b.db.execute("SET LOCAL ROLE deepresearch_workflow");b.db.update("DELETE FROM agent_research_criterion WHERE run_id=?",b.ids.run_id());})).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void reservedUnrelatedOperationCannotBorrowOldCheckOrDuplicateAnotherCriterionBinding() {
        Bridge b=bridge(16);var publication=supportedPacket(b);String run=b.ids.run_id();
        b.db.update("UPDATE agent_research_task SET acceptance_criteria=ARRAY['quote verified','A second independent standard'] WHERE run_id=?",run);
        var old=b.db.queryForMap("SELECT expected_claim::text AS expected_claim,expected_hash,investigation FROM agent_research_criterion WHERE run_id=?",run);
        var attempt=operation(b,4,"TOOL");
        assertThatThrownBy(()->b.db.update("INSERT INTO agent_research_criterion(run_id,task_id,criterion_id,criterion_index,criterion_text,expected_claim,expected_hash,investigation,last_call_id,claim_token) VALUES (?,'task-main',?,1,'A second independent standard',?::jsonb,?,?,?,?::uuid)",run,AgentCompletionService.criterionId(run,"task-main",1,"A second independent standard"),old.get("expected_claim"),old.get("expected_hash"),old.get("investigation"),attempt.call_id(),attempt.claim_token())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        String original=b.db.queryForObject("SELECT safe_result::text FROM agent_research_operation WHERE run_id=? AND operation_key=?",String.class,run,"tool-"+"2".repeat(32));
        b.db.update("UPDATE agent_research_investigation_progress SET current_call_id=?,claim_token=?::uuid WHERE run_id=?",attempt.call_id(),attempt.claim_token(),run);
        assertThatThrownBy(()->b.db.update("INSERT INTO agent_research_criterion(run_id,task_id,criterion_id,criterion_index,criterion_text,expected_claim,expected_hash,investigation,last_call_id,claim_token) VALUES (?,'task-main',?,1,'A second independent standard',?::jsonb,?,?,?,?::uuid)",run,AgentCompletionService.criterionId(run,"task-main",1,"A second independent standard"),old.get("expected_claim"),old.get("expected_hash"),old.get("investigation"),attempt.call_id(),attempt.claim_token())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // Even a settled wrapper with byte-identical old records cannot attest a new check.
        b.authority.settle(b.authority.authorize("Bearer fixture-service","check_claims",attempt),parseJson(original));
        var goals=b.authority.reportGoals(b.authority.authorize("Bearer fixture-service","publish_evidence",publication.identifiers()));
        assertThat(goals.get(0).completionVerified()).isFalse();
        assertThat(goals.get(0).criteria().get(0).status()).isEqualTo("blocked");
        assertThat(goals.get(0).criteria().get(1).status()).isEqualTo("uncovered");
    }
    private JsonNode parseJson(String text) {
        try{return JSON.readTree(text);}catch(Exception malformed){throw new AssertionError(malformed);}
    }

    record MultiKnowledge(AgentPublicationController publication,EvidenceDtos.ReportRequest request) {}
    private MultiKnowledge multipleKnowledgeInvestigations(Bridge b,int count) {
        var searchEvidence=new java.util.ArrayList<JsonNode>();
        for(int i=1;i<=count;i++) searchEvidence.add(object("evidenceId","ragflow:dataset:document:chunk-"+i,
            "uriOrChunkKey","ragflow:dataset:document:chunk-"+i,"title","Managed source "+i));
        b.db.update("UPDATE agent_workflow_tool_receipt SET mcp_safe_result=?::jsonb WHERE run_id=? AND call_id='search-parent'",canonical(object("success",true,"tool","kb_search","evidence",searchEvidence)),b.ids.run_id());
        b.db.update("INSERT INTO agent_research_task(run_id,task_id,objective,status,acceptance_criteria,plan_version,task_json,claim_token) SELECT run_id,'task-second','Independent rate goal','running',ARRAY['Scoped rate decision'],1,'{}',claim_token FROM agent_research_task WHERE run_id=? AND task_id='task-main'",b.ids.run_id());
        SourceReader reader=(g,c)->{b.reads.incrementAndGet();String text=b.text+"Source chunk "+c.chunkId()+".\n";
            return new SourceReader.Document(text,c.title(),object("kind","knowledge_chunk","dataset_id",c.datasetId(),"document_id",c.documentId(),"chunk_id",c.chunkId()),"document_chunk",Instant.now(),sha(text),false,false);};
        var manager=new DataSourceTransactionManager(b.db.getDataSource());
        var service=new EvidenceService(b.authority,new JdbcEvidenceStore(b.db,new TransactionTemplate(manager)),reader);
        var combined=new Bridge(b.db,b.tx,b.authority,service,new AgentPublicationController(b.authority,service,b.db,manager),b.ids,b.reads,b.text);
        var originals=new java.util.ArrayList<JsonNode>();
        for(int i=1;i<=count;i++) originals.add(service.read("Bearer fixture-service",new EvidenceDtos.ReadRequest(operation(b,i,"TOOL"),"ragflow:dataset:document:chunk-"+i)));
        for(int group=0;group<2;group++) {
            var raw=operation(b,count+group+1,"TOOL");
            var check=new EvidenceDtos.Identifiers(raw.project_id(),raw.run_id(),group==0?"task-main":"task-second",raw.call_id(),raw.claim_token());
            var selected=group==0?originals.subList(0,2):originals.subList(2,count);
            var scope=object("subject","API limits","version",known("2.0"),"valid_at",unknown("No applicable date"),"conditions",List.of());
            var prepared=service.prepare("Bearer fixture-service",new EvidenceDtos.PrepareRequest(check,List.of(new EvidenceDtos.ClaimSpec(group==0?"Document version is 2.0.":"Version 2.0 allows 100 requests.","factual",scope)),selected.stream().map(e->e.path("evidence_id").asText()).toList(),0,null));
            registerCriterion(combined,check,prepared,0,group==0?"quote verified":"Scoped rate decision");
            completePrepared(combined,check,prepared,"model:multi-"+group);
        }
        b.db.update("UPDATE agent_research_task SET status='done' WHERE run_id=?",b.ids.run_id());
        return new MultiKnowledge(combined.publication,new EvidenceDtos.ReportRequest(operation(b,count+3,"PUBLICATION")));
    }
    private void assertWholeKnowledgeReport(int sources) {
        Bridge b=bridge(16);var multi=multipleKnowledgeInvestigations(b,sources);
        var report=multi.publication.publish("Bearer fixture-service",multi.request);
        assertThat(report.path("report_status").asText()).isEqualTo("complete");
        assertThat(report.path("claims")).hasSize(2);assertThat(report.path("citations")).hasSize(sources);
        assertThat(b.reads.get()).isEqualTo(sources*2);
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='TOOL'",Integer.class,b.ids.run_id())).isEqualTo(sources*2+4);
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_publication WHERE run_id=? AND status='COMPLETED'",Integer.class,b.ids.run_id())).isEqualTo(1);
        assertThat(multi.publication.publish("Bearer fixture-service",multi.request)).isEqualTo(report);
        assertThat(b.reads.get()).isEqualTo(sources*2);
    }
    @Test void twoPlusTwoKnowledgeOriginalsSealWithRealCriterionProofAndSharedSqlBudget() {assertWholeKnowledgeReport(4);}
    @Test void twoPlusThreeKnowledgeOriginalsSealWithRealCriterionProofAndSharedSqlBudget() {assertWholeKnowledgeReport(5);}
    @Test void multiInvestigationBudgetRefusalPreservesBothChecksAndCannotSealSupportedSubset() {
        Bridge b=bridge(13);var multi=multipleKnowledgeInvestigations(b,5);
        assertThatThrownBy(()->multi.publication.publish("Bearer fixture-service",multi.request)).isInstanceOf(EvidenceException.class).hasMessageContaining("AGENT_BUDGET_EXCEEDED");
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_evidence_check WHERE run_id=? AND status='COMPLETED'",Integer.class,b.ids.run_id())).isEqualTo(2);
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_source_validation WHERE run_id=? AND status='COMPLETED'",Integer.class,b.ids.run_id())).isEqualTo(4);
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='TOOL'",Integer.class,b.ids.run_id())).isEqualTo(13);
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_publication WHERE run_id=? AND status='COMPLETED'",Integer.class,b.ids.run_id())).isZero();
    }
    @Test void sharedFailedAttemptIndependentlyInvalidatesDoneGoalsAndPreviouslyUsedDependentProof() throws Exception {
        Bridge b=bridge(16);var publication=supportedPacket(b);
        String run=b.ids.run_id();
        b.db.update("INSERT INTO agent_research_task(run_id,task_id,objective,dependencies,status,acceptance_criteria,plan_version,task_json,claim_token) VALUES (?,'task-child','Derived rate conclusion',ARRAY['task-main'],'running',ARRAY['Verify the derived rate'],1,'{}',?::uuid)",run,b.ids.claim_token());
        JsonNode evidence=JSON.readTree(b.db.queryForObject("SELECT payload::text FROM agent_evidence_record WHERE run_id=? AND record_type='Evidence'",String.class,run));
        JsonNode scope=object("subject","API limits","version",known("2.0"),"valid_at",unknown("No applicable date"),"conditions",List.of());
        var raw=operation(b,4,"TOOL");var child=new EvidenceDtos.Identifiers(run,run,"task-child",raw.call_id(),raw.claim_token());
        var prepared=b.service.prepare("Bearer fixture-service",new EvidenceDtos.PrepareRequest(child,List.of(new EvidenceDtos.ClaimSpec("The API allows 100 requests.","factual",scope)),List.of(evidence.path("evidence_id").asText()),0,null));
        registerCriterion(b,child,prepared,0,"Verify the derived rate");completePrepared(b,child,prepared,"model:child");
        b.db.update("UPDATE agent_research_task SET status='done' WHERE run_id=?",run);
        var grant=b.authority.authorize("Bearer fixture-service","publish_evidence",publication.identifiers());
        assertThat(b.authority.reportGoals(grant)).allSatisfy(g->assertThat(g.completionVerified()).isTrue());
        String investigation=b.db.queryForObject("SELECT investigation FROM agent_research_criterion WHERE run_id=? AND task_id='task-main'",String.class,run);
        var failed=operation(b,5,"TOOL");
        b.db.update("UPDATE agent_research_investigation_progress SET current_call_id=?,claim_token=?::uuid WHERE run_id=? AND investigation=?",failed.call_id(),failed.claim_token(),run,investigation);
        b.authority.settle(b.authority.authorize("Bearer fixture-service","check_claims",failed),object("errorCode","CHECK_OPERATION_FAILED"));
        var afterFailure=b.authority.reportGoals(grant);
        assertThat(afterFailure).allSatisfy(g->assertThat(g.completionVerified()).isFalse());
        assertThat(afterFailure.stream().filter(g->g.taskId().equals("task-child")).findFirst().orElseThrow().criteria().get(0).status()).isEqualTo("stale");
        String parent=b.db.queryForObject("SELECT check_id FROM agent_evidence_check WHERE run_id=? AND investigation=? ORDER BY dispute_round DESC LIMIT 1",String.class,run,investigation);
        var retry=operation(b,6,"TOOL");
        var recovery=b.service.prepare("Bearer fixture-service",new EvidenceDtos.PrepareRequest(retry,List.of(new EvidenceDtos.ClaimSpec("Version 2.0 allows 100 requests.","factual",scope)),List.of(evidence.path("evidence_id").asText()),1,parent));
        b.db.update("UPDATE agent_research_investigation_progress SET current_call_id=?,claim_token=?::uuid WHERE run_id=? AND investigation=?",retry.call_id(),retry.claim_token(),run,investigation);
        completePrepared(b,retry,recovery,"model:parent-recovered");
        var recovered=b.authority.reportGoals(grant);
        assertThat(recovered.stream().filter(g->g.taskId().equals("task-main")).findFirst().orElseThrow().completionVerified()).isTrue();
        assertThat(recovered.stream().filter(g->g.taskId().equals("task-child")).findFirst().orElseThrow().completionVerified()).isFalse();
        var report=b.publication.publish("Bearer fixture-service",publication);
        assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.path("answer").asText()).contains("Verify the derived rate","Prerequisite proof changed");
    }
}

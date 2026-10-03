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
    private EvidenceDtos.PublishRequest supportedPacket(Bridge b) { return supportedPacket(b,true); }
    private EvidenceDtos.PublishRequest supportedPacket(Bridge b,boolean declare) {
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
        if(declare) registerRequirements(b,List.of(object("task_id",check.task_id(),"criterion_id",AgentCompletionService.criterionId(check.run_id(),check.task_id(),0,"quote verified"),"text","quote verified","claim",AgentCompletionService.normalize(prepared.request().path("claims").get(0)))));
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
    private void registerRequirements(Bridge b,List<JsonNode> obligations) {
        String run=b.ids.run_id(),question=b.db.queryForObject("SELECT question FROM agent_workflow_run WHERE run_id=?",String.class,run);
        var declarations=new java.util.ArrayList<JsonNode>();var requirements=new java.util.ArrayList<JsonNode>();
        for(var obligation:obligations) {
            var draft=object("text",obligation.path("text"),"question_spans",List.of(object("start",0,"end",question.codePointCount(0,question.length()))),
                "kind",obligation.path("claim").path("kind"),"applicability",obligation.path("claim").path("applicability"));
            declarations.add(draft);
            var requirement=(com.fasterxml.jackson.databind.node.ObjectNode)draft.deepCopy();
            requirement.put("requirement_id","requirement-"+sha(canonical(object("contract_version","agent-original-requirements/1","run_id",run,"question_sha256",sha(question),"declaration",draft))).substring(0,48));
            requirements.add(requirement);
        }
        requirements.sort(java.util.Comparator.comparing(r->r.path("requirement_id").asText()));
        var manifest=object("contract_version","agent-original-requirements/1","run_id",run,"question_sha256",sha(question),
            "question_length",question.codePointCount(0,question.length()),"requirements",requirements);
        manifest.put("manifest_sha256",sha(canonical(manifest)));
        b.db.update("INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token) VALUES (?,'model:planning-fixture',1,'MODEL','DECISION',?,'RESERVED',1000,1000,?::uuid)",run,"d".repeat(64),b.ids.claim_token());
        b.db.update("UPDATE agent_research_operation SET status='SETTLED',safe_result=?::jsonb,actual_usage='{}',settled_at=now() WHERE run_id=? AND operation_key='model:planning-fixture'",canonical(object("value",object("requirements",declarations))),run);
        b.db.update("INSERT INTO agent_research_requirements(run_id,manifest,declaration_key,declaration_attempt,claim_token) VALUES (?,?::jsonb,'model:planning-fixture',1,?::uuid)",run,canonical(manifest),b.ids.claim_token());
        for(int index=0;index<obligations.size();index++) {
            String text=obligations.get(index).path("text").asText();
            String id=requirements.stream().filter(r->r.path("text").asText().equals(text)).findFirst().orElseThrow().path("requirement_id").asText();
            b.db.update("INSERT INTO agent_research_requirement_binding(run_id,requirement_id,task_id,criterion_id,claim_token) VALUES (?,?,?,?,?::uuid)",run,id,obligations.get(index).path("task_id").asText(),obligations.get(index).path("criterion_id").asText(),b.ids.claim_token());
        }
    }
    private Bridge dualRequirements(boolean checkSecond) throws Exception {
        Bridge b=bridge(16);supportedPacket(b,false);
        b.db.update("UPDATE agent_workflow_run SET question='Verify API quota and document version under version 2.0' WHERE run_id=?",b.ids.run_id());
        String text="Verify document version",task="task-version",run=b.ids.run_id();
        b.db.update("INSERT INTO agent_research_task(run_id,task_id,objective,status,acceptance_criteria,plan_version,task_json,claim_token) VALUES (?,?,'Document version','running',ARRAY[?],1,'{}',?::uuid)",run,task,text,b.ids.claim_token());
        String criterion=AgentCompletionService.criterionId(run,task,0,text);
        JsonNode scope=object("subject","document version","version",known("2.0"),"valid_at",unknown("Not independently established"),"conditions",List.of());
        JsonNode claim=object("text","Document version is 2.0.","kind","factual","applicability",scope);
        b.db.update("INSERT INTO agent_research_criterion(run_id,task_id,criterion_id,criterion_index,criterion_text,claim_token) VALUES (?,?,?,0,?,?::uuid)",run,task,criterion,text,b.ids.claim_token());
        if(checkSecond) {
            JsonNode evidence=JSON.readTree(b.db.queryForObject("SELECT payload::text FROM agent_evidence_record WHERE run_id=? AND record_type='Evidence'",String.class,run));
            var raw=operation(b,4,"TOOL");var ids=new EvidenceDtos.Identifiers(run,run,task,raw.call_id(),raw.claim_token());
            var prepared=b.service.prepare("Bearer fixture-service",new EvidenceDtos.PrepareRequest(ids,List.of(new EvidenceDtos.ClaimSpec("Document version is 2.0.","factual",scope)),List.of(evidence.path("evidence_id").asText()),0,null));
            // Initial uncovered row already exists; bind its current reserved attempt.
            b.db.update("DELETE FROM agent_research_criterion WHERE run_id=? AND task_id=?",run,task);
            registerCriterion(b,ids,prepared,0,text);completePrepared(b,ids,prepared,"model:document-version");
        }
        JsonNode first=JSON.readTree(b.db.queryForObject("SELECT expected_claim::text FROM agent_research_criterion WHERE run_id=? AND task_id='task-main'",String.class,run));
        registerRequirements(b,List.of(object("task_id","task-main","criterion_id",AgentCompletionService.criterionId(run,"task-main",0,"quote verified"),"text","Verify API quota","claim",first),
                object("task_id",task,"criterion_id",criterion,"text",text,"claim",claim)));
        // Deliberately misleading task labels do not constitute requirement proof.
        b.db.update("UPDATE agent_research_task SET status='done' WHERE run_id=?",run);
        return b;
    }
    @Test void secondOriginalObligationBlocksCompleteEvenWithDoneTasksAndSupportedPacket() throws Exception {
        Bridge b=dualRequirements(false);
        var report=b.publication.publish("Bearer fixture-service",new EvidenceDtos.ReportRequest(operation(b,5,"PUBLICATION")));
        assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.path("report_status").asText()).isEqualTo("partial");
        assertThat(report.path("answer").asText()).contains("Verify document version");
        assertThat(report.path("unfinished_goals").toString()).contains("requirement-");
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_publication WHERE run_id=? AND result->>'report_status'='complete'",Integer.class,b.ids.run_id())).isZero();
    }
    @Test void bothOriginalObligationsReachRealCitedPublicationWithNoSynthesisCall() throws Exception {
        Bridge b=dualRequirements(true);
        int models=b.db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='MODEL'",Integer.class,b.ids.run_id());
        var report=b.publication.publish("Bearer fixture-service",new EvidenceDtos.ReportRequest(operation(b,5,"PUBLICATION")));
        assertThat(report.path("terminal_status").asText()).isEqualTo("SUCCEEDED");
        assertThat(report.path("report_status").asText()).isEqualTo("complete");
        assertThat(report.path("claims")).hasSize(2);assertThat(report.path("citations")).hasSize(1);
        assertThat(report.path("answer").asText()).contains("[来源1]");
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND kind='MODEL'",Integer.class,b.ids.run_id())).isEqualTo(models);
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_operation WHERE run_id=? AND operation_key LIKE 'server:publication-read:%'",Integer.class,b.ids.run_id())).isGreaterThan(0);
    }
    @Test void originalManifestAndAssociationsAreImmutableAndCannotBeFabricatedFromCheckReceipt() throws Exception {
        Bridge b=dualRequirements(true);String run=b.ids.run_id();
        assertThatThrownBy(()->b.db.update("UPDATE agent_research_requirements SET manifest=jsonb_set(manifest,'{question_sha256}',to_jsonb(?::text)) WHERE run_id=?","e".repeat(64),run)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->b.db.update("UPDATE agent_research_requirement_binding SET criterion_id='forged' WHERE run_id=?",run)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->b.db.update("UPDATE agent_research_requirements SET declaration_key='model:check' WHERE run_id=?",run)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        b.db.update("UPDATE agent_workflow_run SET question='A different original question' WHERE run_id=?",run);
        var report=b.publication.publish("Bearer fixture-service",new EvidenceDtos.ReportRequest(operation(b,5,"PUBLICATION")));
        assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.path("answer").asText()).contains("Original requirement binding");
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
        return completePrepared(b,ids,prepared,modelId,false);
    }
    private EvidenceDtos.RecordResult completePrepared(Bridge b,EvidenceDtos.Identifiers ids,EvidenceDtos.PreparedCheck prepared,String modelId,boolean contrary) {
        var proposals=new java.util.ArrayList<JsonNode>();
        for(var claim:prepared.request().path("claims")) {
            var relations=new java.util.ArrayList<JsonNode>();
            for(var evidence:prepared.request().path("evidence")) {
                String original=evidence.path("snapshot").path("text").asText();
                relations.add(object("evidence_id",evidence.path("evidence_id"),"relation",contrary && original.contains("50 requests")?"refutes":"supports","quote",original,"reason","Exact original contains the selected limit or its contrary value"));
            }
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
    record UnboundResearch(Bridge b,EvidenceDtos.PublishRequest publication,EvidenceDtos.PreparedCheck root,
                           List<EvidenceDtos.ClaimSpec> claims,JsonNode original,JsonNode sealed) { }
    private UnboundResearch unboundResearch(boolean seal,String text) {
        Bridge b=bridge(16);
        var original=b.service.read("Bearer fixture-service",new EvidenceDtos.ReadRequest(operation(b,4,"TOOL"),"ragflow:dataset:document:chunk"));
        var first=operation(b,5,"TOOL");
        var scope=object("subject","API limits","version",known("2.0"),"valid_at",unknown("No applicable date"),"conditions",List.of());
        var claims=List.of(new EvidenceDtos.ClaimSpec(text,"factual",scope));
        var root=b.service.prepare("Bearer fixture-service",new EvidenceDtos.PrepareRequest(first,claims,List.of(original.path("evidence_id").asText()),0,null));
        b.db.update("INSERT INTO agent_research_investigation_progress(run_id,investigation,current_call_id,claim_token) VALUES (?,?,?,?::uuid)",first.run_id(),root.investigation_id(),first.call_id(),first.claim_token());
        completePrepared(b,first,root,"model:unbound-root");
        var publication=supportedPacket(b);
        var result=seal?b.publication.publish("Bearer fixture-service",publication):null;
        if(seal) {
            assertThat(result.path("report_status").asText()).isEqualTo("complete");assertThat(result.path("claims")).hasSize(2);
            int reads=b.reads.get();assertThat(b.publication.publish("Bearer fixture-service",publication)).isEqualTo(result);
            assertThat(b.reads.get()).isEqualTo(reads);
        }
        return new UnboundResearch(b,publication,root,claims,original,result);
    }
    private EvidenceDtos.Identifiers advanceUnbound(UnboundResearch context,String state) {
        var b=context.b;var next=operation(b,6,"TOOL");
        b.db.update("UPDATE agent_research_investigation_progress SET current_call_id=?,claim_token=?::uuid WHERE run_id=? AND investigation=?",next.call_id(),next.claim_token(),next.run_id(),context.root.investigation_id());
        if(state.equals("pending")) b.service.prepare("Bearer fixture-service",new EvidenceDtos.PrepareRequest(next,context.claims,List.of(context.original.path("evidence_id").asText()),1,context.root.check_id()));
        else if(state.equals("failed")) b.authority.settle(b.authority.authorize("Bearer fixture-service","check_claims",next),object("errorCode","CHECK_OPERATION_FAILED"));
        else if(state.equals("unknown")) b.db.update("UPDATE agent_research_operation SET status='UNKNOWN',settled_at=now() WHERE run_id=? AND operation_key=?",next.run_id(),next.call_id());
        return next;
    }
    private void rejectStaleUnboundReport(UnboundResearch context) {
        var b=context.b;
        var grant=b.authority.authorize("Bearer fixture-service","publish_evidence",context.publication.identifiers());
        assertThat(canonical(JSON.valueToTree(b.authority.reportGoals(grant)))).isEqualTo(canonical(context.sealed.path("goals")));
        assertThatThrownBy(()->b.publication.publish("Bearer fixture-service",context.publication)).isInstanceOf(EvidenceException.class).hasMessageContaining("REPORT_STATE_CHANGED");
        var repository=new WorkflowRepository(b.db,JSON);var citations=List.of("kb:ragflow:dataset:document:chunk");
        assertThat(repository.sealedAgentReport(b.ids.run_id(),context.sealed.path("answer").asText(),citations,"SUCCEEDED")).isEmpty();
        var workflows=new WorkflowService(repository,mock(AgentStateService.class),mock(UserContextService.class),JSON,true,Duration.ofSeconds(180));
        assertThatThrownBy(()->b.tx.executeWithoutResult(status->{
            b.db.update("UPDATE agent_workflow_run SET status='FINALIZING',stage='FINALIZING' WHERE run_id=?",b.ids.run_id());
            workflows.finalizeRun(b.ids.run_id(),new WorkflowDtos.FinalizeRequest(b.ids.claim_token(),"SUCCEEDED",context.sealed.path("answer").asText(),citations,null,null,null));
        })).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("发布证明");
        assertThat(b.db.queryForObject("SELECT status FROM agent_workflow_run WHERE run_id=?",String.class,b.ids.run_id())).isEqualTo("WORKING");
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_publication WHERE run_id=? AND status='COMPLETED'",Integer.class,b.ids.run_id())).isEqualTo(1);
    }
    @Test void unboundPendingSupplementInvalidatesWholeSealedReportAndActualFinalize() {
        var context=unboundResearch(true,"Document version is 2.0.");advanceUnbound(context,"pending");rejectStaleUnboundReport(context);
        var fresh=context.b.publication.publish("Bearer fixture-service",new EvidenceDtos.ReportRequest(operation(context.b,7,"PUBLICATION")));
        assertThat(fresh.path("report_status").asText()).isEqualTo("partial");assertThat(fresh.path("unfinished_goals").toString()).contains("pending");
    }
    @Test void unboundFailedSupplementInvalidatesWholeSealedReportAndActualFinalize() {
        var context=unboundResearch(true,"Document version is 2.0.");advanceUnbound(context,"failed");rejectStaleUnboundReport(context);
    }
    @Test void unboundUnknownSupplementInvalidatesWholeSealedReportAndActualFinalize() {
        var context=unboundResearch(true,"Document version is 2.0.");advanceUnbound(context,"unknown");rejectStaleUnboundReport(context);
    }
    private void assertUnboundGapBeforeReport(String state) {
        var context=unboundResearch(false,"Document version is 2.0.");advanceUnbound(context,state);var b=context.b;
        var report=b.publication.publish("Bearer fixture-service",context.publication);
        assertThat(report.path("report_status").asText()).isEqualTo("partial");assertThat(report.path("claims")).hasSize(2);
        assertThat(report.path("unfinished_goals").toString()).contains(state.equals("pending")?"pending":"failed");
        int reads=b.reads.get();assertThat(b.publication.publish("Bearer fixture-service",context.publication)).isEqualTo(report);assertThat(b.reads.get()).isEqualTo(reads);
        var repository=new WorkflowRepository(b.db,JSON);var citations=List.of("kb:ragflow:dataset:document:chunk");
        assertThat(repository.sealedAgentReport(b.ids.run_id(),report.path("answer").asText(),citations,"INSUFFICIENT_EVIDENCE")).isPresent();
        var workflows=new WorkflowService(repository,mock(AgentStateService.class),mock(UserContextService.class),JSON,true,Duration.ofSeconds(180));
        b.db.update("UPDATE agent_workflow_run SET status='FINALIZING',stage='FINALIZING' WHERE run_id=?",b.ids.run_id());
        var request=new WorkflowDtos.FinalizeRequest(b.ids.claim_token(),"INSUFFICIENT_EVIDENCE",report.path("answer").asText(),citations,null,null,null);
        assertThat(b.tx.execute(status->workflows.finalizeRun(b.ids.run_id(),request)).replayed()).isFalse();
        assertThat(b.tx.execute(status->workflows.finalizeRun(b.ids.run_id(),request)).replayed()).isTrue();
    }
    @Test void failedUnboundAttemptBeforeReportKeepsGapAndPartialReportCanFinalizeAndReplay() {assertUnboundGapBeforeReport("failed");}
    @Test void unknownUnboundAttemptBeforeReportKeepsGapAndPartialReportCanFinalizeAndReplay() {assertUnboundGapBeforeReport("unknown");}
    @Test void pendingUnboundAttemptBeforeReportKeepsGapAndPartialReportCanFinalizeAndReplay() {assertUnboundGapBeforeReport("pending");}
    @Test void unboundContestedSupplementInvalidatesWholeSealedReportWithoutChangingGoals() {
        var context=unboundResearch(true,"The independent API limit is 100 requests.");var b=context.b;
        var search=operation(b,8,"TOOL");var hits=object("success",true,"tool","kb_search","evidence",List.of(object("evidenceId","ragflow:dataset:counter:counter","uriOrChunkKey","ragflow:dataset:counter:counter","title","Contrary managed source")));
        b.db.update("INSERT INTO agent_workflow_tool_receipt(run_id,call_id,task_id,tool_name,request_fingerprint,status,safe_result,completed_at,claim_token,mcp_execution_status,mcp_safe_result,mcp_claim_token,mcp_started_at,mcp_completed_at) VALUES (?,?,'task-main','kb_search',?,'COMPLETED','{}',now(),?::uuid,'COMPLETED',?::jsonb,?::uuid,now(),now())",search.run_id(),search.call_id(),sha("counter-search"),search.claim_token(),canonical(hits),search.claim_token());
        b.authority.settle(b.authority.authorize("Bearer fixture-service","check_claims",search),hits);
        SourceReader reader=(g,c)->{
            b.reads.incrementAndGet();String text=c.documentId().equals("counter")?"Document version: 2.0\nVersion 2.0 allows 50 requests.\n":b.text;
            return new SourceReader.Document(text,c.title(),object("kind","knowledge_chunk","dataset_id",c.datasetId(),"document_id",c.documentId(),"chunk_id",c.chunkId()),"document_chunk",Instant.now(),sha(text),false,false);
        };
        var manager=new DataSourceTransactionManager(b.db.getDataSource());var service=new EvidenceService(b.authority,new JdbcEvidenceStore(b.db,b.tx),reader);
        var counter=service.read("Bearer fixture-service",new EvidenceDtos.ReadRequest(operation(b,7,"TOOL"),"ragflow:dataset:counter:counter"));
        var next=advanceUnbound(context,"start");
        var prepared=service.prepare("Bearer fixture-service",new EvidenceDtos.PrepareRequest(next,context.claims,List.of(context.original.path("evidence_id").asText(),counter.path("evidence_id").asText()),1,context.root.check_id()));
        var combined=new Bridge(b.db,b.tx,b.authority,service,new AgentPublicationController(b.authority,service,b.db,manager),b.ids,b.reads,b.text);
        var outcome=completePrepared(combined,next,prepared,"model:unbound-contrary",true);
        assertThat(outcome.records().stream().filter(r->r.path("record_type").asText().equals("Claim")).findFirst().orElseThrow().path("decision_status").asText()).isEqualTo("contested");
        rejectStaleUnboundReport(context);
        var fresh=combined.publication.publish("Bearer fixture-service",new EvidenceDtos.ReportRequest(operation(b,9,"PUBLICATION")));
        assertThat(fresh.path("report_status").asText()).isEqualTo("partial");assertThat(fresh.path("answer").asText()).contains("仍有争议");
    }
    @Test void unboundFailureDuringReportSourceRevalidationCannotSealOldState() {
        var context=unboundResearch(false,"Document version is 2.0.");var b=context.b;var changed=new java.util.concurrent.atomic.AtomicBoolean();
        SourceReader reader=(g,c)->{
            if(changed.compareAndSet(false,true)) advanceUnbound(context,"failed");
            return new SourceReader.Document(b.text,c.title(),object("kind","knowledge_chunk","dataset_id",c.datasetId(),"document_id",c.documentId(),"chunk_id",c.chunkId()),"document_chunk",Instant.now(),sha(b.text),false,false);
        };
        var manager=new DataSourceTransactionManager(b.db.getDataSource());var service=new EvidenceService(b.authority,new JdbcEvidenceStore(b.db,b.tx),reader);
        var publication=new AgentPublicationController(b.authority,service,b.db,manager);
        assertThatThrownBy(()->publication.publish("Bearer fixture-service",context.publication)).isInstanceOf(EvidenceException.class).hasMessageContaining("REPORT_STATE_CHANGED");
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_publication WHERE run_id=? AND status='COMPLETED'",Integer.class,b.ids.run_id())).isZero();
    }
    @Test void unboundFailureAfterReportGenerationButBeforeSealCannotSealOldState() {
        var context=unboundResearch(false,"Document version is 2.0.");var b=context.b;
        SourceReader reader=(g,c)->new SourceReader.Document(b.text,c.title(),object("kind","knowledge_chunk","dataset_id",c.datasetId(),"document_id",c.documentId(),"chunk_id",c.chunkId()),"document_chunk",Instant.now(),sha(b.text),false,false);
        var manager=new DataSourceTransactionManager(b.db.getDataSource());var snapshots=new AtomicInteger();var injected=new java.util.concurrent.atomic.AtomicBoolean();
        var authority=new AgentEvidenceAuthority(new AgentRunAuthorization(mock(WorkflowAccessService.class),b.db),b.db,manager) {
            @Override public JsonNode reportState(EvidenceAuthority.Grant g) {snapshots.incrementAndGet();return super.reportState(g);}
            @Override public void lock(EvidenceAuthority.Grant g) {
                if(snapshots.get()==2 && injected.compareAndSet(false,true)) {
                    try {java.util.concurrent.CompletableFuture.runAsync(()->advanceUnbound(context,"failed")).get(5,TimeUnit.SECONDS);}
                    catch(Exception failed){throw new AssertionError("concurrent investigation did not commit before seal",failed);}
                }
                super.lock(g);
            }
        };
        var service=new EvidenceService(authority,new JdbcEvidenceStore(b.db,b.tx),reader);
        var publication=new AgentPublicationController(authority,service,b.db,manager);
        assertThatThrownBy(()->publication.publish("Bearer fixture-service",context.publication)).isInstanceOf(EvidenceException.class).hasMessageContaining("REPORT_STATE_CHANGED");
        assertThat(b.db.queryForObject("SELECT count(*) FROM agent_research_publication WHERE run_id=? AND status='COMPLETED'",Integer.class,b.ids.run_id())).isZero();
        assertThat(injected.get()).isTrue();
        assertThat(b.db.queryForObject("SELECT status FROM agent_research_operation WHERE run_id=? AND operation_key=?",String.class,b.ids.run_id(),"tool-"+"6".repeat(32))).isEqualTo("SETTLED");
    }
    @Test void sealedPartialReportBecomesStaleWhenUnboundAttemptChangesAgain() {
        var context=unboundResearch(false,"Document version is 2.0.");var b=context.b;advanceUnbound(context,"failed");
        var partial=b.publication.publish("Bearer fixture-service",context.publication);
        var next=operation(b,7,"TOOL");
        b.db.update("UPDATE agent_research_investigation_progress SET current_call_id=?,claim_token=?::uuid WHERE run_id=? AND investigation=?",next.call_id(),next.claim_token(),next.run_id(),context.root.investigation_id());
        b.service.prepare("Bearer fixture-service",new EvidenceDtos.PrepareRequest(next,context.claims,List.of(context.original.path("evidence_id").asText()),1,context.root.check_id()));
        var repository=new WorkflowRepository(b.db,JSON);
        assertThat(repository.sealedAgentReport(b.ids.run_id(),partial.path("answer").asText(),List.of("kb:ragflow:dataset:document:chunk"),"INSUFFICIENT_EVIDENCE")).isEmpty();
        assertThatThrownBy(()->b.publication.publish("Bearer fixture-service",context.publication)).isInstanceOf(EvidenceException.class).hasMessageContaining("REPORT_STATE_CHANGED");
    }
    @Test void storedDoneAndOneRealClaimCannotCoverAnAdditionalNativeStandard() {
        Bridge b=bridge(16);var publication=supportedPacket(b);
        b.db.update("UPDATE agent_research_task SET acceptance_criteria=ARRAY['quote verified','Verify the per-minute request rate'] WHERE run_id=?",b.ids.run_id());
        var report=b.publication.publish("Bearer fixture-service",publication);
        assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.path("answer").asText()).contains("Verify the per-minute request rate");
    }
    @Test void sealedCompleteReportCannotFinalizeAfterItsCurrentInvestigationFails() {
        Bridge b=bridge(16);var request=supportedPacket(b);var report=b.publication.publish("Bearer fixture-service",request);
        var repository=new WorkflowRepository(b.db,JSON);var citations=List.of("kb:ragflow:dataset:document:chunk");
        assertThat(repository.sealedAgentReport(b.ids.run_id(),report.path("answer").asText(),citations,"SUCCEEDED")).isPresent();
        var failed=operation(b,4,"TOOL");
        b.db.update("UPDATE agent_research_investigation_progress SET current_call_id=?,claim_token=?::uuid WHERE run_id=?",failed.call_id(),failed.claim_token(),b.ids.run_id());
        b.authority.settle(b.authority.authorize("Bearer fixture-service","check_claims",failed),object("errorCode","CHECK_OPERATION_FAILED"));
        assertThat(repository.sealedAgentReport(b.ids.run_id(),report.path("answer").asText(),citations,"SUCCEEDED")).isEmpty();
        var workflows=new WorkflowService(repository,mock(AgentStateService.class),mock(UserContextService.class),JSON,true,Duration.ofSeconds(180));
        assertThatThrownBy(()->b.tx.executeWithoutResult(status->workflows.finalizeRun(b.ids.run_id(),new WorkflowDtos.FinalizeRequest(b.ids.claim_token(),"SUCCEEDED",report.path("answer").asText(),citations,null,null,null)))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(b.db.queryForObject("SELECT status FROM agent_workflow_run WHERE run_id=?",String.class,b.ids.run_id())).isEqualTo("WORKING");
    }
    @Test void lostLegacyMappingDoesNotDefaultToSatisfiedAndCrossRunCriterionIdentityIsRejected() {
        Bridge b=bridge(16);var publication=supportedPacket(b);
        assertThatThrownBy(()->b.db.update("DELETE FROM agent_research_criterion WHERE run_id=?",b.ids.run_id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        // Admin-only legacy corruption fixture: sidecar has no DELETE privilege.
        b.db.update("DELETE FROM agent_research_requirement_binding WHERE run_id=?",b.ids.run_id());
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
        var obligations=new java.util.ArrayList<JsonNode>();
        for(int group=0;group<2;group++) {
            var raw=operation(b,count+group+1,"TOOL");
            var check=new EvidenceDtos.Identifiers(raw.project_id(),raw.run_id(),group==0?"task-main":"task-second",raw.call_id(),raw.claim_token());
            var selected=group==0?originals.subList(0,2):originals.subList(2,count);
            var scope=object("subject","API limits","version",known("2.0"),"valid_at",unknown("No applicable date"),"conditions",List.of());
            var prepared=service.prepare("Bearer fixture-service",new EvidenceDtos.PrepareRequest(check,List.of(new EvidenceDtos.ClaimSpec(group==0?"Document version is 2.0.":"Version 2.0 allows 100 requests.","factual",scope)),selected.stream().map(e->e.path("evidence_id").asText()).toList(),0,null));
            registerCriterion(combined,check,prepared,0,group==0?"quote verified":"Scoped rate decision");
            String criterion=group==0?"quote verified":"Scoped rate decision";
            obligations.add(object("task_id",check.task_id(),"criterion_id",AgentCompletionService.criterionId(check.run_id(),check.task_id(),0,criterion),"text",criterion,"claim",AgentCompletionService.normalize(prepared.request().path("claims").get(0))));
            completePrepared(combined,check,prepared,"model:multi-"+group);
        }
        registerRequirements(combined,obligations);
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

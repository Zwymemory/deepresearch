package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.service.UserContextService;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** One bounded disposable PG integration; actual controller/services/transactions, no model or retrieval. */
@Testcontainers
class ResearchProgressMemoryIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
        .withDatabaseName("deepresearch").withUsername("deepresearch").withPassword("deepresearch").withInitScript("init-workflow-role.sql");
    @Configuration @EnableTransactionManagement
    static class Application {
        @Bean DataSource source() { return new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager tx(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean UserContextService users() { return new UserContextService("unused",false); }
        @Bean ResearchProgressRepository repository(JdbcTemplate db) { return new ResearchProgressRepository(db); }
        @Bean ResearchProgressService service(ResearchProgressRepository repository,UserContextService users,JdbcTemplate db) { return new ResearchProgressService(repository,users,db); }
        @Bean ResearchProgressController controller(ResearchProgressService service) { return new ResearchProgressController(service); }
    }
    @Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @Import({Application.class,com.deepresearch.config.SecurityConfig.class})
    static class HttpApplication {
        @Bean com.deepresearch.security.JwtTokenService tokens() {
            // Random per test context. No developer credential or paid service is used.
            return new com.deepresearch.security.JwtTokenService(JSON,UUID.randomUUID().toString(),300);
        }
        @Bean com.deepresearch.security.JwtAuthenticationFilter authentication(com.deepresearch.security.JwtTokenService tokens) {
            return new com.deepresearch.security.JwtAuthenticationFilter(tokens,org.mockito.Mockito.mock(WorkflowAccessService.class));
        }
    }
    record HttpReply(int status,JsonNode body) {}
    HttpReply request(int port,String method,String path,String authorization) throws Exception {
        var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path))
            .timeout(java.time.Duration.ofSeconds(10)).method(method,java.net.http.HttpRequest.BodyPublishers.noBody());
        if(authorization!=null) builder.header("Authorization",authorization);
        var response=java.net.http.HttpClient.newHttpClient().send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        JsonNode result=null;
        if(response.headers().firstValue("content-type").orElse("").contains("json")) result=JSON.readTree(response.body());
        return new HttpReply(response.statusCode(),result);
    }
    @Test void actualHttpJwtDiscoverySaveListResumeDeleteAndIsolation() throws Exception {
        Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).load().migrate();
        var factory=new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory(0);
        factory.setAddress(java.net.InetAddress.getByName("127.0.0.1"));
        var web=new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        web.register(HttpApplication.class);
        var server=factory.getWebServer(servlet->{
            web.setServletContext(servlet);web.refresh();
            var dispatcher=servlet.addServlet("dispatcher",new org.springframework.web.servlet.DispatcherServlet(web));
            dispatcher.setLoadOnStartup(1);dispatcher.addMapping("/");
            servlet.addFilter("security",web.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class))
                .addMappingForUrlPatterns(EnumSet.of(jakarta.servlet.DispatcherType.REQUEST),false,"/*");
        });
        try {
            server.start();int port=server.getPort();
            // Seed controlled records through the same real database, never production research.
            Seed source;
            try(var seedContext=new AnnotationConfigApplicationContext(Application.class)) { source=seed(seedContext,"http","FAILED"); }
            var tokens=web.getBean(com.deepresearch.security.JwtTokenService.class);
            String alice=tokens.issue("tenant","alice",List.of("USER"),300L).authorizationHeader();
            String bob=tokens.issue("tenant","bob",List.of("USER"),300L).authorizationHeader();
            String foreign=tokens.issue("other","alice",List.of("USER"),300L).authorizationHeader();
            String discovery="/api/research/agents/"+source.run+"/progress-project";
            String savedRoute=route(source.project,source.run),resumeRoute="/api/research/projects/"+source.project+"/resume-context";
            assertThat(request(port,"GET","/api/research/progress",null).status).isEqualTo(401);
            assertThat(request(port,"GET",discovery,"Bearer invalid").status).isEqualTo(401);
            var discovered=request(port,"GET",discovery,alice);assertThat(discovered.status).isEqualTo(200);
            assertThat(discovered.body.path("project_id").asText()).isEqualTo(source.project);
            assertThat(request(port,"GET","/api/research/agents/no-such-run/progress-project",alice).status).isEqualTo(404);
            var saved=request(port,"PUT",savedRoute,alice);assertThat(saved.status).isEqualTo(200);
            assertThat(saved.body.path("trusted_as_evidence").asBoolean()).isFalse();
            assertThat(request(port,"GET",savedRoute,alice).body).isEqualTo(saved.body);
            var listed=request(port,"GET","/api/research/progress",alice);assertThat(listed.status).isEqualTo(200);
            assertThat(listed.body.path("items")).anyMatch(n->source.run.equals(n.path("source_run_id").asText()));
            var db=web.getBean(JdbcTemplate.class);
            int runCount=db.queryForObject("SELECT count(*) FROM agent_workflow_run",Integer.class);
            var resumed=request(port,"POST",resumeRoute,alice);assertThat(resumed.status).isEqualTo(200);
            String target=resumed.body.path("target_session_id").asText();assertThat(target).isNotBlank().isNotEqualTo(source.session);
            assertThat(resumed.body.path("progress").get(0)).isEqualTo(saved.body);
            assertThat(db.queryForObject("SELECT count(*) FROM agent_workflow_run",Integer.class)).isEqualTo(runCount);
            assertThat(request(port,"GET",resumeRoute+"?sessionId="+target,alice).body).isEqualTo(resumed.body);
            assertThat(request(port,"GET",resumeRoute,alice).status).isEqualTo(400);
            for(String denied:List.of(bob,foreign)) {
                assertThat(request(port,"GET",discovery,denied).status).isEqualTo(404);
                assertThat(request(port,"PUT",savedRoute,denied).status).isEqualTo(404);
                assertThat(request(port,"GET",savedRoute,denied).status).isEqualTo(404);
                assertThat(request(port,"DELETE",savedRoute,denied).status).isEqualTo(404);
                assertThat(request(port,"POST",resumeRoute,denied).status).isEqualTo(404);
                assertThat(request(port,"GET",resumeRoute+"?sessionId="+target,denied).status).isEqualTo(404);
                assertThat(request(port,"GET","/api/research/progress",denied).body.path("items")).isEmpty();
            }
            var deleted=request(port,"DELETE",savedRoute,alice);assertThat(deleted.status).isEqualTo(200);
            assertThat(deleted.body.path("deleted").asBoolean()).isTrue();
            assertThat(request(port,"DELETE",savedRoute,alice).body.path("deleted").asBoolean()).isFalse();
            assertThat(request(port,"GET",savedRoute,alice).status).isEqualTo(404);
            assertThat(request(port,"GET",resumeRoute+"?sessionId="+target,alice).body.path("progress")).isEmpty();
            assertThat(request(port,"GET","/api/research/progress",alice).body.path("items"))
                .noneMatch(n->source.run.equals(n.path("source_run_id").asText()));
            var fixtures=java.nio.file.Path.of("target","research-progress-contract");java.nio.file.Files.createDirectories(fixtures);
            for(var entry:Map.of("project.json",discovered.body,"saved.json",saved.body,"list.json",listed.body,
                    "resume.json",resumed.body,"deleted.json",deleted.body).entrySet())
                java.nio.file.Files.writeString(fixtures.resolve(entry.getKey()),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(entry.getValue())+"\n");
        } finally { server.stop();web.close();SecurityContextHolder.clearContext(); }
    }
    void owner(String tenant,String user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthPrincipal(tenant,user,List.of("USER")),"unused",List.of()));
    }
    String route(String project,String run) { return "/api/research/projects/"+project+"/progress/runs/"+run; }
    JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception { return JSON.readTree(result.getResponse().getContentAsString()); }
    MockMvc mvc(AnnotationConfigApplicationContext context) { return MockMvcBuilders.standaloneSetup(context.getBean(ResearchProgressController.class)).build(); }
    record Seed(String project,String run,String session,String claim,String evidence) {}
    Seed seed(AnnotationConfigApplicationContext context,String name,String status) {
        var db=context.getBean(JdbcTemplate.class);String project="project-"+name,run="run-"+name,session="session-"+name,claim=UUID.randomUUID().toString(),evidence="evidence-"+name;
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{
            db.execute("SET CONSTRAINTS ALL DEFERRED");
            db.update("INSERT INTO agent_session(session_id,user_id,title) VALUES (?,'tenant:alice','original')",session);
            db.update("""
                INSERT INTO agent_workflow_run(run_id,session_id,user_id,question,endpoint,idempotency_key,request_fingerprint,graph_thread_id,status,stage,deadline_at,grant_id,claim_token,lease_until)
                VALUES (?,?,'tenant:alice','Keep this original goal','/api/research/agents',?,'f',?,?,'WORKING',now()+interval '10 minutes',?,?::uuid,now()+interval '10 minutes')
                """,run,session,name,run,status,"grant-"+name,claim);
            db.update("INSERT INTO agent_workflow_grant(grant_id,run_id,subject,scopes,expires_at) VALUES (?,?,'tenant:alice',ARRAY['kb:search'],now()+interval '10 minutes')","grant-"+name,run);
            db.update("INSERT INTO research_project(project_id,tenant_id,owner_id,session_id) VALUES (?,'tenant','alice',?)",project,session);
            db.update("INSERT INTO agent_research_run(run_id,project_id,tenant_id,owner_id) VALUES (?,?,'tenant','alice')",run,project);
            if("FAILED".equals(status)) db.update("UPDATE agent_workflow_run SET error_code='LOCAL_FIXTURE_FAILURE' WHERE run_id=?",run);
            db.update("INSERT INTO agent_research_task(run_id,task_id,objective,status,acceptance_criteria,plan_version,task_json,claim_token) VALUES (?,'task-main','Resolve disputed work','done',ARRAY['resolve disputed work'],1,'{}',?::uuid)",run,claim);
            var e=object("record_type","Evidence","schema_version","0.1.0","tenant_id","tenant","owner_id","alice","project_id",project,"run_id",run,
                "evidence_id",evidence,"receipt_id","read-"+name,"availability","available","freshness","fresh","snapshot",object("text","Controlled original","sha256",sha("Controlled original")),"source",object("source_id","source-"+name));
            db.update("""
                INSERT INTO agent_evidence_read_receipt(receipt_id,tenant_id,owner_id,project_id,run_id,task_id,call_id,claim_token,source_id,parent_receipt_id,request_fingerprint,status,record_json,metadata,completed_at)
                VALUES (?,'tenant','alice',?,?,'task-main',?,?::uuid,?,'parent',?,'COMPLETED',?::jsonb,'{}',now())
                ""","read-"+name,project,run,"read-call-"+name,claim,"source-"+name,"a".repeat(64),canonical(e));
            record(db,project,run,"Evidence",evidence,e,"read-"+name);
            var c=object("record_type","Claim","schema_version","0.1.0","tenant_id","tenant","owner_id","alice","project_id",project,"run_id",run,
                "claim_id","claim-"+name,"decision_status","FAILED".equals(status)?"contested":"unverified","freshness","fresh");
            record(db,project,run,"Claim","claim-"+name,c,null);
        });
        return new Seed(project,run,session,claim,evidence);
    }
    void record(JdbcTemplate db,String project,String run,String type,String id,JsonNode payload,String receipt) {
        db.update("INSERT INTO agent_evidence_record(tenant_id,owner_id,project_id,record_type,record_id,version,run_id,read_receipt_id,payload,payload_sha256) VALUES ('tenant','alice',?,?,?,1,?,?,?::jsonb,?)",
            project,type,id,run,receipt,canonical(payload),sha(canonical(payload)));
    }
    // Local server-record fixture for the existing native completion checker; no model call.
    void completedProof(JdbcTemplate db,Seed source) {
        String claimId="claim-completed-proof",call="check-supported",check="assessment-supported",investigation=sha(call);
        var claim=object("record_type","Claim","schema_version","0.1.0","tenant_id","tenant","owner_id","alice",
            "project_id",source.project,"run_id",source.run,"claim_id",claimId,"decision_status","supported","freshness","fresh",
            "text","Controlled completed work","kind","factual","applicability",object("subject","fixture",
                "version",known("1"),"valid_at",known("2026-10-05"),"conditions",List.of()));
        var decision=object("record_type","DecisionRecord","schema_version","0.1.0","tenant_id","tenant","owner_id","alice",
            "project_id",source.project,"run_id",source.run,"claim_id",claimId,"decision_status","supported",
            "gaps",List.of(),"unresolved_evidence_ids",List.of(),"adopted_evidence_ids",List.of(source.evidence));
        var expected=AgentCompletionService.normalize(claim);
        var outcome=object("check_id",check,"records",List.of(claim,decision));
        db.update("UPDATE agent_workflow_run SET budget=?::jsonb WHERE run_id=?",canonical(object("runtime","agent",
            "maxModelCalls",1,"maxToolCalls",1,"maxInputTokens",100,"maxOutputTokens",100,"maxDecisionSteps",1)),source.run);
        db.update("""
            INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token)
            VALUES (?,?,1,'TOOL','TOOL',?,'RESERVED',0,0,?::uuid)
            """,source.run,call,sha(call),source.claim);
        db.update("INSERT INTO agent_research_investigation_progress(run_id,investigation,current_call_id,claim_token) VALUES (?,?,?,?::uuid)",
            source.run,investigation,call,source.claim);
        db.update("""
            INSERT INTO agent_research_criterion(run_id,task_id,criterion_id,criterion_index,criterion_text,expected_claim,expected_hash,investigation,last_call_id,claim_token)
            VALUES (?,'task-main',?,0,'resolve disputed work',?::jsonb,?,?,?,?::uuid)
            """,source.run,AgentCompletionService.criterionId(source.run,"task-main",0,"resolve disputed work"),
            canonical(expected),sha(canonical(expected)),investigation,call,source.claim);
        db.update("""
            INSERT INTO agent_evidence_check(check_id,tenant_id,owner_id,project_id,run_id,task_id,call_id,investigation,dispute_round,
                request_fingerprint,request_sha256,request,status,assessment_id,result,completed_at)
            VALUES (?,'tenant','alice',?,?,'task-main',?,?,0,?,?,'{}','COMPLETED',?,?::jsonb,now())
            """,check,source.project,source.run,call,investigation,sha(call),sha("{}"),check,canonical(outcome));
        record(db,source.project,source.run,"Claim",claimId,claim,null);
        record(db,source.project,source.run,"DecisionRecord","decision-"+claimId,decision,null);
        db.update("UPDATE agent_research_operation SET status='SETTLED',safe_result=?::jsonb,actual_usage='{}',settled_at=now() WHERE run_id=? AND operation_key=?",
            canonical(outcome),source.run,call);
    }
    @Test void saveResumeFromDifferentSessionRestartIsolationIdempotencyDeletionAndUnresolvedStatus() throws Exception {
        Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).load().migrate();
        Seed source;
        try(var context=new AnnotationConfigApplicationContext(Application.class)) {
            source=seed(context,"failed","FAILED");var db=context.getBean(JdbcTemplate.class);var http=mvc(context);owner("tenant","alice");
            var first=http.perform(put(route(source.project,source.run))).andReturn();assertThat(first.getResponse().getStatus()).isEqualTo(200);
            var snapshot=body(first);assertThat(snapshot.path("run_status").asText()).isEqualTo("FAILED");
            assertThat(snapshot.path("completed_work")).isEmpty();assertThat(snapshot.path("unresolved_questions")).isNotEmpty();
            assertThat(snapshot.path("source_claims").get(0).path("decision_status").asText()).isEqualTo("contested");
            assertThat(snapshot.path("unresolved_questions").toString()).contains("LOCAL_FIXTURE_FAILURE");
            assertThat(snapshot.path("trusted_as_evidence").asBoolean()).isFalse();
            var saved=db.queryForObject("SELECT saved_at FROM research_progress_memory WHERE run_id=?",Object.class,source.run);
            assertThat(http.perform(put(route(source.project,source.run))).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(db.queryForObject("SELECT count(*) FROM research_progress_memory WHERE run_id=?",Integer.class,source.run)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT saved_at FROM research_progress_memory WHERE run_id=?",Object.class,source.run)).isEqualTo(saved);
            db.update("INSERT INTO agent_session(session_id,user_id,title) VALUES ('different-session','tenant:alice','resume'),('foreign-session','tenant:bob','foreign')");
            var resume=http.perform(get("/api/research/projects/"+source.project+"/resume-context").param("sessionId","different-session")).andReturn();
            assertThat(resume.getResponse().getStatus()).isEqualTo(200);assertThat(body(resume).path("progress").get(0)).isEqualTo(snapshot);
            assertThat(body(http.perform(get("/api/research/projects/"+source.project+"/resume-context").param("sessionId",source.session)).andReturn()).path("progress")).isEmpty();
            assertThat(http.perform(get("/api/research/projects/"+source.project+"/resume-context").param("sessionId","foreign-session")).andReturn().getResponse().getStatus()).isEqualTo(404);
            var other=seed(context,"pending","WORKING");
            assertThat(http.perform(get("/api/research/projects/"+source.project+"/resume-context").param("sessionId",other.session)).andReturn().getResponse().getStatus()).isEqualTo(404);
            assertThat(http.perform(put(route(source.project,other.run))).andReturn().getResponse().getStatus()).isEqualTo(404);
            var pending=body(http.perform(put(route(other.project,other.run))).andReturn());assertThat(pending.path("run_status").asText()).isEqualTo("WORKING");assertThat(pending.path("completed_work")).isEmpty();
            assertThat(pending.path("source_claims").get(0).path("decision_status").asText()).isEqualTo("unverified");
            var supported=seed(context,"supported","WORKING");completedProof(db,supported);
            var completed=body(http.perform(put(route(supported.project,supported.run))).andReturn());
            assertThat(completed.path("completed_work").size()).isEqualTo(1);
            assertThat(completed.path("completed_work").get(0).path("completion_verified").asBoolean()).isTrue();
            assertThat(completed.path("unresolved_questions")).isNotEmpty();
            assertThat(body(http.perform(get(route(supported.project,supported.run))).andReturn())).isEqualTo(completed);
            for(var identity:List.of(new AuthPrincipal("tenant","bob",List.of()),new AuthPrincipal("another-tenant","alice",List.of()))) {
                owner(identity.tenantId(),identity.userId());
                assertThat(http.perform(put(route(source.project,source.run))).andReturn().getResponse().getStatus()).isEqualTo(404);
                assertThat(http.perform(get(route(source.project,source.run))).andReturn().getResponse().getStatus()).isEqualTo(404);
                assertThat(http.perform(delete(route(source.project,source.run))).andReturn().getResponse().getStatus()).isEqualTo(404);
                assertThat(http.perform(get("/api/research/projects/"+source.project+"/resume-context").param("sessionId","different-session")).andReturn().getResponse().getStatus()).isEqualTo(404);
            }
            SecurityContextHolder.clearContext();assertThat(http.perform(get(route(source.project,source.run))).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        // Reconstruct the entire small service application; the same disposable DB persists.
        try(var restarted=new AnnotationConfigApplicationContext(Application.class)) {
            owner("tenant","alice");var http=mvc(restarted);var db=restarted.getBean(JdbcTemplate.class);
            var resumed=body(http.perform(get("/api/research/projects/"+source.project+"/resume-context").param("sessionId","different-session")).andReturn());
            assertThat(resumed.path("progress").size()).isEqualTo(1);assertThat(resumed.path("progress").get(0).path("original_goal").asText()).isEqualTo("Keep this original goal");
            // Deleted original references disappear from both read and resume; no old content leak.
            db.update("DELETE FROM agent_evidence_record WHERE run_id=? AND record_type='Evidence'",source.run);
            assertThat(http.perform(get(route(source.project,source.run))).andReturn().getResponse().getStatus()).isEqualTo(404);
            assertThat(body(http.perform(get("/api/research/projects/"+source.project+"/resume-context").param("sessionId","different-session")).andReturn()).path("progress")).isEmpty();
            assertThat(body(http.perform(delete(route(source.project,source.run))).andReturn()).path("deleted").asBoolean()).isTrue();
            assertThat(body(http.perform(delete(route(source.project,source.run))).andReturn()).path("deleted").asBoolean()).isFalse();
            assertThat(http.perform(get(route(source.project,source.run))).andReturn().getResponse().getStatus()).isEqualTo(404);
        } finally { SecurityContextHolder.clearContext(); }
    }
}

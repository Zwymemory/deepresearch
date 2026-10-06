package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static com.deepresearch.workflow.ResearchProgressSelectionDtos.*;
import static org.assertj.core.api.Assertions.*;

/** Disposable loopback PostgreSQL; real JDBC and Spring REQUIRED transactions, no providers. */
@Testcontainers
class ResearchProgressSelectionIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
        .withDatabaseName("deepresearch").withUsername("deepresearch").withPassword("deepresearch")
        .withInitScript("init-workflow-role.sql");
    static final AuthPrincipal ALICE=new AuthPrincipal("tenant","alice",List.of("USER"));
    @Configuration @EnableTransactionManagement
    static class Application {
        @Bean DataSource source() { return new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager tx(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ResearchProgressRepository repository(JdbcTemplate db) { return new ResearchProgressRepository(db); }
        @Bean ResearchProgressSelectionService service(ResearchProgressRepository repository,JdbcTemplate db) {
            return new ResearchProgressSelectionService(repository,db);
        }
    }
    @BeforeAll static void migrateOnlyDisposableDatabase() {
        Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).load().migrate();
    }
    record Seed(String project,String source,String target,String sourceSession,String targetSession,UUID claim,JsonNode snapshot) {}
    Seed seed(AnnotationConfigApplicationContext context) {
        String id=UUID.randomUUID().toString().substring(0,8),project="p-"+id,source="source-"+id,target="target-"+id;
        String sourceSession="source-session-"+id,targetSession="target-session-"+id;UUID claim=UUID.randomUUID();
        var db=context.getBean(JdbcTemplate.class);
        var snapshot=ResearchProgressContextAssemblerTest.snapshot(source,sourceSession,"保留原目标：检查争议及未完成事项");
        ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot).put("project_id",project);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{
            db.update("INSERT INTO agent_session(session_id,user_id,title) VALUES (?,'tenant:alice','source'),(?,'tenant:alice','target')",sourceSession,targetSession);
            db.update("INSERT INTO research_project(project_id,tenant_id,owner_id,session_id) VALUES (?,'tenant','alice',?)",project,sourceSession);
            for(String run:List.of(source,target)) {
                boolean historical=run.equals(source);
                db.update("""
                    INSERT INTO agent_workflow_run(run_id,session_id,user_id,question,endpoint,idempotency_key,request_fingerprint,
                      graph_thread_id,status,stage,deadline_at,grant_id,claim_token,lease_until)
                    VALUES (?,?,'tenant:alice','synthetic research','/api/research/agents',?,?,?,?,'PLANNING',
                      clock_timestamp()+interval '5 minutes',?,?,clock_timestamp()+interval '5 minutes')
                    """,run,historical?sourceSession:targetSession,run,sha(run),run,historical?"FAILED":"PLANNING","grant-"+run,claim);
                db.update("""
                    INSERT INTO agent_workflow_grant(grant_id,run_id,subject,scopes,expires_at)
                    VALUES (?,?,'tenant:alice',ARRAY['kb_search'],clock_timestamp()+interval '5 minutes')
                    ""","grant-"+run,run);
                db.update("INSERT INTO agent_research_run(run_id,project_id,tenant_id,owner_id) VALUES (?,?,'tenant','alice')",run,project);
            }
            // Source has no live claim or grant and no tasks: readonly historical use must still work.
            db.update("UPDATE agent_workflow_run SET claim_token=NULL,lease_until=clock_timestamp()-interval '1 hour' WHERE run_id=?",source);
            db.update("UPDATE agent_workflow_grant SET expires_at=clock_timestamp()-interval '1 hour' WHERE run_id=?",source);
            context.getBean(ResearchProgressRepository.class).save(project,source,ALICE,snapshot);
        });
        return new Seed(project,source,target,sourceSession,targetSession,claim,snapshot);
    }
    Selection freeze(AnnotationConfigApplicationContext context,Seed seed) {
        var selected=context.getBean(ResearchProgressSelectionService.class).select(new SelectionRequest(ALICE,seed.project,seed.targetSession));
        context.getBean(JdbcTemplate.class).update("UPDATE agent_workflow_run SET context_snapshot=?::jsonb WHERE run_id=?",
            canonical(object("prior_progress",selected.priorProgress(),"prior_progress_binding",
                object("project_id",seed.project,"projection_sha256",selected.projectionSha256(),"canonical_bytes",selected.canonicalBytes()))),seed.target);
        return selected;
    }
    ValidationRequest request(Seed seed,Selection selected) { return new ValidationRequest(seed.target,seed.claim,selected.projectionSha256()); }
    void rejects(String code,Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ResearchMemoryException.class,e->assertThat(e.code()).isEqualTo("RESEARCH_MEMORY_"+code));
    }
    @Test void historicalReadIsOwnedAndRevalidatedAfterServiceRestartWithoutAnySourceGrantOrTask() {
        Seed seed;Selection selected;
        try(var context=new AnnotationConfigApplicationContext(Application.class)) {
            seed=seed(context);selected=freeze(context,seed);var service=context.getBean(ResearchProgressSelectionService.class);
            assertThat(selected.priorProgress().path("records").get(0).path("snapshot")).isEqualTo(seed.snapshot);
            var validated=service.validate(request(seed,selected));assertThat(validated.checkedAt()).isNotNull();
            assertThat(validated.projectId()).isEqualTo(seed.project);
            for(var foreign:List.of(new AuthPrincipal("tenant","bob",List.of()),new AuthPrincipal("other","alice",List.of())))
                rejects("NOT_FOUND",()->service.select(new SelectionRequest(foreign,seed.project,seed.targetSession)));
            var other=seed(context);
            rejects("NOT_FOUND",()->service.select(new SelectionRequest(ALICE,seed.project,other.targetSession)));
            rejects("UNAVAILABLE",()->service.select(new SelectionRequest(ALICE,seed.project,seed.sourceSession)));
            rejects("INVALID",()->service.validate(new ValidationRequest(seed.target,seed.claim,"0".repeat(64))));
        }
        try(var restarted=new AnnotationConfigApplicationContext(Application.class)) {
            assertThat(restarted.getBean(ResearchProgressSelectionService.class).validate(request(seed,selected)).projectionSha256())
                .isEqualTo(selected.projectionSha256());
        }
    }
    @Test void deletionContentChangeClaimRotationCancelGrantRevocationAndExpiryRejectNewUse() {
        try(var context=new AnnotationConfigApplicationContext(Application.class)) {
            var db=context.getBean(JdbcTemplate.class);var service=context.getBean(ResearchProgressSelectionService.class);
            for(String change:List.of("delete","content","claim","cancel","grant","deadline","lease","status")) {
                var seed=seed(context);var selected=freeze(context,seed);
                switch(change) {
                    case "delete" -> db.update("DELETE FROM research_progress_memory WHERE run_id=?",seed.source);
                    case "content" -> db.update("UPDATE research_progress_memory SET payload=jsonb_set(payload,'{original_goal}','\"changed\"') WHERE run_id=?",seed.source);
                    case "claim" -> db.update("UPDATE agent_workflow_run SET claim_token=? WHERE run_id=?",UUID.randomUUID(),seed.target);
                    case "cancel" -> db.update("UPDATE agent_workflow_run SET cancel_requested=true WHERE run_id=?",seed.target);
                    case "grant" -> db.update("UPDATE agent_workflow_grant SET revoked_at=clock_timestamp() WHERE run_id=?",seed.target);
                    case "deadline" -> db.update("UPDATE agent_workflow_run SET deadline_at=clock_timestamp()-interval '1 second' WHERE run_id=?",seed.target);
                    case "lease" -> db.update("UPDATE agent_workflow_run SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=?",seed.target);
                    case "status" -> db.update("UPDATE agent_workflow_run SET status='FAILED' WHERE run_id=?",seed.target);
                }
                rejects(change.equals("delete") || change.equals("content")?"REVOKED":"CLAIM_INVALID",()->service.validate(request(seed,selected)));
            }
        }
    }
    @Test void existingClaimReferenceChangesRejectAndCreateTransactionRollsBack() {
        try(var context=new AnnotationConfigApplicationContext(Application.class)) {
            var seed=seed(context);var db=context.getBean(JdbcTemplate.class);var repository=context.getBean(ResearchProgressRepository.class);
            var service=context.getBean(ResearchProgressSelectionService.class);
            var claim=object("record_type","Claim","schema_version","0.1.0","tenant_id","tenant","owner_id","alice",
                "project_id",seed.project,"run_id",seed.source,"claim_id","claim","decision_status","contested","freshness","fresh");
            insertClaim(db,seed,claim,1);
            var snapshot=seed.snapshot.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)snapshot).set("source_claims",JSON.valueToTree(repository.claimStates(seed.project,seed.source,ALICE)));
            repository.save(seed.project,seed.source,ALICE,snapshot);
            var selected=freeze(context,seed);service.validate(request(seed,selected));
            ((com.fasterxml.jackson.databind.node.ObjectNode)claim).put("freshness","stale");insertClaim(db,seed,claim,2);
            rejects("REVOKED",()->service.validate(request(seed,selected)));
            String failedSession="rollback-"+UUID.randomUUID();
            rejects("UNAVAILABLE",()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{
                db.update("INSERT INTO agent_session(session_id,user_id,title) VALUES (?,'tenant:alice','rollback')",failedSession);
                service.select(new SelectionRequest(ALICE,seed.project,failedSession));
            }));
            assertThat(db.queryForObject("SELECT count(*) FROM agent_session WHERE session_id=?",Integer.class,failedSession)).isZero();
        }
    }
    void insertClaim(JdbcTemplate db,Seed seed,JsonNode claim,int version) {
        db.update("""
            INSERT INTO agent_evidence_record(tenant_id,owner_id,project_id,record_type,record_id,version,run_id,payload,payload_sha256)
            VALUES ('tenant','alice',?,'Claim','claim',?,?,?::jsonb,?)
            """,seed.project,version,seed.source,canonical(claim),sha(canonical(claim)));
    }
    @Test void targetLockWaitUsesWallClockAfterWaitingRatherThanTransactionStartTime() throws Exception {
        try(var context=new AnnotationConfigApplicationContext(Application.class)) {
            var seed=seed(context);var selected=freeze(context,seed);var db=context.getBean(JdbcTemplate.class);
            var executor=Executors.newSingleThreadExecutor();
            try(var lock=context.getBean(DataSource.class).getConnection()) {
                lock.setAutoCommit(false);
                try(var statement=lock.prepareStatement("UPDATE agent_workflow_run SET lease_until=clock_timestamp()+interval '1 second' WHERE run_id=?")) {
                    statement.setString(1,seed.target);statement.executeUpdate();
                }
                var started=new CountDownLatch(1);
                Future<?> result=executor.submit(()->{started.countDown();rejects("CLAIM_INVALID",()->context.getBean(ResearchProgressSelectionService.class).validate(request(seed,selected)));});
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
                while(System.nanoTime()<deadline) {
                    blocked=db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%context_snapshot::text AS context%')",Boolean.class);
                    if(blocked) break;
                    Thread.sleep(20);
                }
                assertThat(blocked).isTrue();
                Thread.sleep(1200);lock.commit();result.get(10,TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
        }
    }
}

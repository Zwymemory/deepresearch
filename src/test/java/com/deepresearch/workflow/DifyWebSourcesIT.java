package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real V1-V16 migration/receipt checks without starting Elasticsearch or a provider. */
@Testcontainers
class DifyWebSourcesIT {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("deepresearch").withUsername("deepresearch").withPassword("deepresearch")
            .withInitScript("init-workflow-role.sql");
    static JdbcTemplate jdbc;
    static WorkflowRepository repository;
    static TransactionTemplate tx;
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void migrate() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        repository = new WorkflowRepository(jdbc, JSON);
        jdbc.update("INSERT INTO agent_session(session_id,user_id,title) VALUES ('sess-web-it','tenant-it:user-it','web')");
    }

    @Test
    void snapshotRequiresCompletedReceiptAndExactRunAndMigrationRejectsRawUrls() throws Exception {
        String run = "wf-web-snapshot-it", other = "wf-web-other-it", call = run + ":initial:1";
        createRun(run); createRun(other);
        String id = DifyWebEvidence.id("https://example.com/page", "Page", "Typed search summary");
        assertThat(tx.<Boolean>execute(s -> repository.beginDifyToolCall(run, call, "web_search", "f".repeat(64)))).isTrue();
        assertThat(repository.difyWebSource(run, id)).isEmpty();
        var evidence = new DifyToolDtos.Evidence(id, "来源1", "Page", "Typed search summary", true, "https://example.com/page");
        String receipt = JSON.writeValueAsString(new DifyToolDtos.Response(true, "OK", "web_search", List.of(evidence), ""));
        assertThat(tx.<Boolean>execute(s -> repository.completeDifyToolCall(run, call, "web_search", "f".repeat(64), receipt, List.of(id)))).isTrue();
        assertThat(repository.difySources(run)).containsExactly(id);
        assertThat(DifyWebEvidence.valid(repository.difyWebSource(run, id).orElseThrow())).isTrue();
        assertThat(repository.findDifyToolCall(run, call).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(tx.<Boolean>execute(s -> repository.completeDifyToolCall(run, call, "web_search", "f".repeat(64), receipt, List.of(id)))).isFalse();
        // Even an allowlisted identity alone cannot confer another run's receipt provenance.
        jdbc.update("INSERT INTO dify_workflow_source(run_id,citation_id) VALUES (?,?)", other, id);
        assertThat(repository.difyWebSource(other, id)).isEmpty();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dify_workflow_source(run_id,citation_id) VALUES (?,?)",
                other, "https://example.com/model-created-url")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void cancellationDeadlineAndSafeTerminalReasonSurviveTheNewSourceMigration() {
        String cancelled = "wf-web-cancel-it"; createRun(cancelled);
        String call = cancelled + ":initial:1";
        assertThat(tx.<Boolean>execute(s -> repository.beginDifyToolCall(cancelled, call, "web_search", "f".repeat(64)))).isTrue();
        assertThat(repository.cancel(cancelled, "tenant-it:user-it")).isEqualTo(1);
        assertThat(tx.<Boolean>execute(s -> repository.completeDifyToolCall(cancelled, call, "web_search", "f".repeat(64),
                "{\"success\":true,\"code\":\"OK\",\"tool\":\"web_search\",\"evidences\":[]}", List.of()))).isFalse();
        assertThat(repository.finishDify(cancelled, WorkflowStatus.SUCCEEDED, "{}", "{}", null, "late")).isFalse();

        String expired = "wf-web-expired-it"; createRun(expired);
        jdbc.update("UPDATE agent_workflow_run SET deadline_at=now()-interval '1 second' WHERE run_id=?", expired);
        assertThat(repository.finishDify(expired, WorkflowStatus.SUCCEEDED, "{}", "{}", null, "late")).isFalse();
        assertThat(repository.timeoutDify(expired)).isTrue();

        String failure = "wf-web-unconfigured-it"; createRun(failure);
        assertThat(tx.<Boolean>execute(s -> repository.finishDify(failure, WorkflowStatus.FAILED, "{}", "{}",
                "WEB_SEARCH_NOT_CONFIGURED", ""))).isTrue();
        var row = repository.find(failure).orElseThrow();
        assertThat(row.errorCode()).isEqualTo("WEB_SEARCH_NOT_CONFIGURED");
        assertThat(row.errorMessage()).contains("网页搜索尚未配置");
        assertThat(repository.eventsAfter(failure, 0, 20)).anyMatch(e -> e.payload().toString().contains("WEB_SEARCH_NOT_CONFIGURED"));
    }

    @Test
    void knowledgePresentationMetadataRequiresThisRunsCompletedAuthorizedReceipt() throws Exception {
        String run = "wf-kb-metadata-it", other = "wf-kb-other-it", call = run + ":initial:1";
        createRun(run); createRun(other);
        String id = "kb:ragflow:dataset:document:chunk";
        assertThat(tx.<Boolean>execute(s -> repository.beginDifyToolCall(run, call, "kb_search", "b".repeat(64)))).isTrue();
        assertThat(repository.difyKbSource(run, id)).isEmpty();
        var evidence = new DifyToolDtos.Evidence(id, "来源1", "Actual document.md", "Actual chunk", true);
        String receipt = JSON.writeValueAsString(new DifyToolDtos.Response(true, "OK", "kb_search", List.of(evidence), ""));
        assertThat(tx.<Boolean>execute(s -> repository.completeDifyToolCall(run, call, "kb_search", "b".repeat(64),
                receipt, List.of(id)))).isTrue();
        assertThat(repository.difyKbSource(run, id).orElseThrow().title()).isEqualTo("Actual document.md");
        assertThat(repository.difyKbSource(run, id).orElseThrow().content()).isEqualTo("Actual chunk");
        jdbc.update("INSERT INTO dify_workflow_source(run_id,citation_id) VALUES (?,?)", other, id);
        assertThat(repository.difyKbSource(other, id)).isEmpty();
        jdbc.update("UPDATE agent_workflow_run SET requested_scopes=ARRAY['web_search'] WHERE run_id=?", run);
        assertThat(repository.difyKbSource(run, id)).isEmpty();
    }

    private void createRun(String id) {
        tx.execute(s -> {
            repository.insertRun(new WorkflowRepository.NewRun(id, "sess-web-it", "tenant-it:user-it", "Question", "{}",
                    "/api/research/workflows", id, "f".repeat(64), id, "DIFY_DISPATCHING", "DIFY_DISPATCHING",
                    OffsetDateTime.now().plusMinutes(2), List.of("kb_search", "web_search"), "grant-" + id));
            repository.insertGrant(new WorkflowRepository.NewGrant("grant-" + id, id, "tenant-it:user-it",
                    List.of("kb_search", "web_search"), 1, OffsetDateTime.now().plusMinutes(2)));
            repository.insertDifyMapping(id);
            jdbc.update("UPDATE dify_workflow_run SET dispatch_state='POSTING' WHERE run_id=?", id);
            repository.bindDify(id, "remote-" + id, "task-" + id);
            return null;
        });
    }
}

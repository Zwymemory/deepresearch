package com.deepresearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.deepresearch.workflow.WorkflowRepository;
import com.deepresearch.workflow.WorkflowDelegationContext;
import com.deepresearch.security.AuthPrincipal;

import java.time.OffsetDateTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实基础设施集成测试。
 *
 * 输入是由 Testcontainers 提供的临时 pgvector 与 Elasticsearch；
 * 输出是可用的完整 Spring 上下文。容器启动、迁移或连接失败时测试直接失败，
 * 不回退到开发机上的 5432/9201 服务。
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class DeepResearchApplicationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("deepresearch")
            .withUsername("deepresearch")
            .withPassword("deepresearch")
            .withInitScript("init-workflow-role.sql");

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("xpack.security.http.ssl.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("deepresearch.elasticsearch.url",
                () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("spring.ai.vectorstore.pgvector.index-type", () -> "NONE");
        registry.add("spring.ai.openai.api-key", () -> "integration-test-openai-key");
        registry.add("spring.ai.zhipuai.api-key", () -> "integration-test-zhipuai-key");
        registry.add("tavily.api-key", () -> "integration-test-tavily-key");
        registry.add("deepresearch.rerank.enabled", () -> "false");
        registry.add("deepresearch.security.jwt-secret",
                () -> "integration-test-jwt-secret-with-at-least-32-bytes");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void loadsContextAndMigratesRealInfrastructure() throws Exception {
        assertThat(applicationContext).isNotNull();

        String latestMigration = jdbcTemplate.queryForObject(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success
                ORDER BY installed_rank DESC
                LIMIT 1
                """,
                String.class);
        assertThat(latestMigration).isEqualTo("21");

        Integer coreTableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('kb_document', 'agent_run', 'user_memory', 'agent_idempotency_record',
                                     'agent_workflow_run', 'agent_workflow_event',
                                     'agent_workflow_grant', 'agent_workflow_task_grant',
                                     'agent_workflow_tool_receipt',
                                     'agent_workflow_budget_reservation',
                                     'dify_workflow_run', 'dify_workflow_source',
                                     'dify_workflow_tool_call',
                                     'kb_ragflow_document', 'kb_ragflow_sync_job',
                                     'research_project', 'agent_research_run', 'agent_research_task',
                                     'agent_research_operation', 'agent_research_publication',
                                     'agent_evidence_read_receipt', 'agent_evidence_record',
                                     'agent_evidence_check', 'agent_research_source_validation',
                                     'agent_evidence_blocked_attempt', 'agent_research_criterion',
                                     'agent_research_investigation_progress')
                """, Integer.class);
        assertThat(coreTableCount).isEqualTo(27);

        assertThat(elasticsearchClient.ping().value()).isTrue();
    }

    @Test
    void pgvectorExtensionIsInstalled() {
        Boolean installed = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_extension
                    WHERE extname = 'vector'
                )
                """, Boolean.class);

        assertThat(installed).isTrue();
    }

    @Test
    void workflowIdempotencyAndDurableEventKeysAreEnforcedByPostgres() {
        jdbcTemplate.update("""
                INSERT INTO agent_session (session_id, user_id, title)
                VALUES ('sess-workflow-it', 'tenant-it:user-it', 'workflow test')
                ON CONFLICT (session_id) DO NOTHING
                """);
        WorkflowRepository.NewRun first = new WorkflowRepository.NewRun(
                "wf-it-1", "sess-workflow-it", "tenant-it:user-it", "question", "{}",
                "/api/research/workflows", "idem-key-it-0001", "a".repeat(64), "wf-it-1",
                "QUEUED", "QUEUED", OffsetDateTime.now().plusMinutes(2),
                List.of("kb_search"), "grant-it-1");
        WorkflowRepository.NewRun duplicate = new WorkflowRepository.NewRun(
                "wf-it-2", "sess-workflow-it", "tenant-it:user-it", "question", "{}",
                "/api/research/workflows", "idem-key-it-0001", "a".repeat(64), "wf-it-2",
                "QUEUED", "QUEUED", OffsetDateTime.now().plusMinutes(2),
                List.of("kb_search"), "grant-it-2");

        assertThat(insertRunWithCanonicalGrant(first)).isEqualTo(1);
        assertThat(insertRunWithCanonicalGrant(duplicate)).isZero();
        assertThat(workflowRepository.insertEvent(
                "wf-it-1", "workflow:queued", "SYSTEM", null, "QUEUED", "{}" )).isTrue();
        assertThat(workflowRepository.insertEvent(
                "wf-it-1", "workflow:queued", "SYSTEM", null, "QUEUED", "{}" )).isFalse();
        assertThat(workflowRepository.eventsAfter("wf-it-1", 0, 10)).hasSize(1);

        WorkflowRepository.NewRun concurrentA = new WorkflowRepository.NewRun(
                "wf-it-concurrent-a", "sess-workflow-it", "tenant-it:user-it", "question", "{}",
                "/api/research/workflows", "idem-key-it-concurrent", "b".repeat(64), "wf-it-concurrent-a",
                "QUEUED", "QUEUED", OffsetDateTime.now().plusMinutes(2),
                List.of("kb_search"), "grant-it-concurrent-a");
        WorkflowRepository.NewRun concurrentB = new WorkflowRepository.NewRun(
                "wf-it-concurrent-b", "sess-workflow-it", "tenant-it:user-it", "question", "{}",
                "/api/research/workflows", "idem-key-it-concurrent", "b".repeat(64), "wf-it-concurrent-b",
                "QUEUED", "QUEUED", OffsetDateTime.now().plusMinutes(2),
                List.of("kb_search"), "grant-it-concurrent-b");
        CompletableFuture<Integer> firstInsert = CompletableFuture.supplyAsync(
                () -> insertRunWithCanonicalGrant(concurrentA));
        CompletableFuture<Integer> secondInsert = CompletableFuture.supplyAsync(
                () -> insertRunWithCanonicalGrant(concurrentB));
        assertThat(firstInsert.join() + secondInsert.join()).isEqualTo(1);

        UUID currentClaim = UUID.randomUUID();
        jdbcTemplate.update("""
                UPDATE agent_workflow_run
                SET claim_token = ?, status = 'FINALIZING', stage = 'FINALIZING',
                    lease_until = now() + interval '30 seconds'
                WHERE run_id = 'wf-it-1'
                """, currentClaim);
        assertThat(workflowRepository.finalizeClaim(
                "wf-it-1", UUID.randomUUID(), com.deepresearch.workflow.WorkflowStatus.SUCCEEDED,
                "{}", "{}", null, null, "c".repeat(64))).isZero();
        jdbcTemplate.update("""
                UPDATE agent_workflow_run SET lease_until = now() - interval '1 second'
                WHERE run_id = 'wf-it-1'
                """);
        assertThat(workflowRepository.finalizeClaim(
                "wf-it-1", currentClaim, com.deepresearch.workflow.WorkflowStatus.SUCCEEDED,
                "{}", "{}", null, null, "c".repeat(64))).isZero();
        jdbcTemplate.update("""
                UPDATE agent_workflow_run SET lease_until = now() + interval '30 seconds'
                WHERE run_id = 'wf-it-1'
                """);
        assertThat(workflowRepository.finalizeClaim(
                "wf-it-1", currentClaim, com.deepresearch.workflow.WorkflowStatus.SUCCEEDED,
                "{}", "{}", null, null, "c".repeat(64))).isEqualTo(1);
    }

    @Test
    void workflowSidecarRoleCannotForgeGrantsOwnersOrTerminalState() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO agent_session (session_id, user_id, title)
                VALUES ('sess-workflow-role-it', 'tenant-it:user-it', 'role boundary')
                ON CONFLICT (session_id) DO NOTHING
                """);
        WorkflowRepository.NewRun run = new WorkflowRepository.NewRun(
                "wf-role-it", "sess-workflow-role-it", "tenant-it:user-it", "question", "{}",
                "/api/research/workflows", "idem-role-it", "d".repeat(64), "wf-role-it",
                "QUEUED", "QUEUED", OffsetDateTime.now().plusMinutes(2),
                List.of("kb_search"), "grant-role-it");
        assertThat(insertRunWithCanonicalGrant(run)).isEqualTo(1);
        UUID claim = UUID.randomUUID();
        jdbcTemplate.update("""
                UPDATE agent_workflow_run
                SET claim_token = ?, claimed_by = 'integration-runner',
                    lease_until = now() + interval '30 seconds'
                WHERE run_id = 'wf-role-it'
                """, claim);

        try (Connection sidecar = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "deepresearch_workflow", "workflow-integration-test-password")) {
            assertThat(sidecar.createStatement().executeQuery(
                    "SELECT count(*) FROM agent_workflow_run").next()).isTrue();
            assertThatSqlFails(sidecar,
                    "UPDATE agent_workflow_grant SET subject='tenant-x:user-x' WHERE grant_id='grant-role-it'");
            assertThatSqlFails(sidecar,
                    "UPDATE agent_workflow_run SET user_id='tenant-x:user-x' WHERE run_id='wf-role-it'");
            assertThat(sidecar.createStatement().executeUpdate(
                    "UPDATE agent_workflow_run SET status='PLANNING', stage='PLANNING' "
                            + "WHERE run_id='wf-role-it'")).isEqualTo(1);
            assertThat(sidecar.createStatement().executeUpdate("""
                    INSERT INTO agent_workflow_budget_reservation(
                        run_id, operation_key, attempt, kind, status,
                        claim_token, origin_claim_token
                    ) VALUES (
                        'wf-role-it', 'model:planner', 1, 'MODEL', 'RESERVED',
                        '%s'::uuid, '%s'::uuid
                    )
                    """.formatted(claim, claim))).isEqualTo(1);
            assertThatSqlFails(sidecar, """
                    INSERT INTO agent_workflow_budget_reservation(
                        run_id, operation_key, attempt, kind, status,
                        claim_token, origin_claim_token
                    ) VALUES (
                        'wf-role-it', 'model:stale', 1, 'MODEL', 'RESERVED',
                        '%s'::uuid, '%s'::uuid
                    )
                    """.formatted(UUID.randomUUID(), UUID.randomUUID()));
            assertThatSqlFails(sidecar, """
                    INSERT INTO agent_workflow_budget_reservation(
                        run_id, operation_key, attempt, kind, status,
                        claim_token, origin_claim_token
                    ) VALUES (
                        'wf-role-it', 'tool-wrong-kind', 1, 'MODEL', 'RESERVED',
                        '%s'::uuid, '%s'::uuid
                    )
                    """.formatted(claim, claim));
            assertThatSqlFails(sidecar, """
                    UPDATE agent_workflow_budget_reservation
                    SET operation_key='model:forged'
                    WHERE run_id='wf-role-it' AND operation_key='model:planner'
                    """);
            // Failure/timeout/cancellation may be handed to Java from every active stage.
            assertThat(sidecar.createStatement().executeUpdate(
                    "UPDATE agent_workflow_run SET status='FINALIZING', stage='FINALIZING' "
                            + "WHERE run_id='wf-role-it'")).isEqualTo(1);
            assertThatSqlFails(sidecar,
                    "UPDATE agent_workflow_run SET status='WORKING', stage='WORKING' WHERE run_id='wf-role-it'");
            assertThatSqlFails(sidecar,
                    "UPDATE agent_workflow_run SET status='SUCCEEDED', stage='SUCCEEDED' WHERE run_id='wf-role-it'");
        }
    }

    @Test
    void workflowSidecarCannotBypassChildFenceWithTemporaryShadowTable() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO agent_session (session_id, user_id, title)
                VALUES ('sess-workflow-shadow-it', 'tenant-it:user-it', 'shadow fence')
                ON CONFLICT (session_id) DO NOTHING
                """);
        WorkflowRepository.NewRun run = new WorkflowRepository.NewRun(
                "wf-shadow-it", "sess-workflow-shadow-it", "tenant-it:user-it", "question", "{}",
                "/api/research/workflows", "idem-shadow-it", "9".repeat(64), "wf-shadow-it",
                "QUEUED", "QUEUED", OffsetDateTime.now().plusMinutes(2),
                List.of("kb_search"), "grant-shadow-it");
        assertThat(insertRunWithCanonicalGrant(run)).isEqualTo(1);

        UUID staleClaim = UUID.randomUUID();
        jdbcTemplate.update("""
                UPDATE agent_workflow_run
                SET claim_token = ?, claimed_by = 'shadow-runner', status = 'WORKING', stage = 'WORKING',
                    lease_until = now() + interval '30 seconds'
                WHERE run_id = 'wf-shadow-it'
                """, staleClaim);

        try (Connection sidecar = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "deepresearch_workflow", "workflow-integration-test-password")) {
            sidecar.createStatement().execute("""
                    CREATE TEMP TABLE agent_workflow_run (
                        run_id varchar(64), claim_token uuid, lease_until timestamptz,
                        cancel_requested boolean, status varchar(32)
                    )
                    """);
            sidecar.createStatement().executeUpdate("""
                    INSERT INTO agent_workflow_run
                    VALUES ('wf-shadow-it', '%s'::uuid, now() + interval '1 hour', false, 'WORKING')
                    """.formatted(staleClaim));

            UUID currentClaim = UUID.randomUUID();
            jdbcTemplate.update("""
                    UPDATE public.agent_workflow_run
                    SET claim_token = ?, lease_until = now() + interval '30 seconds'
                    WHERE run_id = 'wf-shadow-it'
                    """, currentClaim);

            assertThatSqlFails(sidecar, """
                    INSERT INTO public.agent_workflow_event(
                        run_id, event_key, type, safe_payload, claim_token
                    ) VALUES (
                        'wf-shadow-it', 'shadow-event', 'TEST', '{}'::jsonb, '%s'::uuid
                    )
                    """.formatted(staleClaim));
            assertThatSqlFails(sidecar, """
                    INSERT INTO public.agent_workflow_budget_reservation(
                        run_id, operation_key, attempt, kind, status,
                        claim_token, origin_claim_token
                    ) VALUES (
                        'wf-shadow-it', 'model:shadow', 1, 'MODEL', 'RESERVED',
                        '%s'::uuid, '%s'::uuid
                    )
                    """.formatted(staleClaim, staleClaim));
        }
    }

    @Test
    void javaMcpReceiptReplaysCompletedResultAndFencesStaleClaims() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO agent_session (session_id, user_id, title)
                VALUES ('sess-mcp-receipt-it', 'tenant-it:user-it', 'mcp receipt')
                ON CONFLICT (session_id) DO NOTHING
                """);
        WorkflowRepository.NewRun run = new WorkflowRepository.NewRun(
                "wf-mcp-receipt-it", "sess-mcp-receipt-it", "tenant-it:user-it", "question", "{}",
                "/api/research/workflows", "idem-mcp-receipt-it", "e".repeat(64), "wf-mcp-receipt-it",
                "QUEUED", "QUEUED", OffsetDateTime.now().plusMinutes(2),
                List.of("kb_search"), "grant-mcp-receipt-it");
        assertThat(insertRunWithCanonicalGrant(run)).isEqualTo(1);

        UUID claim = UUID.randomUUID();
        jdbcTemplate.update("""
                UPDATE agent_workflow_run
                SET claim_token = ?, claimed_by = 'integration-runner', status = 'WORKING', stage = 'WORKING',
                    lease_until = now() + interval '30 seconds'
                WHERE run_id = 'wf-mcp-receipt-it'
                """, claim);
        assertThat(workflowRepository.bindTaskGrant(
                "grant-mcp-receipt-it", "wf-mcp-receipt-it", "task-1", claim,
                List.of("kb_search"), OffsetDateTime.now().plusMinutes(1))).isTrue();

        String fingerprint = "f".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO agent_workflow_tool_receipt
                    (run_id, call_id, task_id, tool_name, request_fingerprint, status, claim_token)
                VALUES ('wf-mcp-receipt-it', 'tool-call-it-0001', 'task-1', 'kb_search', ?, 'STARTED', ?)
                """, fingerprint, claim);
        WorkflowDelegationContext context = new WorkflowDelegationContext(
                new AuthPrincipal("tenant-it", "user-it", List.of("USER")),
                "wf-mcp-receipt-it", "grant-mcp-receipt-it", "task-1", claim.toString(),
                java.util.Set.of("kb_search"), "tool-call-it-0001");

        assertThat(workflowRepository.activeMcpExecutionBinding(context, claim)).isTrue();
        assertThat(workflowRepository.beginMcpToolExecution(
                context, claim, "tool-call-it-0001", "kb_search", fingerprint)).isTrue();
        assertThat(workflowRepository.beginMcpToolExecution(
                context, claim, "tool-call-it-0001", "kb_search", fingerprint)).isFalse();
        assertThat(workflowRepository.completeMcpToolExecution(
                context, claim, "tool-call-it-0001", "kb_search", fingerprint,
                "{\"success\":true,\"code\":\"OK\",\"tool\":\"kb_search\",\"evidence\":[]}"))
                .isTrue();
        WorkflowRepository.McpToolExecutionRow completed = workflowRepository
                .findMcpToolExecution("wf-mcp-receipt-it", "tool-call-it-0001").orElseThrow();
        assertThat(completed.executionStatus()).isEqualTo("COMPLETED");
        assertThat(completed.safeResultJson()).contains("\"code\": \"OK\"");

        try (Connection sidecar = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "deepresearch_workflow", "workflow-integration-test-password")) {
            assertThat(sidecar.createStatement().executeUpdate("""
                    UPDATE agent_workflow_tool_receipt
                    SET status='COMPLETED', safe_result='{"call_id":"tool-call-it-0001","evidence":[]}'::jsonb,
                        completed_at=now()
                    WHERE run_id='wf-mcp-receipt-it' AND call_id='tool-call-it-0001'
                    """)).isEqualTo(1);
            assertThatSqlFails(sidecar, """
                    UPDATE agent_workflow_tool_receipt
                    SET mcp_safe_result='{}'::jsonb
                    WHERE run_id='wf-mcp-receipt-it' AND call_id='tool-call-it-0001'
                    """);
        }

        jdbcTemplate.update("""
                INSERT INTO agent_workflow_tool_receipt
                    (run_id, call_id, task_id, tool_name, request_fingerprint, status, claim_token)
                VALUES ('wf-mcp-receipt-it', 'tool-call-it-0002', 'task-1', 'kb_search', ?, 'STARTED', ?)
                """, fingerprint, claim);
        WorkflowDelegationContext second = new WorkflowDelegationContext(
                context.principal(), context.runId(), context.grantId(), context.taskId(),
                context.claimToken(), context.scopes(), "tool-call-it-0002");
        assertThat(workflowRepository.beginMcpToolExecution(
                second, claim, "tool-call-it-0002", "kb_search", fingerprint)).isTrue();
        jdbcTemplate.update("""
                UPDATE agent_workflow_run
                SET claim_token = ?, lease_until = now() + interval '30 seconds'
                WHERE run_id = 'wf-mcp-receipt-it'
                """, UUID.randomUUID());
        assertThat(workflowRepository.activeMcpExecutionBinding(second, claim)).isFalse();
        assertThat(workflowRepository.completeMcpToolExecution(
                second, claim, "tool-call-it-0002", "kb_search", fingerprint,
                "{\"success\":true,\"code\":\"OK\",\"tool\":\"kb_search\",\"evidence\":[]}"))
                .isFalse();
    }

    @Test
    void difyStateAndToolReceiptFencesAreEnforcedByPostgres() {
        jdbcTemplate.update("""
                INSERT INTO agent_session (session_id, user_id, title)
                VALUES ('sess-dify-it', 'tenant-it:user-it', 'dify state')
                ON CONFLICT (session_id) DO NOTHING
                """);

        String pending = "wf-dify-pending-it";
        insertDifyRun(pending, "idem-dify-pending-it", OffsetDateTime.now().minusSeconds(1));
        assertThat(workflowRepository.timeoutPendingDify()).contains(pending);
        assertThat(workflowRepository.find(pending).orElseThrow().status()).isEqualTo("TIMED_OUT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM agent_workflow_grant WHERE run_id = ?",
                Boolean.class, pending)).isTrue();

        String unknown = "wf-dify-unknown-it";
        insertDifyRun(unknown, "idem-dify-unknown-it", OffsetDateTime.now().plusMinutes(2));
        jdbcTemplate.update("UPDATE dify_workflow_run SET dispatch_state = 'POSTING' WHERE run_id = ?", unknown);
        assertThat(workflowRepository.unknownDifyDispatch(unknown)).isTrue();
        assertThat(workflowRepository.unknownDifyDispatch(unknown)).isFalse();
        WorkflowRepository.RunRow unknownRow = workflowRepository.find(unknown).orElseThrow();
        assertThat(unknownRow.status()).isEqualTo("DISPATCH_UNKNOWN");
        assertThat(unknownRow.cancelRequested()).isTrue();
        assertThat(workflowRepository.difyMapping(unknown).orElseThrow().dispatchState())
                .isEqualTo("UNKNOWN");
        workflowRepository.bindDify(unknown, "remote-unknown-late-it", "task-unknown-late-it");
        WorkflowRepository.DifyMapping lateUnknown = workflowRepository.difyMapping(unknown).orElseThrow();
        assertThat(lateUnknown.dispatchState()).isEqualTo("UNKNOWN");
        assertThat(lateUnknown.taskId()).isEqualTo("task-unknown-late-it");
        assertThat(workflowRepository.find(unknown).orElseThrow().status()).isEqualTo("DISPATCH_UNKNOWN");

        String expired = "wf-dify-expired-it";
        insertDifyRun(expired, "idem-dify-expired-it", OffsetDateTime.now().plusMinutes(2));
        jdbcTemplate.update("UPDATE dify_workflow_run SET dispatch_state = 'POSTING' WHERE run_id = ?", expired);
        workflowRepository.bindDify(expired, "remote-expired-it", "task-expired-it");
        jdbcTemplate.update("UPDATE agent_workflow_run SET deadline_at = now() - interval '1 second' WHERE run_id = ?",
                expired);
        assertThat(workflowRepository.expiredDifyRuns()).contains(expired);
        assertThat(workflowRepository.timeoutDify(expired)).isTrue();
        assertThat(workflowRepository.timeoutDify(expired)).isFalse();

        String active = "wf-dify-receipt-it";
        insertDifyRun(active, "idem-dify-receipt-it", OffsetDateTime.now().plusMinutes(2));
        jdbcTemplate.update("UPDATE dify_workflow_run SET dispatch_state = 'POSTING' WHERE run_id = ?", active);
        workflowRepository.bindDify(active, "remote-receipt-it", "task-receipt-it");
        String callId = active + ":initial:1";
        String fingerprint = "f".repeat(64);
        CompletableFuture<Boolean> firstBegin = CompletableFuture.supplyAsync(
                () -> workflowRepository.beginDifyToolCall(active, callId, "kb_search", fingerprint));
        CompletableFuture<Boolean> secondBegin = CompletableFuture.supplyAsync(
                () -> workflowRepository.beginDifyToolCall(active, callId, "kb_search", fingerprint));
        assertThat(List.of(firstBegin.join(), secondBegin.join()).stream().filter(Boolean::booleanValue).count())
                .isEqualTo(1);
        String source = "kb:ragflow:dataset-it:document-it:chunk-it";
        String safeResult = "{\"success\":true,\"code\":\"OK\",\"tool\":\"kb_search\",\"evidences\":[]}";
        assertThat(workflowRepository.completeDifyToolCall(active, callId, "kb_search", fingerprint,
                safeResult, List.of(source))).isTrue();
        WorkflowRepository.DifyToolCall completed = workflowRepository
                .findDifyToolCall(active, callId).orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.safeResultJson()).contains("\"code\": \"OK\"");
        assertThat(workflowRepository.difySources(active)).containsExactly(source);
        assertThat(workflowRepository.completeDifyToolCall(active, callId, "kb_search", fingerprint,
                safeResult, List.of(source))).isFalse();

        String lateCall = active + ":revision:1";
        assertThat(workflowRepository.beginDifyToolCall(active, lateCall, "kb_search", fingerprint)).isTrue();
        assertThat(workflowRepository.cancel(active, "tenant-it:user-it")).isEqualTo(1);
        assertThat(workflowRepository.difyStopState(active)).contains("PENDING");
        WorkflowRepository.DifyStopWork stop = workflowRepository.claimDifyStops(10).stream()
                .filter(work -> active.equals(work.runId())).findFirst().orElseThrow();
        assertThat(stop.taskId()).isEqualTo("task-receipt-it");
        assertThat(stop.attempts()).isEqualTo(1);
        jdbcTemplate.update("UPDATE dify_workflow_run SET stop_lease_until=now()-interval '1 second' WHERE run_id=?",
                active);
        WorkflowRepository.DifyStopWork recovered = workflowRepository.claimDifyStops(10).stream()
                .filter(work -> active.equals(work.runId())).findFirst().orElseThrow();
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(workflowRepository.completeDifyStop(stop, "REQUESTED", null)).isFalse();
        assertThat(workflowRepository.completeDifyStop(recovered, "REQUESTED", null)).isTrue();
        assertThat(workflowRepository.difyStopState(active)).contains("REQUESTED");
        workflowRepository.revokeGrantForRun(active);
        assertThat(workflowRepository.completeDifyToolCall(active, lateCall, "kb_search", fingerprint,
                safeResult, List.of(source))).isFalse();
        assertThat(workflowRepository.finishDify(active, com.deepresearch.workflow.WorkflowStatus.SUCCEEDED,
                "{\"answer\":\"late [来源1]\",\"citations\":[\"" + source + "\"]}", "{}", null,
                "late [来源1]")).isFalse();

        List<String> rotating = new java.util.ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            String runId = "wf-dify-rotate-it-" + index;
            rotating.add(runId);
            insertDifyRun(runId, "idem-dify-rotate-it-" + index, OffsetDateTime.now().plusMinutes(2));
            jdbcTemplate.update("UPDATE dify_workflow_run SET dispatch_state = 'POSTING' WHERE run_id = ?", runId);
            workflowRepository.bindDify(runId, "remote-rotate-it-" + index, "task-rotate-it-" + index);
            jdbcTemplate.update("UPDATE dify_workflow_run SET updated_at = now() - interval '1 minute' WHERE run_id = ?",
                    runId);
        }
        List<String> claimed = new java.util.ArrayList<>();
        claimed.addAll(workflowRepository.claimBoundDifyRuns(2));
        claimed.addAll(workflowRepository.claimBoundDifyRuns(2));
        claimed.addAll(workflowRepository.claimBoundDifyRuns(2));
        assertThat(claimed).containsAll(rotating);
        assertThat(claimed).doesNotHaveDuplicates();
    }

    private int insertRunWithCanonicalGrant(WorkflowRepository.NewRun run) {
        Integer inserted = new TransactionTemplate(transactionManager).execute(status -> {
            int changed = workflowRepository.insertRun(run);
            if (changed == 1) {
                workflowRepository.insertGrant(new WorkflowRepository.NewGrant(
                        run.grantId(), run.runId(), run.userId(), run.requestedScopes(), 1,
                        run.deadlineAt()));
            }
            return changed;
        });
        return inserted == null ? 0 : inserted;
    }

    private void insertDifyRun(String runId, String idempotencyKey, OffsetDateTime deadlineAt) {
        WorkflowRepository.NewRun run = new WorkflowRepository.NewRun(
                runId, "sess-dify-it", "tenant-it:user-it", "question", "{}",
                "/api/research/workflows", idempotencyKey, "d".repeat(64), runId,
                "DIFY_DISPATCHING", "DIFY_DISPATCHING", deadlineAt,
                List.of("kb_search"), "grant-" + runId);
        assertThat(insertRunWithCanonicalGrant(run)).isEqualTo(1);
        workflowRepository.insertDifyMapping(runId);
    }

    private void assertThatSqlFails(Connection connection, String sql) {
        assertThatThrownBy(() -> connection.createStatement().executeUpdate(sql))
                .isInstanceOf(SQLException.class);
    }
}

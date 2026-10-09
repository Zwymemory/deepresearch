package com.deepresearch;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the complete migration chain on a disposable database. */
@Testcontainers
class FlywayMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("deepresearch")
            .withUsername("deepresearch")
            .withPassword("deepresearch");

    @Test
    void appliesCompleteMigrationChainThroughV26OnCleanDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate db = new JdbcTemplate(dataSource);
        assertThat(db.queryForList("""
                SELECT version FROM flyway_schema_history
                WHERE success ORDER BY installed_rank
                """, String.class)).containsExactlyElementsOf(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26"));
        assertThat(db.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                    'dify_workflow_run', 'dify_workflow_source', 'dify_workflow_tool_call',
                    'kb_ragflow_document', 'kb_ragflow_sync_job',
                    'research_project', 'agent_research_run', 'agent_research_task',
                    'agent_research_operation', 'agent_research_publication',
                    'agent_evidence_read_receipt', 'agent_evidence_record', 'agent_evidence_check',
                    'agent_research_source_validation', 'agent_evidence_blocked_attempt',
                    'agent_research_criterion', 'agent_research_investigation_progress',
                    'agent_research_requirements', 'agent_research_requirement_binding')
                ORDER BY table_name
                """, String.class)).containsExactly(
                "agent_evidence_blocked_attempt", "agent_evidence_check",
                "agent_evidence_read_receipt", "agent_evidence_record",
                "agent_research_criterion", "agent_research_investigation_progress",
                "agent_research_operation", "agent_research_publication",
                "agent_research_requirement_binding", "agent_research_requirements",
                "agent_research_run", "agent_research_source_validation", "agent_research_task",
                "dify_workflow_run", "dify_workflow_source", "dify_workflow_tool_call",
                "kb_ragflow_document", "kb_ragflow_sync_job", "research_project");
    }
}

package com.deepresearch.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** Disposable database only: stale summary completion cannot regress the watermark. */
@Testcontainers
class ConversationCompressionPostgresIT {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("deepresearch").withUsername("deepresearch").withPassword("deepresearch")
            .withInitScript("init-workflow-role.sql");
    static AgentSessionRepository repository;

    @BeforeAll
    static void setup() {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        repository = new AgentSessionRepository(new JdbcTemplate(new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())));
    }

    @Test
    void lateOrRepeatedCompletionPreservesTheNewestSummaryAndProgress() {
        repository.upsertOwned("summary-cas", "fixture-owner", "fixture");
        repository.updateSummary("summary-cas", "newer summary", 8);
        repository.updateSummary("summary-cas", "late stale summary", 6);
        repository.updateSummary("summary-cas", "duplicate at same watermark", 8);
        assertThat(repository.summary("summary-cas")).isEqualTo("newer summary");
        assertThat(repository.summaryMessageCount("summary-cas")).isEqualTo(8);
        repository.updateSummary("summary-cas", "next summary", 10);
        assertThat(repository.summary("summary-cas")).isEqualTo("next summary");
        assertThat(repository.summaryMessageCount("summary-cas")).isEqualTo(10);
    }
}

package com.deepresearch.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance scripts use their actual functions against only this disposable migrated DB. */
@Testcontainers
class AgentAcceptanceV22PostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
        .withDatabaseName("deepresearch").withUsername("deepresearch").withPassword("deepresearch")
        .withInitScript("init-workflow-role.sql");

    @Test void pinnedSchemaAndNativeRunExportAreCheckedThroughActualPythonHarness() throws Exception {
        pythonHarness("tests/test_agent_v22_acceptance_postgres.py");
    }
    @Test void segmentAndLegacySettlementsKeepNativeAuditBindingsWithoutSchemaChanges() throws Exception {
        pythonHarness("tests/test_agent_v22_acceptance_postgres.py::test_actual_segment_receipt_persistence_and_safe_audit_reconstruction",
            "tests/test_agent_v22_acceptance_postgres.py::test_actual_run_scoped_native_export_preserves_two_persisted_declarations");
    }
    private void pythonHarness(String... tests) throws Exception {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        var log = Path.of(tests.length>1?"target/segments-acceptance-postgres-python.log":"target/v22-acceptance-postgres-python.log").toAbsolutePath();
        var command=new java.util.ArrayList<String>(java.util.List.of(System.getenv().getOrDefault("AGENT_PYTHON", "python3"), "-B", "-m", "pytest"));
        command.addAll(java.util.List.of(tests));command.addAll(java.util.List.of("-q","-p","no:cacheprovider"));
        var builder = new ProcessBuilder(command)
            .directory(Path.of("workflow-service").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("PYTHONPATH", Path.of("workflow-service/src").toAbsolutePath().toString());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.environment().put("AGENT_TEST_ISOLATED", "1");
        builder.environment().put("TEST_AGENT_DATABASE_URL", "postgresql://" + PG.getUsername() + ":" + PG.getPassword()
                + "@" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + PG.getDatabaseName());
        var process = builder.start();
        assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("bounded acceptance harness timeout").isTrue();
        assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
    }
}

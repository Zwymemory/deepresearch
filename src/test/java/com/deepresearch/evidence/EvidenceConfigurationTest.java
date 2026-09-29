package com.deepresearch.evidence;

import com.deepresearch.service.RagflowClient;
import com.deepresearch.service.RagflowDocumentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EvidenceConfiguration.class, EvidenceController.class);

    @Test void newPathIsAbsentByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EvidenceService.class);
            assertThat(context).doesNotHaveBean(EvidenceController.class);
        });
    }

    @Test void enablingWithoutControlPlaneDeniesBeforeDatabaseOrProviderAccess() {
        var db = mock(JdbcTemplate.class);
        var provider = mock(RagflowClient.class);
        var registry = mock(RagflowDocumentRegistry.class);
        runner.withPropertyValues("deepresearch.agent.evidence.enabled=true")
                .withBean(JdbcTemplate.class, () -> db)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(RagflowClient.class, () -> provider)
                .withBean(RagflowDocumentRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EvidenceService.class)
                            .hasSingleBean(EvidenceController.class);
                    clearInvocations(db, provider, registry); // exclude Spring bean initialization
                    var ids = new EvidenceDtos.Identifiers("project", "run", "task", "call", UUID.randomUUID().toString());
                    assertThatThrownBy(() -> context.getBean(EvidenceService.class)
                            .read("untrusted-header", new EvidenceDtos.ReadRequest(ids, "source")))
                            .hasMessageContaining("EVIDENCE_ACCESS_DENIED");
                    verifyNoInteractions(db, provider, registry);
                });
    }
}

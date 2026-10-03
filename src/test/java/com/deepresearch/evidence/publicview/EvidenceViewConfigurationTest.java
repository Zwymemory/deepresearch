package com.deepresearch.evidence.publicview;

import org.junit.jupiter.api.Test;
import com.deepresearch.service.UserContextService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Disabled reader must not acquire database/transaction dependencies or leave a partial route. */
class EvidenceViewConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EvidenceViewController.class, EvidenceViewService.class, EvidenceViewQuery.class,
                    EvidenceViewUnavailableController.class, UserContextService.class);

    @Test void readerIsAbsentByDefaultWithoutAnyDatabaseOrWorkflowBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(EvidenceViewUnavailableController.class).doesNotHaveBean(EvidenceViewController.class)
                    .doesNotHaveBean(EvidenceViewService.class).doesNotHaveBean(EvidenceViewQuery.class)
                    .doesNotHaveBean(JdbcTemplate.class).doesNotHaveBean(PlatformTransactionManager.class);
        });
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false"})
    void eitherDisabledFlagRemovesAllThreeComponentsWithoutCreatingDependencies(boolean workflow, boolean evidence) {
        runner.withPropertyValues("deepresearch.workflow.enabled=" + workflow, "deepresearch.agent.evidence.enabled=" + evidence)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(EvidenceViewUnavailableController.class).doesNotHaveBean(EvidenceViewController.class)
                        .doesNotHaveBean(EvidenceViewService.class).doesNotHaveBean(EvidenceViewQuery.class)
                        .doesNotHaveBean(PlatformTransactionManager.class).doesNotHaveBean(JdbcTemplate.class));
    }
}

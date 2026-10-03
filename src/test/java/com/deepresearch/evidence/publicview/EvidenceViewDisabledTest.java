package com.deepresearch.evidence.publicview;

import com.deepresearch.security.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real application/security configuration, existing no-data-source profile, no transaction-manager mock. */
@SpringBootTest(properties = {"deepresearch.workflow.enabled=false", "deepresearch.agent.evidence.enabled=false", "spring.config.import="})
@AutoConfigureMockMvc
@ActiveProfiles("unit-test")
class EvidenceViewDisabledTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc http;
    @Autowired JwtTokenService tokens;
    // Existing application repositories need JdbcTemplate; these do not provide a database or transaction manager.
    @MockitoBean JdbcTemplate db;
    @MockitoBean VectorStore vectors;

    @Test void disabledReaderReturnsExplicit503AndAuthenticationStillAppliesWithoutTransactionManager() throws Exception {
        assertThat(context.getBeansOfType(PlatformTransactionManager.class)).isEmpty();
        assertThat(context.getBeansOfType(EvidenceViewService.class)).isEmpty();
        assertThat(context.getBeansOfType(EvidenceViewQuery.class)).isEmpty();
        assertThat(context.getBeansOfType(EvidenceViewController.class)).isEmpty();
        assertThat(context.getBeansOfType(EvidenceViewUnavailableController.class)).hasSize(1);
        clearInvocations(db, vectors);
        String path = "/api/research/workflows/run-disabled/evidence";
        http.perform(get(path)).andExpect(status().isUnauthorized());
        String authorization = tokens.issue("tenant-disabled", "user-disabled", List.of("USER"), 60L).authorizationHeader();
        http.perform(get(path).header("Authorization", authorization)).andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("{\"errorCode\":\"EVIDENCE_VIEW_DISABLED\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        verifyNoInteractions(db, vectors);
    }
}

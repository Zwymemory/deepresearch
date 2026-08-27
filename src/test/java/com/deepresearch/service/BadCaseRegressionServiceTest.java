package com.deepresearch.service;

import com.deepresearch.web.dto.AgentBadCaseResponse;
import com.deepresearch.web.dto.AgentHarnessRequest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BadCaseRegressionServiceTest {

    @Test
    void convertsGroundingBadCaseToReviewDraftWithoutFakeCitationAssertion() {
        AgentStateService state = mock(AgentStateService.class);
        when(state.listBadCases(10)).thenReturn(List.of(new AgentBadCaseResponse(
                "feedback", "run-1", "session", "tenant:user", "问题", "旧答案",
                "DOWN", "NOT_GROUNDED", "缺少引用", OffsetDateTime.now())));

        AgentHarnessRequest request = new BadCaseRegressionService(state)
                .convert(10, List.of("manual-react", "native-tool-calling"));

        assertThat(request.cases()).singleElement().satisfies(testCase -> {
            assertThat(testCase.id()).isEqualTo("badcase-run-1");
            assertThat(testCase.expectedCitationMarkers()).isEmpty();
            assertThat(testCase.forbiddenTools()).contains("shell", "exec", "write_file");
            assertThat(testCase.securityCase()).isFalse();
            assertThat(testCase.category()).isEqualTo("bad-case-draft");
            assertThat(testCase.tags()).contains("needs-review", "grounding");
            assertThat(testCase.critical()).isFalse();
        });
    }

    @Test
    void keepsEmptyBadCasesExplicitInsteadOfFallingBackToMissingDataset() {
        AgentStateService state = mock(AgentStateService.class);
        when(state.listBadCases(10)).thenReturn(List.of());

        AgentHarnessRequest request = new BadCaseRegressionService(state).convert(10, null);

        assertThat(request.dataset()).isEqualTo("bad-cases-draft");
        assertThat(request.cases()).isEmpty();
        assertThat(request.limit()).isNull();
    }
}

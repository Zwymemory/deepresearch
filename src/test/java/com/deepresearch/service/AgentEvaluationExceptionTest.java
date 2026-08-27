package com.deepresearch.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvaluationExceptionTest {

    @Test
    void classifiesWrappedTimeoutWithoutExposingProviderMessage() {
        RuntimeException failure = new CompletionException(
                new TimeoutException("provider-secret-timeout-detail"));

        AgentEvaluationException classified = AgentEvaluationException.classify(failure);

        assertThat(classified.code()).isEqualTo("EVALUATION_TIMEOUT");
        assertThat(classified.getMessage()).isEqualTo("评测 case 执行超时");
        assertThat(classified.getMessage()).doesNotContain("provider-secret-timeout-detail");
        assertThat(classified.getCause()).isSameAs(failure);
    }

    @Test
    void preservesKnownAgentBudgetCategoryAsSafeEvaluationCode() {
        AgentControlException failure = new AgentControlException(
                "BUDGET_EXCEEDED", "raw-budget-detail-that-must-not-leak");

        AgentEvaluationException classified = AgentEvaluationException.classify(failure);

        assertThat(classified.code()).isEqualTo("EVALUATION_BUDGET_EXCEEDED");
        assertThat(classified.getMessage()).isEqualTo("评测 case 已达到运行预算上限");
        assertThat(classified.getMessage()).doesNotContain("raw-budget-detail-that-must-not-leak");
    }

    @Test
    void separatesAccessInvalidCaseAndStateFailures() {
        AgentEvaluationException forbidden = AgentEvaluationException.classify(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant-b-secret"));
        AgentEvaluationException invalid = AgentEvaluationException.classify(
                new IllegalArgumentException("invalid raw case"));
        AgentEvaluationException state = AgentEvaluationException.classify(
                new TransientDataAccessResourceException("jdbc password leaked here"));

        assertThat(forbidden.code()).isEqualTo("EVALUATION_ACCESS_DENIED");
        assertThat(forbidden.getMessage()).doesNotContain("tenant-b-secret");
        assertThat(invalid.code()).isEqualTo("EVALUATION_INVALID_CASE");
        assertThat(invalid.getMessage()).doesNotContain("invalid raw case");
        assertThat(state.code()).isEqualTo("EVALUATION_STATE_UNAVAILABLE");
        assertThat(state.getMessage()).doesNotContain("jdbc password leaked here");
    }

    @Test
    void usesGenericSafeFailureAndDoesNotWrapTwice() {
        RuntimeException raw = new RuntimeException("api-key=secret");
        AgentEvaluationException classified = AgentEvaluationException.classify(raw);

        assertThat(classified.code()).isEqualTo("EVALUATION_EXECUTION_FAILED");
        assertThat(classified.getMessage()).isEqualTo("评测 case 执行失败");
        assertThat(classified.getMessage()).doesNotContain("api-key=secret");
        assertThat(AgentEvaluationException.classify(classified)).isSameAs(classified);
    }
}

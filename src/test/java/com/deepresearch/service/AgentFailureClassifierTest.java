package com.deepresearch.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class AgentFailureClassifierTest {

    @Test
    void classifiesRateLimitTimeoutAndUnknownWithoutLeakingProviderMessage() {
        AgentControlException rateLimit = AgentFailureClassifier.modelFailure(
                new IllegalStateException("HTTP 429: secret provider payload"));
        AgentControlException timeout = AgentFailureClassifier.modelFailure(
                new IllegalStateException("wrapper", new TimeoutException("socket detail")));
        AgentControlException unknown = AgentFailureClassifier.modelFailure(
                new IllegalStateException("jdbc://user:password@internal"));

        assertThat(rateLimit.code()).isEqualTo("MODEL_RATE_LIMITED");
        assertThat(timeout.code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(unknown.code()).isEqualTo("MODEL_EXECUTION_FAILED");
        assertThat(rateLimit.getMessage()).doesNotContain("secret");
        assertThat(unknown.getMessage()).doesNotContain("password", "internal");
    }
}

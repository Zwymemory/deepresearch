package com.deepresearch.service;

import com.deepresearch.agent.ToolArgumentFingerprint;
import com.deepresearch.web.dto.AgentResearchRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentIdempotencyServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void claimsExecutesAndStoresFirstRequest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 1);
        AgentIdempotencyService service = new AgentIdempotencyService(jdbc, objectMapper, Duration.ofHours(1));
        AtomicInteger calls = new AtomicInteger();

        AgentIdempotencyService.Outcome outcome = service.execute(
                "tenant:user", "/api/research/agent", "request-0001", request("question"),
                () -> {
                    calls.incrementAndGet();
                    return response("run-1");
                });

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.response().runId()).isEqualTo("run-1");
        assertThat(calls).hasValue(1);
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void replaysCompletedResponseWithoutExecutingAgain() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        AgentResearchRequest request = request("question");
        String fingerprint = ToolArgumentFingerprint.sha256(
                "{\"question\":\"question\",\"sessionId\":\"\"}");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "request_fingerprint", fingerprint,
                "status", "COMPLETED",
                "response_json", objectMapper.writeValueAsString(response("run-original")),
                "expires_at", Timestamp.from(Instant.now().plusSeconds(3600))
        )));
        AgentIdempotencyService service = new AgentIdempotencyService(jdbc, objectMapper, Duration.ofHours(1));

        AgentIdempotencyService.Outcome outcome = service.execute(
                "tenant:user", "/api/research/agent", "request-0001", request,
                () -> response("run-duplicate"));

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.response().runId()).isEqualTo("run-original");
    }

    @Test
    void rejectsSameKeyWithDifferentRequestFingerprint() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "request_fingerprint", "different-fingerprint",
                "status", "COMPLETED",
                "response_json", "{}",
                "expires_at", Timestamp.from(Instant.now().plusSeconds(3600))
        )));
        AgentIdempotencyService service = new AgentIdempotencyService(jdbc, objectMapper, Duration.ofHours(1));

        assertThatThrownBy(() -> service.execute(
                "tenant:user", "/api/research/agent", "request-0001", request("new body"),
                () -> response("run-2")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不能对应不同请求体");
    }

    @Test
    void rejectsUnsafeShortKeyBeforeBusinessExecution() {
        AgentIdempotencyService service = new AgentIdempotencyService(
                mock(JdbcTemplate.class), objectMapper, Duration.ofHours(1));

        assertThatThrownBy(() -> service.execute(
                "tenant:user", "/api/research/agent", "short", request("question"),
                () -> response("never")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("8-128");
    }

    @Test
    void expiredStatusCursorIsGoneInsteadOfBeingReusableForAStreamRestart() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "request_fingerprint", "fingerprint",
                "status", "COMPLETED",
                "response_json", "{}",
                "expires_at", Timestamp.from(Instant.now().minusSeconds(1))
        )));
        AgentIdempotencyService service = new AgentIdempotencyService(
                jdbc, objectMapper, Duration.ofHours(1));

        assertThatThrownBy(() -> service.status(
                "tenant:user", "/api/research/agent/stream", "request-0001"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("410", "事件游标已过期");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private AgentResearchRequest request(String question) {
        return new AgentResearchRequest(question, null, "tenant:user");
    }

    private AgentResearchResponse response(String runId) {
        return new AgentResearchResponse(
                runId, "session", "answer", 1, true,
                new AgentResearchResponse.MemoryContext("", List.of(), List.of(),
                        new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "test")),
                List.of(), List.of());
    }
}

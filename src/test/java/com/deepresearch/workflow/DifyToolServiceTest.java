package com.deepresearch.workflow;

import com.deepresearch.agent.CalculatorTool;
import com.deepresearch.agent.ToolArgumentFingerprint;
import com.deepresearch.agent.WebSearchTool;
import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.workflow.DifyToolDtos.Request;
import com.deepresearch.workflow.DifyToolDtos.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DifyToolServiceTest {
    private static final String TOKEN = "dify-tool-service-secret-must-be-32-bytes";
    private static final String RUN = "wf-7e65e2c8-4251-41ed-94aa-123147661234";
    private static final String CALL = RUN + ":initial:1";
    private static final String SOURCE = "kb:ragflow:dataset-1:document-1:chunk-1";
    private final WorkflowRepository repository = mock(WorkflowRepository.class);
    private final DifyKbToolGateway kbGateway = mock(DifyKbToolGateway.class);
    private final WebSearchTool webSearch = mock(WebSearchTool.class);
    private final CalculatorTool calculator = new CalculatorTool();
    private final ObjectMapper json = new ObjectMapper();
    private DifyToolService service;

    @BeforeEach
    void setUp() {
        service = new DifyToolService(repository, kbGateway, webSearch, calculator, json,
                TOKEN, "dify", "unrelated-api-secret-0123456789012345",
                "unrelated-internal-secret-0123456789", "unrelated-mcp-secret-0123456789012");
    }

    @Test
    void requiresDedicatedIdentityBeforeLookingUpRun() {
        assertThatThrownBy(() -> service.execute("Bearer user-jwt", "kb_search", request("query")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(repository, never()).find(any());
    }

    @Test
    void rejectsCallIdsNotStrictlyBoundToRunBeforeLookingUpOrClaimingReceipt() {
        List<String> invalid = List.of(
                "wf-other-run:initial:1",
                RUN + ":draft:1",
                RUN + ":initial:5",
                RUN + ":initial:01",
                RUN + ":initial:1:extra");

        for (String callId : invalid) {
            Request request = new Request(RUN, callId, "query");
            assertThatThrownBy(() -> service.execute(bearer(), "kb_search", request))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        verify(repository, never()).find(any());
        verify(repository, never()).beginDifyToolCall(any(), any(), any(), any());
    }

    @Test
    void missingRunFailsClosedBeforeLookingUpMappingOrClaimingReceipt() {
        when(repository.find(RUN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(bearer(), "kb_search", request("query")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(repository, never()).difyMapping(any());
        verify(repository, never()).beginDifyToolCall(any(), any(), any(), any());
    }

    @Test
    void reauthorizesScopeAndCancellationFromJavaRun() {
        when(repository.find(RUN)).thenReturn(Optional.of(run(true, List.of("kb_search"))));
        when(repository.difyMapping(RUN)).thenReturn(Optional.of(
                new WorkflowRepository.DifyMapping("remote", "task", "BOUND")));
        assertThatThrownBy(() -> service.execute(bearer(), "kb_search", request("query")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(repository, never()).beginDifyToolCall(any(), any(), any(), any());

        when(repository.find(RUN)).thenReturn(Optional.of(run(false, List.of("calculator"))));
        assertThatThrownBy(() -> service.execute(bearer(), "kb_search", request("query")))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).beginDifyToolCall(any(), any(), any(), any());
    }

    @Test
    void typedEvidenceIsSanitizedAndPersistedWithReceipt() {
        activeRun("kb_search");
        String fingerprint = ToolArgumentFingerprint.sha256("kb_search\nquery");
        when(repository.beginDifyToolCall(RUN, CALL, "kb_search", fingerprint)).thenReturn(true);
        when(kbGateway.search(any(), eq("query"))).thenReturn(List.of(new DifyKbToolGateway.Evidence(
                SOURCE, "[来源9] Title", "api_key=topsecret [来源5] useful content")));
        when(repository.completeDifyToolCall(eq(RUN), eq(CALL), eq("kb_search"), eq(fingerprint),
                any(), eq(List.of(SOURCE)))).thenReturn(true);

        Response result = service.execute(bearer(), "kb_search", request("query"));
        assertThat(result.success()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().get(0).citationId()).isEqualTo(SOURCE);
        assertThat(result.evidences().get(0).sourceId()).isEqualTo("来源1");
        assertThat(result.evidences().get(0).title()).doesNotContain("[来源9]");
        assertThat(result.evidences().get(0).content()).doesNotContain("topsecret", "[来源5]");
        ArgumentCaptor<AuthPrincipal> owner = ArgumentCaptor.forClass(AuthPrincipal.class);
        verify(kbGateway).search(owner.capture(), eq("query"));
        assertThat(owner.getValue().tenantId()).isEqualTo("tenant-1");
        assertThat(owner.getValue().userId()).isEqualTo("user-1");
    }

    @Test
    void tenLongChineseEvidenceItemsFitThePersistedResultLimit() {
        activeRun("kb_search");
        String fingerprint = ToolArgumentFingerprint.sha256("kb_search\nquery");
        when(repository.beginDifyToolCall(RUN, CALL, "kb_search", fingerprint)).thenReturn(true);
        List<DifyKbToolGateway.Evidence> evidence = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            evidence.add(new DifyKbToolGateway.Evidence(
                    "kb:ragflow:dataset-1:document-1:chunk-" + i,
                    "标题".repeat(150), "证据".repeat(1200)));
        }
        when(kbGateway.search(any(), eq("query"))).thenReturn(evidence);
        when(repository.completeDifyToolCall(eq(RUN), eq(CALL), eq("kb_search"), eq(fingerprint),
                any(), any())).thenReturn(true);

        Response result = service.execute(bearer(), "kb_search", request("query"));
        assertThat(result.success()).isTrue();
        assertThat(result.evidences()).hasSize(10);
        ArgumentCaptor<String> persistedJson = ArgumentCaptor.forClass(String.class);
        verify(repository).completeDifyToolCall(eq(RUN), eq(CALL), eq("kb_search"),
                eq(fingerprint), persistedJson.capture(), any());
        assertThat(persistedJson.getValue().getBytes(StandardCharsets.UTF_8).length)
                .isLessThan(100_000);
    }

    @Test
    void completedCallReplaysWithoutCallingGatewayAndChangedInputIsRejected() throws Exception {
        activeRun("kb_search");
        String fingerprint = ToolArgumentFingerprint.sha256("kb_search\nquery");
        Response cached = new Response(true, "OK", "kb_search", List.of(), "");
        when(repository.findDifyToolCall(RUN, CALL)).thenReturn(Optional.of(
                new WorkflowRepository.DifyToolCall("kb_search", fingerprint,
                        "COMPLETED", json.writeValueAsString(cached))));

        assertThat(service.execute(bearer(), "kb_search", request("query"))).isEqualTo(cached);
        assertThat(service.execute(bearer(), "kb_search", request("changed query")).code())
                .isEqualTo("CALL_ID_CONFLICT");
        verify(kbGateway, never()).search(any(), any());
        verify(repository, never()).completeDifyToolCall(any(), any(), any(), any(), any(), any());
    }

    @Test
    void emptyKbEvidenceAfterDisabledOrDeletedFilteringSucceedsAndPersistsEmptySources() {
        activeRun("kb_search");
        String fingerprint = ToolArgumentFingerprint.sha256("kb_search\nquery");
        when(repository.beginDifyToolCall(RUN, CALL, "kb_search", fingerprint)).thenReturn(true);
        when(kbGateway.search(any(), eq("query"))).thenReturn(List.of());
        when(repository.completeDifyToolCall(eq(RUN), eq(CALL), eq("kb_search"), eq(fingerprint),
                any(), eq(List.of()))).thenReturn(true);

        Response result = service.execute(bearer(), "kb_search", request("query"));

        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.evidences()).isEmpty();
        verify(repository).completeDifyToolCall(eq(RUN), eq(CALL), eq("kb_search"),
                eq(fingerprint), any(), eq(List.of()));
    }

    @Test
    void noGatewayFailsClosedAndPersistsSafeFailure() {
        activeRun("kb_search");
        String fingerprint = ToolArgumentFingerprint.sha256("kb_search\nquery");
        when(repository.beginDifyToolCall(RUN, CALL, "kb_search", fingerprint)).thenReturn(true);
        when(kbGateway.search(any(), any())).thenThrow(new IllegalStateException("offline"));
        when(repository.completeDifyToolCall(eq(RUN), eq(CALL), eq("kb_search"), eq(fingerprint),
                any(), eq(List.of()))).thenReturn(true);
        assertThat(service.execute(bearer(), "kb_search", request("query")).code())
                .isEqualTo("TOOL_UNAVAILABLE");
    }

    @Test
    void completionRaceNeverReturnsToolOutputAfterCancellation() {
        activeRun("calculator");
        String fingerprint = ToolArgumentFingerprint.sha256("calculator\n1+1");
        when(repository.beginDifyToolCall(RUN, CALL, "calculator", fingerprint)).thenReturn(true);
        assertThatThrownBy(() -> service.execute(bearer(), "calculator", request("1+1")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private void activeRun(String tool) {
        when(repository.find(RUN)).thenReturn(Optional.of(run(false, List.of(tool))));
        when(repository.difyMapping(RUN)).thenReturn(Optional.of(
                new WorkflowRepository.DifyMapping("remote", "task", "BOUND")));
    }

    private WorkflowRepository.RunRow run(boolean cancelled, List<String> scopes) {
        return new WorkflowRepository.RunRow(RUN, "session", "tenant-1:user-1", "question",
                "{}", "/api/research/workflows", "key", "fp", "thread", "DIFY_WORKING",
                "DIFY_WORKING", OffsetDateTime.now().plusMinutes(1), cancelled, scopes,
                "grant", null, null, null, null, null, null, null, null, null, 0,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private Request request(String input) {
        return new Request(RUN, CALL, input);
    }

    private String bearer() {
        return "Bearer " + TOKEN;
    }
}

package com.deepresearch.workflow;

import com.deepresearch.service.AgentStateService;
import com.deepresearch.service.UserContextService;
import com.deepresearch.web.dto.AgentResearchResponse;
import com.deepresearch.workflow.WorkflowDtos.CreateRequest;
import com.deepresearch.workflow.WorkflowDtos.FinalizeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowServiceTest {

    private final WorkflowRepository repository = mock(WorkflowRepository.class);
    private final AgentStateService agentStateService = mock(AgentStateService.class);
    private final UserContextService userContextService = mock(UserContextService.class);
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(repository, agentStateService, userContextService,
                new ObjectMapper(), true, Duration.ofSeconds(120));
        when(userContextService.currentUser()).thenReturn("tenant-a:user-a");
    }

    @Test
    void createsQueuedRunWithDeterministicSessionAndOnlyReadScopes() {
        when(repository.findByIdempotency(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(agentStateService.prepareContext(anyString(), anyString(), anyString()))
                .thenReturn(context("sess-wf-fixed"));
        when(repository.insertRun(any())).thenReturn(1);

        var result = service.create(new CreateRequest(
                "compare agents", null, List.of("kb_search", "calculator")), "request-key-0001");

        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.replayed()).isFalse();
        ArgumentCaptor<WorkflowRepository.NewRun> run = ArgumentCaptor.forClass(WorkflowRepository.NewRun.class);
        verify(repository).insertRun(run.capture());
        assertThat(run.getValue().requestedScopes()).containsExactly("calculator", "kb_search");
        assertThat(run.getValue().contextSnapshotJson()).doesNotContain("Bearer", "token");
        verify(repository).insertGrant(any());
        verify(repository).insertEvent(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(), anyString());
    }

    @Test
    void difyRunIsNeverClaimableByLangGraphSidecar() {
        WorkflowService dify = new WorkflowService(repository, agentStateService,
                userContextService, new ObjectMapper(), true, Duration.ofSeconds(120), "dify");
        when(repository.findByIdempotency(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(agentStateService.prepareContext(anyString(), anyString(), anyString()))
                .thenReturn(context("sess-wf-dify"));
        when(repository.insertRun(any())).thenReturn(1);

        var accepted = dify.create(new CreateRequest("question", null, List.of("kb_search")),
                "request-key-dify-01");

        assertThat(accepted.status()).isEqualTo("DIFY_DISPATCHING");
        ArgumentCaptor<WorkflowRepository.NewRun> row = ArgumentCaptor.forClass(WorkflowRepository.NewRun.class);
        verify(repository).insertRun(row.capture());
        assertThat(row.getValue().status()).isEqualTo("DIFY_DISPATCHING");
        verify(repository).insertDifyMapping(accepted.runId());
    }

    @Test
    void rejectsFileToolBeforeCreatingAnyBusinessState() {
        assertThatThrownBy(() -> service.create(
                new CreateRequest("read files", null, List.of("file_read")), "request-key-0002"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("只允许");

        verify(agentStateService, never()).prepareContext(anyString(), anyString(), anyString());
        verify(repository, never()).insertRun(any());
    }

    @Test
    void disabledFeatureFailsClosed() {
        WorkflowService disabled = new WorkflowService(repository, agentStateService, userContextService,
                new ObjectMapper(), false, Duration.ofSeconds(120));

        assertThatThrownBy(() -> disabled.create(
                new CreateRequest("question", null, null), "request-key-0003"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("尚未启用");
    }

    @Test
    void finalizeReplayRequiresSameClaimAndRequestFingerprint() {
        String claim = "7e65e2c8-4251-41ed-94aa-123147661234";
        WorkflowRepository.RunRow working = runRow("FINALIZING", null, null);
        when(repository.find("wf-1")).thenReturn(Optional.of(working));
        when(repository.finalizeClaim(eq("wf-1"), eq(UUID.fromString(claim)),
                eq(WorkflowStatus.SUCCEEDED), anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                anyString())).thenReturn(1);
        FinalizeRequest request = new FinalizeRequest(
                claim, "SUCCEEDED", "grounded answer [来源1]", List.of("source-1"), null, null, null);

        assertThat(service.finalizeRun("wf-1", request).replayed()).isFalse();

        ArgumentCaptor<String> finalResponse = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(repository).finalizeClaim(eq("wf-1"), eq(UUID.fromString(claim)),
                eq(WorkflowStatus.SUCCEEDED), finalResponse.capture(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                fingerprint.capture());
        assertThat(finalResponse.getValue())
                .contains("\"citationContract\":\"INDEXED_V1\"")
                .contains("grounded answer [来源1]");
        WorkflowRepository.RunRow terminal = runRow(
                "SUCCEEDED", fingerprint.getValue(), UUID.fromString(claim));
        when(repository.find("wf-1")).thenReturn(Optional.of(terminal));

        assertThat(service.finalizeRun("wf-1", request).replayed()).isTrue();
        assertThatThrownBy(() -> service.finalizeRun("wf-1", new FinalizeRequest(
                claim, "SUCCEEDED", "different answer [来源1]", List.of("source-1"), null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void rejectsAmbiguousCitationMappingsBeforeFinalization() {
        String claim = "7e65e2c8-4251-41ed-94aa-123147661234";

        assertThatThrownBy(() -> service.finalizeRun("wf-1", new FinalizeRequest(
                claim, "SUCCEEDED", "missing marker", List.of("source-1"), null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INDEXED_V1");
        assertThatThrownBy(() -> service.finalizeRun("wf-1", new FinalizeRequest(
                claim, "SUCCEEDED", "out of range [来源2]", List.of("source-1"), null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INDEXED_V1");
        assertThatThrownBy(() -> service.finalizeRun("wf-1", new FinalizeRequest(
                claim, "SUCCEEDED", "wrong order [来源2] [来源1]",
                List.of("source-1", "source-2"), null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INDEXED_V1");
        assertThatThrownBy(() -> service.finalizeRun("wf-1", new FinalizeRequest(
                claim, "SUCCEEDED", "duplicate [来源1]",
                List.of("source-1", "source-1"), null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INDEXED_V1");

        verify(repository, never()).find(anyString());
        verify(repository, never()).finalizeClaim(anyString(), any(), any(), anyString(), anyString(),
                any(), any(), anyString());
    }

    @Test
    void finalizesCitationValidationFailureWithStableErrorCodeAndNoAnswer() {
        String claim = "7e65e2c8-4251-41ed-94aa-123147661234";
        when(repository.find("wf-1")).thenReturn(Optional.of(runRow("FINALIZING", null, null)));
        when(repository.finalizeClaim(eq("wf-1"), eq(UUID.fromString(claim)),
                eq(WorkflowStatus.FAILED), anyString(), anyString(),
                eq("CITATION_VALIDATION_FAILED"),
                eq("citation validation failed: MARKER_OUT_OF_RANGE"), anyString())).thenReturn(1);

        FinalizeRequest request = new FinalizeRequest(
                claim, "FAILED", null, List.of(), null,
                "CITATION_VALIDATION_FAILED",
                "citation validation failed: MARKER_OUT_OF_RANGE");

        assertThat(service.finalizeRun("wf-1", request).status()).isEqualTo("FAILED");

        ArgumentCaptor<String> finalResponse = ArgumentCaptor.forClass(String.class);
        verify(repository).finalizeClaim(eq("wf-1"), eq(UUID.fromString(claim)),
                eq(WorkflowStatus.FAILED), finalResponse.capture(), anyString(),
                eq("CITATION_VALIDATION_FAILED"),
                eq("citation validation failed: MARKER_OUT_OF_RANGE"), anyString());
        assertThat(finalResponse.getValue())
                .contains("\"answer\":\"\"")
                .contains("\"citations\":[]")
                .contains("\"citationContract\":\"NONE\"")
                .contains("\"insufficientEvidence\":false");

        ArgumentCaptor<String> terminalPayload = ArgumentCaptor.forClass(String.class);
        verify(repository).insertEvent(eq("wf-1"), eq("workflow:terminal:failed"),
                eq("SYSTEM"), org.mockito.ArgumentMatchers.isNull(), eq("FAILED"),
                terminalPayload.capture());
        assertThat(terminalPayload.getValue())
                .contains("\"status\":\"FAILED\"")
                .contains("\"hasAnswer\":false")
                .contains("\"errorCode\":\"CITATION_VALIDATION_FAILED\"");
    }

    @Test
    void rejectsCandidateAnswerWhenCitationValidationFailed() {
        String claim = "7e65e2c8-4251-41ed-94aa-123147661234";

        assertThatThrownBy(() -> service.finalizeRun("wf-1", new FinalizeRequest(
                claim, "FAILED", "unverified answer [来源1]", List.of("source-1"), null,
                "CITATION_VALIDATION_FAILED", "citation validation failed")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不得发布");

        verify(repository, never()).find(anyString());
        verify(repository, never()).finalizeClaim(anyString(), any(), any(), anyString(), anyString(),
                any(), any(), anyString());
    }

    private AgentStateService.AgentContext context(String sessionId) {
        return new AgentStateService.AgentContext(
                sessionId, "tenant-a:user-a", "summary", List.of("用户: earlier"), List.of("memory"),
                new AgentResearchResponse.Diagnostics(true, 1, 1, 1, "test"));
    }

    private WorkflowRepository.RunRow runRow(String status, String finalizeFingerprint,
                                              UUID finalizedClaimToken) {
        OffsetDateTime now = OffsetDateTime.now();
        return new WorkflowRepository.RunRow(
                "wf-1", "sess-1", "tenant-a:user-a", "question", "{}",
                "/api/research/workflows", "idem", "a".repeat(64), "wf-1",
                status, status, now.plusMinutes(2), false, List.of("kb_search"), "grant-1",
                UUID.fromString("7e65e2c8-4251-41ed-94aa-123147661234"), "runner",
                now.plusSeconds(30), "{}", "{}", null, null,
                finalizeFingerprint, finalizedClaimToken, 1, now, now);
    }
}

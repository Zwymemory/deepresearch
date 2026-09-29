package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DifyWorkflowAdapterTest {
    private final WorkflowRepository repository = mock(WorkflowRepository.class);
    private final DifyWorkflowClient client = mock(DifyWorkflowClient.class);
    private final DifyCitationValidator citationValidator = mock(DifyCitationValidator.class);
    private final ObjectMapper json = new ObjectMapper();
    private final DifyWorkflowAdapter adapter = new DifyWorkflowAdapter(repository, client, json, citationValidator, "dify");

    @AfterEach
    void shutdown() {
        adapter.shutdown();
    }

    @Test
    void unknownDispatchIsATerminalStateRequiringManualReconciliation() {
        assertThat(WorkflowStatus.DISPATCH_UNKNOWN.terminal()).isTrue();
    }

    @Test
    void rejectsCitationOutsideThisRunEvenWhenDifyReportsSuccess() throws Exception {
        setup("succeeded", "SUCCEEDED", "answer [来源1]", "kb:ragflow:other:doc:chunk");
        when(repository.difySources("wf-1")).thenReturn(Set.of("kb:ragflow:allowed:doc:chunk"));

        adapter.reconcile("wf-1");

        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), response.capture(),
                any(), eq("CITATION_VALIDATION_FAILED"), eq(""));
        assertThat(response.getValue()).contains("\"answer\":\"\"").contains("\"citations\":[]");
    }

    @Test
    void acceptsOnlyExactAllowlistedAndOrderedCitations() throws Exception {
        setup("succeeded", "SUCCEEDED", "first [来源1] second [来源2]",
                "kb:ragflow:dataset:doc:one", "kb:ragflow:dataset:doc:two");
        when(repository.difySources("wf-1")).thenReturn(Set.of(
                "kb:ragflow:dataset:doc:one", "kb:ragflow:dataset:doc:two"));
        when(citationValidator.available(eq("wf-1"), any())).thenReturn(true);

        adapter.reconcile("wf-1");

        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.SUCCEEDED), any(), any(),
                eq(null), eq("first [来源1] second [来源2]"));
    }

    @Test
    void oldToolReceiptCannotPublishAfterItsSourceDisappears() throws Exception {
        String source = "kb:ragflow:dataset:doc:old-chunk";
        setup("succeeded", "SUCCEEDED", "answer [来源1]", source);
        when(repository.difySources("wf-1")).thenReturn(Set.of(source));
        when(citationValidator.available("wf-1", List.of(source))).thenReturn(false);

        adapter.reconcile("wf-1");

        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), response.capture(),
                any(), eq("CITATION_SOURCE_UNAVAILABLE"), eq(""));
        assertThat(response.getValue()).contains("\"answer\":\"\"").contains("\"citations\":[]");
    }

    @Test
    void acceptsSixReindexedEvidenceCitationsWithinBoundedSourceCheck() throws Exception {
        String[] sources = new String[6];
        for (int index = 0; index < sources.length; index++) {
            sources[index] = "kb:ragflow:dataset:doc:chunk-" + (index + 1);
        }
        setup("succeeded", "SUCCEEDED", "facts [来源1][来源2][来源3][来源4][来源5][来源6]", sources);
        when(repository.difySources("wf-1")).thenReturn(Set.of(sources));
        when(citationValidator.available(eq("wf-1"), any())).thenReturn(true);

        adapter.reconcile("wf-1");

        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.SUCCEEDED), any(), any(),
                eq(null), eq("facts [来源1][来源2][来源3][来源4][来源5][来源6]"));
    }

    @Test
    void remoteFailureCannotPublishAppClaimedSuccess() throws Exception {
        setup("failed", "SUCCEEDED", "answer [来源1]", "kb:ragflow:dataset:doc:one");
        adapter.reconcile("wf-1");

        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), any(), any(),
                eq("DIFY_WORKFLOW_FAILED"), eq(""));
    }

    @Test
    void preservesDistinctSafeFailureReasonAndNeverAcceptsRawProviderErrorText() throws Exception {
        when(repository.difyMapping("wf-1")).thenReturn(Optional.of(
                new WorkflowRepository.DifyMapping("remote", "task", "BOUND")));
        when(client.detail("remote")).thenReturn(json.readTree("""
                {"status":"succeeded","outputs":{"status":"FAILED","answer":"","citations":[],
                "error_code":"WEB_SEARCH_NOT_CONFIGURED"}}
                """));
        adapter.reconcile("wf-1");
        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), any(), any(),
                eq("WEB_SEARCH_NOT_CONFIGURED"), eq(""));
        when(client.detail("remote")).thenReturn(json.readTree("""
                {"status":"succeeded","outputs":{"status":"FAILED","answer":"","citations":[],
                "error_code":"secret unapproved provider body"}}
                """));
        adapter.reconcile("wf-1");
        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), any(), any(),
                eq("DIFY_OUTPUT_INVALID"), eq(""));
    }

    @Test
    void successfulWebOnlyAnswerPublishesActualReceiptMetadataAndMixedOrderRemainsExact() throws Exception {
        String url = "https://example.com/page", web = DifyWebEvidence.id(url, "Page", "Search summary");
        String kb = "kb:ragflow:dataset:doc:one";
        when(repository.difySources("wf-1")).thenReturn(Set.of(web, kb));
        when(repository.difyWebSource("wf-1", web)).thenReturn(Optional.of(new WorkflowRepository.DifyWebSource(
                web, url, "Page", "Search summary", OffsetDateTime.now())));
        when(citationValidator.available(eq("wf-1"), any())).thenReturn(true);
        setup("succeeded", "SUCCEEDED", "Summary [来源1]", web);
        adapter.reconcile("wf-1");
        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.SUCCEEDED), response.capture(), any(), eq(null), eq("Summary [来源1]"));
        assertThat(response.getValue()).contains("WEB_SEARCH_SNAPSHOT", url, "Search summary");
        setup("succeeded", "SUCCEEDED", "Knowledge [来源1] web [来源2]", kb, web);
        adapter.reconcile("wf-1");
        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.SUCCEEDED), any(), any(), eq(null),
                eq("Knowledge [来源1] web [来源2]"));
    }

    @Test
    void partialSuccessIsAFailedTerminalState() throws Exception {
        setup("partial-succeeded", "SUCCEEDED", "answer [来源1]", "kb:ragflow:dataset:doc:one");
        adapter.reconcile("wf-1");

        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), any(), any(),
                eq("DIFY_WORKFLOW_FAILED"), eq(""));
    }

    @Test
    void knowledgeMetadataUsesAuthorizedReceiptAndNeverAddsAPublicUrl() throws Exception {
        String kb = "kb:ragflow:dataset:doc:one";
        setup("succeeded", "SUCCEEDED", "Knowledge [来源1]", kb);
        when(repository.difySources("wf-1")).thenReturn(Set.of(kb));
        when(citationValidator.available("wf-1", List.of(kb))).thenReturn(true);
        when(repository.difyKbSource("wf-1", kb)).thenReturn(Optional.of(
                new WorkflowRepository.DifyKbSource(kb, "Actual document.md", "Actual retrieved chunk")));
        adapter.reconcile("wf-1");
        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.SUCCEEDED), response.capture(), any(),
                eq(null), eq("Knowledge [来源1]"));
        var metadata = json.readTree(response.getValue()).path("citationDetails").get(0);
        assertThat(metadata.path("sourceId").asText()).isEqualTo(kb);
        assertThat(metadata.path("kind").asText()).isEqualTo("KNOWLEDGE_CHUNK");
        assertThat(metadata.path("title").asText()).isEqualTo("Actual document.md");
        assertThat(metadata.path("excerpt").asText()).isEqualTo("Actual retrieved chunk");
        assertThat(metadata.has("url")).isFalse();
    }

    @Test
    void modelFailurePublishesOnlyAllowlistedNodeAndSafeCode() throws Exception {
        when(repository.difyMapping("wf-1")).thenReturn(Optional.of(
                new WorkflowRepository.DifyMapping("remote", "task", "BOUND")));
        when(client.detail("remote")).thenReturn(json.readTree("""
                {"status":"succeeded","outputs":{"status":"FAILED","answer":"","citations":[],
                "error_code":"DIFY_MODEL_OUTPUT_TRUNCATED","diagnostic_node":"reviewer",
                "reasoning_content":"private reasoning must never be published"}}
                """));
        adapter.reconcile("wf-1");
        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), response.capture(), any(),
                eq("DIFY_MODEL_OUTPUT_TRUNCATED"), eq(""));
        assertThat(json.readTree(response.getValue()).path("diagnostics").path("node").asText())
                .isEqualTo("reviewer");
        assertThat(response.getValue()).doesNotContain("private reasoning");
    }

    @Test
    void oversizedCitationMarkerFailsValidationInsteadOfStallingRun() throws Exception {
        setup("succeeded", "SUCCEEDED", "answer [来源999999999999999999999]", "kb:ragflow:dataset:doc:one");
        when(repository.difySources("wf-1")).thenReturn(Set.of("kb:ragflow:dataset:doc:one"));
        adapter.reconcile("wf-1");

        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), any(), any(),
                eq("CITATION_VALIDATION_FAILED"), eq(""));
    }

    @Test
    void succeededWithoutCitationsFailsClosed() throws Exception {
        setup("succeeded", "SUCCEEDED", "answer");

        adapter.reconcile("wf-1");

        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), any(), any(),
                eq("CITATION_VALIDATION_FAILED"), eq(""));
    }

    @Test
    void insufficientEvidenceCannotSmuggleAnAnswerOrCitations() throws Exception {
        setup("succeeded", "INSUFFICIENT_EVIDENCE", "unverified [来源1]",
                "kb:ragflow:dataset:doc:one");

        adapter.reconcile("wf-1");

        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.INSUFFICIENT_EVIDENCE),
                response.capture(), any(), eq(null), eq(""));
        assertThat(response.getValue()).contains("\"answer\":\"\"").contains("\"citations\":[]");
    }

    @Test
    void engineSwitchStillTimesOutAndReconcilesExistingDifyRuns() throws Exception {
        DifyWorkflowAdapter switched = new DifyWorkflowAdapter(repository, client, json, citationValidator, "langgraph");
        setup("succeeded", "SUCCEEDED", "answer [来源1]", "kb:ragflow:dataset:doc:one");
        when(repository.difySources("wf-1")).thenReturn(Set.of("kb:ragflow:dataset:doc:one"));
        when(citationValidator.available(eq("wf-1"), any())).thenReturn(true);
        when(repository.claimBoundDifyRuns(4)).thenReturn(List.of("wf-1"));

        try {
            switched.reconcileBound();

            verify(repository).timeoutPendingDify();
            verify(repository).abandonStaleDifyDispatches();
            verify(repository).expiredDifyRuns();
            verify(repository).claimBoundDifyRuns(4);
            verify(client, timeout(1_000)).detail("remote-1");
            verify(repository, timeout(1_000)).finishDify(eq("wf-1"), eq(WorkflowStatus.SUCCEEDED),
                    any(), any(), eq(null), eq("answer [来源1]"));
        } finally {
            switched.shutdown();
        }
    }

    @Test
    void disconnectAfterWorkflowStartedLeavesBoundRunForReconciliation() throws Exception {
        when(repository.claimDifyDispatches(1)).thenReturn(List.of("wf-1"), List.of());
        when(repository.find("wf-1")).thenReturn(Optional.of(run("wf-1", false)));
        when(repository.difyMapping("wf-1")).thenReturn(Optional.of(
                new WorkflowRepository.DifyMapping("remote-1", "task-1", "BOUND")));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<com.fasterxml.jackson.databind.JsonNode> events = invocation.getArgument(2);
            var started = json.createObjectNode();
            started.put("event", "workflow_started");
            started.put("workflow_run_id", "remote-1");
            started.put("task_id", "task-1");
            events.accept(started);
            throw new IOException("stream disconnected");
        }).when(client).run(anyMap(), anyString(), any());

        adapter.dispatch();

        verify(repository, timeout(1_000)).bindDify("wf-1", "remote-1", "task-1");
        verify(repository, after(200).never()).unknownDifyDispatch("wf-1");
    }

    @Test
    void lateWorkflowStartedAfterCancellationStoresIdsAndStopsRemoteTask() throws Exception {
        when(repository.claimDifyDispatches(1)).thenReturn(List.of("wf-1"), List.of());
        when(repository.find("wf-1")).thenReturn(
                Optional.of(run("wf-1", false)),
                Optional.of(run("wf-1", true)),
                Optional.of(run("wf-1", true)));
        when(repository.difyMapping("wf-1")).thenReturn(Optional.of(
                new WorkflowRepository.DifyMapping("remote-1", "task-1", "UNKNOWN")));
        when(repository.claimDifyStops(1)).thenReturn(List.of(stopWork("wf-1", "remote-1", "task-1")), List.of());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<com.fasterxml.jackson.databind.JsonNode> events = invocation.getArgument(2);
            var started = json.createObjectNode();
            started.put("event", "workflow_started");
            started.put("workflow_run_id", "remote-1");
            started.put("task_id", "task-1");
            events.accept(started);
            throw new IOException("late response disconnected");
        }).when(client).run(anyMap(), anyString(), any());

        adapter.dispatch();

        verify(repository, timeout(1_000)).bindDify("wf-1", "remote-1", "task-1");
        verify(client, timeout(1_000)).stop(eq("task-1"), anyString());
        verify(repository, after(200).never()).unknownDifyDispatch("wf-1");
    }

    @Test
    void failureBeforeRemoteIdCreatesOneDispatchUnknownTerminalEvent() throws Exception {
        when(repository.claimDifyDispatches(1)).thenReturn(List.of("wf-1"), List.of());
        when(repository.find("wf-1")).thenReturn(Optional.of(run("wf-1", false)));
        when(repository.difyMapping("wf-1")).thenReturn(Optional.of(
                new WorkflowRepository.DifyMapping(null, null, "POSTING")));
        when(repository.unknownDifyDispatch("wf-1")).thenReturn(true);
        doThrow(new IOException("no response")).when(client).run(anyMap(), anyString(), any());

        adapter.dispatch();

        verify(repository, timeout(1_000)).unknownDifyDispatch("wf-1");
        verify(repository, timeout(1_000)).insertEvent("wf-1", "dify:dispatch:unknown",
                "SYSTEM", null, "DISPATCH_UNKNOWN", "{\"status\":\"DISPATCH_UNKNOWN\"}");
    }

    @Test
    void expiredBoundRunIsStoppedEvenAfterEngineSwitch() throws Exception {
        DifyWorkflowAdapter switched = new DifyWorkflowAdapter(repository, client, json, citationValidator, "langgraph");
        when(repository.expiredDifyRuns()).thenReturn(List.of("wf-expired"));
        when(repository.timeoutDify("wf-expired")).thenReturn(true);
        when(repository.find("wf-expired")).thenReturn(Optional.of(run("wf-expired", true)));
        when(repository.difyMapping("wf-expired")).thenReturn(Optional.of(
                new WorkflowRepository.DifyMapping("remote-expired", "task-expired", "BOUND")));
        when(repository.claimDifyStops(1)).thenReturn(
                List.of(stopWork("wf-expired", "remote-expired", "task-expired")), List.of());

        try {
            switched.reconcileBound();
            verify(client, timeout(1_000)).stop(eq("task-expired"), anyString());
        } finally {
            switched.shutdown();
        }
    }

    @Test
    void acceptedStopIsOnlyConfirmedAfterRemoteDetailReportsStopped() throws Exception {
        WorkflowRepository.DifyStopWork work = new WorkflowRepository.DifyStopWork(
                "wf-1", "task-1", "remote-1", "REQUESTED", UUID.randomUUID(), 2);
        when(repository.claimDifyStops(1)).thenReturn(List.of(work), List.of());
        var detail = json.createObjectNode();
        detail.put("status", "stopped");
        when(client.detail("remote-1")).thenReturn(detail);

        adapter.dispatchStops();

        verify(repository, timeout(1_000)).completeDifyStop(work, "CONFIRMED_STOPPED", null);
        verify(client, never()).stop(anyString(), anyString());
    }

    @Test
    void failedStopRequestRemainsPendingForDurableRetry() throws Exception {
        WorkflowRepository.DifyStopWork work = stopWork("wf-1", "remote-1", "task-1");
        when(repository.claimDifyStops(1)).thenReturn(List.of(work), List.of());
        when(repository.find("wf-1")).thenReturn(Optional.of(run("wf-1", true)));
        doThrow(new IOException("unavailable")).when(client).stop(eq("task-1"), anyString());

        adapter.dispatchStops();

        verify(repository, timeout(1_000)).completeDifyStop(work, "PENDING", "DIFY_STOP_UNAVAILABLE");
    }

    private void setup(String remoteStatus, String appStatus, String answer, String... citations) throws Exception {
        when(repository.difyMapping("wf-1")).thenReturn(Optional.of(
                new WorkflowRepository.DifyMapping("remote-1", "task-1", "BOUND")));
        var payload = json.createObjectNode();
        payload.put("status", remoteStatus);
        var outputs = payload.putObject("outputs");
        outputs.put("status", appStatus);
        outputs.put("answer", answer);
        var array = outputs.putArray("citations");
        for (String citation : citations) array.add(citation);
        when(client.detail("remote-1")).thenReturn(payload);
    }

    private WorkflowRepository.RunRow run(String runId, boolean cancelled) {
        OffsetDateTime now = OffsetDateTime.now();
        return new WorkflowRepository.RunRow(runId, "session", "tenant-1:user-1", "question",
                "{}", "/api/research/workflows", "key", "fp", runId, "DIFY_WORKING",
                "DIFY_WORKING", now.plusMinutes(1), cancelled, List.of("kb_search"),
                "grant", null, null, null, null, null, null, null, null, null, 0, now, now);
    }

    private WorkflowRepository.DifyStopWork stopWork(String runId, String remoteId, String taskId) {
        return new WorkflowRepository.DifyStopWork(runId, taskId, remoteId, "PENDING", UUID.randomUUID(), 1);
    }
}

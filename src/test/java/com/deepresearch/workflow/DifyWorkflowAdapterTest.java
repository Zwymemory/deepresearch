package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DifyWorkflowAdapterTest {
    private final WorkflowRepository repository = mock(WorkflowRepository.class);
    private final DifyWorkflowClient client = mock(DifyWorkflowClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final DifyWorkflowAdapter adapter = new DifyWorkflowAdapter(repository, client, json, "dify");

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

        adapter.reconcile("wf-1");

        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.SUCCEEDED), any(), any(),
                eq(null), eq("first [来源1] second [来源2]"));
    }

    @Test
    void remoteFailureCannotPublishAppClaimedSuccess() throws Exception {
        setup("failed", "SUCCEEDED", "answer [来源1]", "kb:ragflow:dataset:doc:one");
        adapter.reconcile("wf-1");

        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), any(), any(),
                eq("DIFY_WORKFLOW_FAILED"), eq(""));
    }

    @Test
    void partialSuccessIsAFailedTerminalState() throws Exception {
        setup("partial-succeeded", "SUCCEEDED", "answer [来源1]", "kb:ragflow:dataset:doc:one");
        adapter.reconcile("wf-1");

        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), any(), any(),
                eq("DIFY_WORKFLOW_FAILED"), eq(""));
    }

    @Test
    void oversizedCitationMarkerFailsValidationInsteadOfStallingRun() throws Exception {
        setup("succeeded", "SUCCEEDED", "answer [来源999999999999999999999]", "kb:ragflow:dataset:doc:one");
        when(repository.difySources("wf-1")).thenReturn(Set.of("kb:ragflow:dataset:doc:one"));
        adapter.reconcile("wf-1");

        verify(repository).finishDify(eq("wf-1"), eq(WorkflowStatus.FAILED), any(), any(),
                eq("CITATION_VALIDATION_FAILED"), eq(""));
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
}

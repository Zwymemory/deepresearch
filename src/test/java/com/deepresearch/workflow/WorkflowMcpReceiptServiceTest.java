package com.deepresearch.workflow;

import com.deepresearch.mcp.McpKnowledgeTools.Evidence;
import com.deepresearch.mcp.McpKnowledgeTools.McpToolResponse;
import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowMcpReceiptServiceTest {

    private static final String CLAIM = "7e65e2c8-4251-41ed-94aa-123147661234";
    private static final UUID CLAIM_UUID = UUID.fromString(CLAIM);
    private static final String FINGERPRINT =
            "e5ba3872ed1941f96f8461dbd83a68b2e72d4cc27d0efed49429c19995c5f91a";

    private final WorkflowRepository repository = mock(WorkflowRepository.class);
    private final WorkflowMcpReceiptService service = new WorkflowMcpReceiptService(
            repository, new ObjectMapper());
    private final WorkflowDelegationContext context = new WorkflowDelegationContext(
            new AuthPrincipal("tenant-a", "user-a", List.of("USER")),
            "run-1", "grant-1", "task-1", CLAIM, Set.of("kb_search"), "tool-call-0001");

    @Test
    void atomicallyAcquiresMatchingPythonReceipt() {
        when(repository.beginMcpToolExecution(
                context, CLAIM_UUID, "tool-call-0001", "kb_search", FINGERPRINT)).thenReturn(true);

        var result = service.begin(context, "kb_search", Map.of("query", "MCP-7788"));

        assertThat(result.action()).isEqualTo(WorkflowMcpReceiptService.Action.EXECUTE);
        assertThat(result.requestFingerprint()).isEqualTo(FINGERPRINT);
    }

    @Test
    void replaysCompletedJavaResultWithoutExecutingAgain() throws Exception {
        McpToolResponse response = new McpToolResponse(true, "OK", "kb_search", List.of(
                new Evidence("source-1", "kb_search", "title", "chunk-1", "evidence", "digest")));
        when(repository.findMcpToolExecution("run-1", "tool-call-0001")).thenReturn(Optional.of(
                new WorkflowRepository.McpToolExecutionRow(
                        "task-1", "kb_search", FINGERPRINT, CLAIM_UUID,
                        "COMPLETED", new ObjectMapper().writeValueAsString(response), CLAIM_UUID)));
        when(repository.activeMcpExecutionBinding(context, CLAIM_UUID)).thenReturn(true);

        var result = service.begin(context, "kb_search", Map.of("query", "MCP-7788"));

        assertThat(result.action()).isEqualTo(WorkflowMcpReceiptService.Action.REPLAY);
        assertThat(result.replay()).isEqualTo(response);
    }

    @Test
    void rejectsInProgressConflictAndStaleClaimWithStableCodes() {
        when(repository.findMcpToolExecution("run-1", "tool-call-0001")).thenReturn(Optional.of(
                new WorkflowRepository.McpToolExecutionRow(
                        "task-1", "kb_search", FINGERPRINT, CLAIM_UUID,
                        "EXECUTING", null, CLAIM_UUID)));
        when(repository.activeMcpExecutionBinding(context, CLAIM_UUID)).thenReturn(true);
        assertThat(service.begin(context, "kb_search", Map.of("query", "MCP-7788")).errorCode())
                .isEqualTo("MCP_CALL_IN_PROGRESS");

        assertThat(service.begin(context, "web_search", Map.of("query", "MCP-7788")).errorCode())
                .isEqualTo("MCP_RECEIPT_CONFLICT");

        when(repository.activeMcpExecutionBinding(context, CLAIM_UUID)).thenReturn(false);
        assertThat(service.begin(context, "kb_search", Map.of("query", "MCP-7788")).errorCode())
                .isEqualTo("MCP_STALE_CLAIM");
    }

    @Test
    void rejectsAmbiguousExecutionFromPreviousClaimAsResultUnknown() {
        UUID previousClaim = UUID.randomUUID();
        when(repository.findMcpToolExecution("run-1", "tool-call-0001")).thenReturn(Optional.of(
                new WorkflowRepository.McpToolExecutionRow(
                        "task-1", "kb_search", FINGERPRINT, CLAIM_UUID,
                        "EXECUTING", null, previousClaim)));
        when(repository.activeMcpExecutionBinding(context, CLAIM_UUID)).thenReturn(true);

        assertThat(service.begin(context, "kb_search", Map.of("query", "MCP-7788")).errorCode())
                .isEqualTo("MCP_RESULT_UNKNOWN");
    }

    @Test
    void completionIsFencedByRepository() {
        McpToolResponse response = new McpToolResponse(true, "OK", "kb_search", List.of());
        when(repository.completeMcpToolExecution(
                context, CLAIM_UUID, "tool-call-0001", "kb_search", FINGERPRINT, "{\"placeholder\":true}"))
                .thenReturn(false);

        assertThat(service.complete(context, "kb_search", FINGERPRINT, response)).isFalse();
        verify(repository).completeMcpToolExecution(
                context, CLAIM_UUID, "tool-call-0001", "kb_search", FINGERPRINT,
                "{\"success\":true,\"code\":\"OK\",\"tool\":\"kb_search\",\"evidence\":[]}");
    }

    @Test
    void canonicalizesArgumentOrderBeforeFingerprinting() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("query", "员工年假");
        first.put("topK", 3);

        Map<String, Object> second = new LinkedHashMap<>();

        second.put("topK", 3);
        second.put("query", "员工年假");

        assertThat(service.requestFingerprint(first)).isEqualTo(service.requestFingerprint(second));
    }

    @Test
    void rejectsSameCallIdWhenArgumentsChange() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("query", "员工年假");
        first.put("topK", 3);
        String originalFingerprint = service.requestFingerprint(first);

        when(repository.findMcpToolExecution("run-1", "tool-call-0001")).thenReturn(Optional.of(
                new WorkflowRepository.McpToolExecutionRow(
                        "task-1", "kb_search", originalFingerprint, CLAIM_UUID,
                        "EXECUTING", null, CLAIM_UUID)));

        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("query", "员工年假");
        changed.put("topK", 5);

        var result = service.begin(context, "kb_search", changed);

        assertThat(result.action()).isEqualTo(WorkflowMcpReceiptService.Action.REJECT);
        assertThat(result.errorCode()).isEqualTo("MCP_RECEIPT_CONFLICT");
    }
}

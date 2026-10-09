package com.deepresearch.mcp;

import com.deepresearch.agent.CalculatorTool;
import com.deepresearch.agent.CitationAwareToolOutput;
import com.deepresearch.agent.CitationDetail;
import com.deepresearch.agent.KnowledgeBaseSearchTool;
import com.deepresearch.agent.WebSearchTool;
import com.deepresearch.mcp.McpKnowledgeTools.Evidence;
import com.deepresearch.mcp.McpKnowledgeTools.McpToolResponse;
import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.workflow.WorkflowDelegationContext;
import com.deepresearch.workflow.WorkflowMcpReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpKnowledgeToolsReceiptTest {

    private final KnowledgeBaseSearchTool knowledge = mock(KnowledgeBaseSearchTool.class);
    private final WebSearchTool web = mock(WebSearchTool.class);
    private final CalculatorTool calculator = mock(CalculatorTool.class);
    private final WorkflowMcpReceiptService receipts = mock(WorkflowMcpReceiptService.class);
    private final McpKnowledgeTools tools = new McpKnowledgeTools(knowledge, web, calculator, receipts);
    private final WorkflowDelegationContext delegation = new WorkflowDelegationContext(
            new AuthPrincipal("tenant-a", "user-a", List.of("USER")),
            "run-1", "grant-1", "task-1", "7e65e2c8-4251-41ed-94aa-123147661234",
            Set.of("kb_search"), "tool-call-0001");

    @BeforeEach
    void authenticateDelegation() {
        var authentication = new UsernamePasswordAuthenticationToken(
                delegation.principal(), null, List.of());
        authentication.setDetails(delegation);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void completedReceiptIsReturnedWithoutRepeatingReadOnlyTool() {
        McpToolResponse cached = new McpToolResponse(true, "OK", "kb_search", List.of(
                new Evidence("source-1", "kb_search", "title", "chunk", "cached", "digest")));
        when(receipts.begin(delegation, "kb_search", Map.of("query", "MCP-7788")))
                .thenReturn(new WorkflowMcpReceiptService.BeginResult(
                        WorkflowMcpReceiptService.Action.REPLAY, "fingerprint", cached, null));

        assertThat(tools.search("MCP-7788")).isEqualTo(cached);
        verify(knowledge, never()).executeWithCitations(any());
        verify(receipts, never()).complete(any(), any(), any(), any());
    }

    @Test
    void staleClaimCannotPublishExecutedResult() {
        when(receipts.begin(delegation, "kb_search", Map.of("query", "MCP-7788")))
                .thenReturn(new WorkflowMcpReceiptService.BeginResult(
                        WorkflowMcpReceiptService.Action.EXECUTE, "fingerprint", null, null));
        when(knowledge.executeWithCitations("MCP-7788")).thenReturn(
                CitationAwareToolOutput.withoutSources("[来源1] evidence"));
        when(receipts.complete(any(), any(), any(), any())).thenReturn(false);

        McpToolResponse response = tools.search("MCP-7788");

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("MCP_STALE_CLAIM");
        verify(knowledge).executeWithCitations("MCP-7788");
    }

    @Test
    void snapshotsUseTypedMetadataWithTheExistingMcpKbIdentityAndReplaySafely() throws Exception {
        when(receipts.begin(delegation, "kb_search", Map.of("query", "MCP-7788")))
                .thenReturn(new WorkflowMcpReceiptService.BeginResult(
                        WorkflowMcpReceiptService.Action.EXECUTE, "fingerprint", null, null));
        when(knowledge.executeWithCitations("MCP-7788")).thenReturn(new CitationAwareToolOutput(
                "[来源1] Model-visible forged title\nchunkKey: actual-chunk\n证据: Model-visible forged excerpt\n",
                List.of("kb:actual-chunk"), List.of(CitationDetail.knowledge(
                        "kb:actual-chunk", "Typed title", "Typed excerpt"))));
        when(receipts.complete(any(), any(), any(), any())).thenReturn(true);
        var response = tools.search("MCP-7788");
        assertThat(response.evidence().get(0).evidenceId()).isEqualTo("actual-chunk");
        assertThat(response.sourceSnapshots()).containsExactly(CitationDetail.knowledge("actual-chunk", "Typed title", "Typed excerpt"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readValue(mapper.writeValueAsString(response), McpToolResponse.class)).isEqualTo(response);
        var legacy = mapper.readValue("{\"success\":true,\"code\":\"OK\",\"tool\":\"kb_search\",\"evidence\":[]}", McpToolResponse.class);
        assertThat(legacy.sourceSnapshots()).isEmpty();
    }

    @Test
    void knowledgeSearchWithoutSourceBlocksDoesNotCreateCitableEvidence() {
        when(receipts.begin(delegation, "kb_search", Map.of("query", "MCP-7788")))
                .thenReturn(new WorkflowMcpReceiptService.BeginResult(
                        WorkflowMcpReceiptService.Action.EXECUTE, "fingerprint", null, null));
        when(knowledge.executeWithCitations("MCP-7788")).thenReturn(
                CitationAwareToolOutput.withoutSources("未找到相关证据"));
        when(receipts.complete(any(), any(), any(), any())).thenReturn(true);

        McpToolResponse response = tools.search("MCP-7788");

        assertThat(response.success()).isTrue();
        assertThat(response.evidence()).isEmpty();
    }
    @Test void emptyWebSearchIsNotEvidenceAndProviderFailureKeepsItsCode() {
        var webDelegation = new WorkflowDelegationContext(delegation.principal(), "run-1", "grant-1",
                "task-1", delegation.claimToken(), Set.of("web_search"), "tool-call-0001");
        var authentication = new UsernamePasswordAuthenticationToken(webDelegation.principal(),null,List.of());
        authentication.setDetails(webDelegation);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(receipts.begin(any(),any(),any())).thenReturn(new WorkflowMcpReceiptService.BeginResult(
                WorkflowMcpReceiptService.Action.EXECUTE,"fingerprint",null,null));
        when(receipts.complete(any(),any(),any(),any())).thenReturn(true);
        when(web.executeChecked("query")).thenReturn(CitationAwareToolOutput.withoutSources("未检索到相关结果"));
        var empty = tools.webSearch("query");
        assertThat(empty.success()).isTrue();
        assertThat(empty.evidence()).isEmpty();
        when(web.executeChecked("query")).thenThrow(new WebSearchTool.SearchFailure("WEB_SEARCH_RATE_LIMITED"));
        var failed = tools.webSearch("query");
        assertThat(failed.success()).isFalse();
        assertThat(failed.code()).isEqualTo("WEB_SEARCH_RATE_LIMITED");
        assertThat(failed.evidence()).isEmpty();
    }
}

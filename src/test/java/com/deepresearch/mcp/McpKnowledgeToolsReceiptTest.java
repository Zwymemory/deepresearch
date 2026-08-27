package com.deepresearch.mcp;

import com.deepresearch.agent.CalculatorTool;
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
        verify(knowledge, never()).execute(any());
        verify(receipts, never()).complete(any(), any(), any(), any());
    }

    @Test
    void staleClaimCannotPublishExecutedResult() {
        when(receipts.begin(delegation, "kb_search", Map.of("query", "MCP-7788")))
                .thenReturn(new WorkflowMcpReceiptService.BeginResult(
                        WorkflowMcpReceiptService.Action.EXECUTE, "fingerprint", null, null));
        when(knowledge.execute("MCP-7788")).thenReturn("[来源1] evidence");
        when(receipts.complete(any(), any(), any(), any())).thenReturn(false);

        McpToolResponse response = tools.search("MCP-7788");

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("MCP_STALE_CLAIM");
        verify(knowledge).execute("MCP-7788");
    }
}

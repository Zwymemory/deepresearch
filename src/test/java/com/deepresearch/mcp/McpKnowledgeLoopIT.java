package com.deepresearch.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.deepresearch.agent.KnowledgeBaseSearchTool;
import com.deepresearch.agent.CitationAwareToolOutput;
import com.deepresearch.agent.CitationDetail;
import com.deepresearch.mcp.McpKnowledgeClientService.McpClientFailure;
import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.workflow.WorkflowAccessService;
import com.deepresearch.workflow.WorkflowDelegationContext;
import com.deepresearch.workflow.WorkflowMcpReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.shutdown=immediate",
                "spring.lifecycle.timeout-per-shutdown-phase=1s"
        })
@ActiveProfiles("unit-test")
class McpKnowledgeLoopIT {

    @LocalServerPort
    private int port;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private VectorStore vectorStore;

    @MockitoBean
    private KnowledgeBaseSearchTool knowledgeBaseSearchTool;

    @MockitoBean
    private WorkflowAccessService workflowAccessService;

    @MockitoBean
    private WorkflowMcpReceiptService receiptService;

    @Test
    void negotiatesDiscoversSchemaAndCallsKnowledgeToolOverRealMcpTransport() throws Exception {
        when(knowledgeBaseSearchTool.executeWithCitations("MCP-7788"))
                .thenReturn(new CitationAwareToolOutput("""
                        [来源1] MCP-7788
                        chunkKey: mcp-7788-chunk
                        证据: MCP-7788 是真实 MCP 闭环测试参数
                        """, List.of("kb:mcp-7788-chunk"), List.of(CitationDetail.knowledge(
                                "kb:mcp-7788-chunk", "MCP-7788", "MCP-7788 是真实 MCP 闭环测试参数"))));
        AuthPrincipal principal = new AuthPrincipal("tenant-a", "user-a", List.of("USER"));
        when(workflowAccessService.authenticateDelegation("delegation-token"))
                .thenReturn(new WorkflowDelegationContext(
                        principal, "wf-1", "grant-1", "task-1",
                        "7e65e2c8-4251-41ed-94aa-123147661234", Set.of("kb_search")));
        when(receiptService.begin(any(), anyString(), any())).thenReturn(
                new WorkflowMcpReceiptService.BeginResult(
                        WorkflowMcpReceiptService.Action.EXECUTE, "fingerprint", null, null));
        when(receiptService.complete(any(), anyString(), anyString(), any())).thenReturn(true);
        McpKnowledgeClientService client = client(port, Duration.ofSeconds(5));

        McpKnowledgeClientService.McpCallResult result = client.callToolWithDelegation(
                "kb_search", Map.of("query", "MCP-7788"), "delegation-token", "wf-1", "task-1",
                "tool-call-transport-0001");

        assertThat(result.protocolVersion()).isNotBlank();
        assertThat(result.serverName()).isEqualTo("deepresearch-kb-server");
        assertThat(result.toolsCapability()).isTrue();
        assertThat(result.discoveredTools()).contains("kb_search", "web_search", "calculator");
        assertThat(result.inputSchema()).contains("query");
        assertThat(result.error()).isFalse();
        McpKnowledgeTools.McpToolResponse response = new ObjectMapper().readValue(
                result.content(), McpKnowledgeTools.McpToolResponse.class);
        assertThat(response.success()).isTrue();
        assertThat(response.sourceSnapshots()).containsExactly(CitationDetail.knowledge(
                "mcp-7788-chunk", "MCP-7788", "MCP-7788 是真实 MCP 闭环测试参数"));
        assertThat(response.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.evidenceId()).isEqualTo("mcp-7788-chunk");
            assertThat(evidence.uriOrChunkKey()).isEqualTo("mcp-7788-chunk");
            assertThat(evidence.excerpt()).isEqualTo("MCP-7788 是真实 MCP 闭环测试参数");
        });
        verify(receiptService).begin(
                argThat(context -> "tool-call-transport-0001".equals(context.callId())),
                eq("kb_search"), eq(Map.of("query", "MCP-7788")));
    }

    @Test
    void rejectsDelegationUsedWithoutItsRunAndTaskBinding() {
        AuthPrincipal principal = new AuthPrincipal("tenant-a", "user-a", List.of("USER"));
        when(workflowAccessService.authenticateDelegation("delegation-token"))
                .thenReturn(new WorkflowDelegationContext(
                        principal, "wf-1", "grant-1", "task-1",
                        "7e65e2c8-4251-41ed-94aa-123147661234", Set.of("kb_search")));
        McpKnowledgeClientService client = client(port, Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.callToolWithDelegation(
                "kb_search", Map.of("query", "MCP-7788"), "delegation-token", "wf-2", "task-2"))
                .isInstanceOfSatisfying(McpClientFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_UNAVAILABLE"));
    }

    @Test
    void reportsMissingToolAfterSuccessfulDiscovery() {
        McpKnowledgeClientService client = client(port, Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.callToolWithBearer("missing_tool", Map.of(), null))
                .isInstanceOfSatisfying(McpClientFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_TOOL_NOT_FOUND"));
    }

    @Test
    void classifiesConnectionFailureAsMcpUnavailable() {
        McpKnowledgeClientService client = new McpKnowledgeClientService(
                "http://127.0.0.1:1", Duration.ofMillis(500));

        assertThatThrownBy(() -> client.searchKnowledge("query"))
                .isInstanceOfSatisfying(McpClientFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_UNAVAILABLE"));
    }

    private McpKnowledgeClientService client(int serverPort, Duration timeout) {
        return new McpKnowledgeClientService("http://127.0.0.1:" + serverPort, timeout);
    }
}

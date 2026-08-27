package com.deepresearch.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 真实 MCP Client 演示：initialize 协议/能力协商 -> tools/list 发现 Schema -> tools/call 调用。
 * 每次演示使用短生命周期连接，任何连接或协议失败统一标记为 MCP_UNAVAILABLE。
 */
@Service
public class McpKnowledgeClientService {

    private final String serverBaseUrl;
    private final Duration timeout;

    public McpKnowledgeClientService(
            @Value("${deepresearch.mcp.demo-server-url:http://127.0.0.1:8080}") String serverBaseUrl,
            @Value("${deepresearch.mcp.request-timeout:10s}") Duration timeout) {
        this.serverBaseUrl = serverBaseUrl;
        this.timeout = timeout;
    }

    public McpCallResult searchKnowledge(String query) {
        return callTool("kb_search", Map.of("query", query));
    }

    /**
     * Compatibility path for the explicitly public local MCP demo. It never
     * copies credentials from Spring Security into a downstream request.
     */
    public McpCallResult callTool(String toolName, Map<String, Object> arguments) {
        return callToolWithBinding(toolName, arguments, null, null, null, null);
    }

    /** Sidecars and controlled demos pass a task delegation explicitly; user API tokens are not reused. */
    public McpCallResult callToolWithBearer(String toolName, Map<String, Object> arguments, String bearerToken) {
        return callToolWithBinding(toolName, arguments, bearerToken, null, null, null);
    }

    public McpCallResult callToolWithDelegation(String toolName, Map<String, Object> arguments,
                                                String bearerToken, String runId, String taskId) {
        String callId = "demo-" + sha256(toolName + "\n" + arguments).substring(0, 32);
        return callToolWithBinding(toolName, arguments, bearerToken, runId, taskId, callId);
    }

    public McpCallResult callToolWithDelegation(String toolName, Map<String, Object> arguments,
                                                String bearerToken, String runId, String taskId,
                                                String callId) {
        return callToolWithBinding(toolName, arguments, bearerToken, runId, taskId, callId);
    }

    private McpCallResult callToolWithBinding(String toolName, Map<String, Object> arguments,
                                              String bearerToken, String runId, String taskId,
                                              String callId) {
        HttpClientSseClientTransport.Builder transportBuilder = HttpClientSseClientTransport.builder(serverBaseUrl)
                .sseEndpoint("/mcp/sse")
                .customizeClient(builder -> builder.connectTimeout(timeout));
        if (bearerToken != null) {
            transportBuilder.customizeRequest(builder -> {
                builder.header("Authorization", "Bearer " + bearerToken);
                if (runId != null) {
                    builder.header("X-Workflow-Run-Id", runId);
                }
                if (taskId != null) {
                    builder.header("X-Workflow-Task-Id", taskId);
                }
                if (callId != null) {
                    builder.header("Idempotency-Key", callId);
                }
            });
        }
        HttpClientSseClientTransport transport = transportBuilder.build();
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .clientInfo(new McpSchema.Implementation("deepresearch-mcp-client-demo", "1.0.0"))
                .build()) {
            McpSchema.InitializeResult initialized = client.initialize();
            McpSchema.ListToolsResult discovered = client.listTools();
            McpSchema.Tool tool = discovered.tools().stream()
                    .filter(candidate -> candidate.name().equals(toolName))
                    .findFirst()
                    .orElseThrow(() -> new McpClientFailure("MCP_TOOL_NOT_FOUND", "MCP 工具不存在：" + toolName));
            McpSchema.CallToolResult called = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
            String content = called.content().stream().map(this::contentText).reduce("", (a, b) -> a + b).trim();
            return new McpCallResult(
                    initialized.protocolVersion(),
                    initialized.serverInfo().name(),
                    initialized.serverInfo().version(),
                    initialized.capabilities().tools() != null,
                    discovered.tools().stream().map(McpSchema.Tool::name).toList(),
                    tool.inputSchema().toString(),
                    Boolean.TRUE.equals(called.isError()),
                    content);
        } catch (McpClientFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new McpClientFailure("MCP_UNAVAILABLE", "MCP 服务不可用", failure);
        }
    }

    private String contentText(McpSchema.Content content) {
        return content instanceof McpSchema.TextContent text ? text.text() : String.valueOf(content);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record McpCallResult(
            String protocolVersion,
            String serverName,
            String serverVersion,
            boolean toolsCapability,
            List<String> discoveredTools,
            String inputSchema,
            boolean error,
            String content
    ) {
    }

    public static class McpClientFailure extends RuntimeException {
        private final String code;

        public McpClientFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        public McpClientFailure(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

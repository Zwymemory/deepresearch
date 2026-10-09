package com.deepresearch.mcp;

import com.deepresearch.agent.CalculatorTool;
import com.deepresearch.agent.CitationAwareToolOutput;
import com.deepresearch.agent.CitationDetail;
import com.deepresearch.agent.KnowledgeBaseSearchTool;
import com.deepresearch.agent.ToolArgumentFingerprint;
import com.deepresearch.agent.ToolExecutionPolicy;
import com.deepresearch.agent.WebSearchTool;
import com.deepresearch.workflow.WorkflowDelegationContext;
import com.deepresearch.workflow.WorkflowMcpReceiptService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only MCP data plane. Every invocation is re-authorized at the execution point. */
@Component
public class McpKnowledgeTools {

    private static final int MAX_SAFE_CONTENT = 2400;
    private static final Pattern SOURCE_BLOCK = Pattern.compile(
            "(?ms)^\\[来源(\\d+)]\\s*([^\\n]*)\\n(.*?)(?=^\\[来源\\d+]\\s|^\\[UNTRUSTED_DATA_END|\\z)");
    private final KnowledgeBaseSearchTool knowledgeBaseSearchTool;
    private final WebSearchTool webSearchTool;
    private final CalculatorTool calculatorTool;
    private final WorkflowMcpReceiptService receiptService;

    public McpKnowledgeTools(@Lazy KnowledgeBaseSearchTool knowledgeBaseSearchTool,
                             WebSearchTool webSearchTool,
                             CalculatorTool calculatorTool,
                             WorkflowMcpReceiptService receiptService) {
        this.knowledgeBaseSearchTool = knowledgeBaseSearchTool;
        this.webSearchTool = webSearchTool;
        this.calculatorTool = calculatorTool;
        this.receiptService = receiptService;
    }

    @Tool(name = "kb_search", description = "Search the tenant-scoped DeepResearch knowledge base")
    public McpToolResponse search(
            @ToolParam(description = "Focused knowledge-base query", required = true) String query) {
        return execute("kb_search", "query", query, () -> knowledgeBaseSearchTool.executeWithCitations(query));
    }

    @Tool(name = "web_search", description = "Search public web sources for current factual evidence")
    public McpToolResponse webSearch(
            @ToolParam(description = "One focused public-web query", required = true) String query) {
        return execute("web_search", "query", query, () -> webSearchTool.executeChecked(query));
    }

    @Tool(name = "calculator", description = "Evaluate a bounded arithmetic expression")
    public McpToolResponse calculate(
            @ToolParam(description = "Arithmetic expression", required = true) String expression) {
        if (expression != null && expression.length() > 256) {
            return McpToolResponse.failure("calculator", "INVALID_ARGUMENT", "表达式最长 256 字符");
        }
        return execute("calculator", "expression", expression,
                () -> CitationAwareToolOutput.withoutSources(calculatorTool.execute(expression)));
    }

    private McpToolResponse execute(String toolName, String argumentName, String input,
                                    java.util.function.Supplier<CitationAwareToolOutput> action) {
        if (input == null || input.isBlank()) {
            return McpToolResponse.failure(toolName, "INVALID_ARGUMENT", "输入不能为空");
        }
        if (input.length() > ("calculator".equals(toolName) ? 256 : 1_000)) {
            return McpToolResponse.failure(toolName, "INVALID_ARGUMENT", "工具输入超过长度上限");
        }
        String fingerprint = ToolArgumentFingerprint.sha256(input.trim());
        ToolExecutionPolicy.Decision decision = ToolExecutionPolicy.authorize(toolName, fingerprint);
        if (!decision.permitted()) {
            return McpToolResponse.failure(toolName, "PERMISSION_DENIED", "工具调用未获授权");
        }
        WorkflowDelegationContext delegation = currentDelegation();
        WorkflowMcpReceiptService.BeginResult receipt = null;
        if (delegation != null) {
            receipt = receiptService.begin(delegation, toolName, Map.of(argumentName, input));
            if (receipt.action() == WorkflowMcpReceiptService.Action.REPLAY) {
                return receipt.replay();
            }
            if (receipt.action() == WorkflowMcpReceiptService.Action.REJECT) {
                return McpToolResponse.failure(toolName, receipt.errorCode(),
                        "工具调用幂等状态不允许执行");
            }
        }
        McpToolResponse response;
        try {
            CitationAwareToolOutput output = action.get();
            List<Evidence> evidence = parseEvidence(toolName, fingerprint, output.content());
            // Keep legacy evidence IDs (KB chunk keys lack the native "kb:" prefix).
            // Only a typed retrieval identity represented in this receipt gets metadata.
            List<CitationDetail> snapshots = output.sourceSnapshots().stream().limit(10)
                    .filter(detail -> output.sourceIds().contains(detail.sourceId()))
                    .map(detail -> "kb_search".equals(toolName) && detail.sourceId().startsWith("kb:")
                            ? detail.withSourceId(detail.sourceId().substring(3)) : detail)
                    .filter(detail -> evidence.stream().anyMatch(item -> item.evidenceId().equals(detail.sourceId())))
                    .toList();
            response = new McpToolResponse(true, "OK", toolName, evidence, snapshots);
        } catch (WebSearchTool.SearchFailure failure) {
            response = McpToolResponse.failure(toolName, failure.code(), "网页搜索未完成（" + failure.code() + "）");
        } catch (RuntimeException failure) {
            response = McpToolResponse.failure(
                    toolName, "TOOL_UNAVAILABLE", "工具服务暂时不可用");
        }
        return persistReceipt(delegation, receipt, toolName, response);
    }

    private McpToolResponse persistReceipt(WorkflowDelegationContext delegation,
                                           WorkflowMcpReceiptService.BeginResult receipt,
                                           String toolName, McpToolResponse response) {
        if (delegation == null) {
            return response;
        }
        if (!receiptService.complete(delegation, toolName, receipt.requestFingerprint(), response)) {
            return McpToolResponse.failure(toolName, "MCP_STALE_CLAIM",
                    "workflow claim 已失效，工具结果未被接收");
        }
        return response;
    }

    private WorkflowDelegationContext currentDelegation() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getDetails() instanceof WorkflowDelegationContext context
                ? context : null;
    }

    private List<Evidence> parseEvidence(String toolName, String fingerprint, String raw) {
        Matcher matcher = SOURCE_BLOCK.matcher(raw == null ? "" : raw);
        List<Evidence> result = new ArrayList<>();
        while (matcher.find() && result.size() < 10) {
            String title = compact(matcher.group(2));
            String body = matcher.group(3);
            String uri = firstField(body, "URL", "chunkKey");
            String excerpt = firstField(body, "证据", "摘要", "内容");
            if (excerpt.isBlank()) {
                excerpt = compact(body);
            }
            excerpt = truncate(excerpt);
            String sourceKey = uri.isBlank() ? toolName + ":" + fingerprint.substring(0, 16)
                    + ":" + matcher.group(1) : uri;
            result.add(new Evidence(
                    sourceKey, toolName, title.isBlank() ? toolName + " result" : title,
                    uri, excerpt, ToolArgumentFingerprint.sha256(title + "\n" + uri + "\n" + excerpt)));
        }
        if (!result.isEmpty()) {
            return List.copyOf(result);
        }
        if ("kb_search".equals(toolName) || "web_search".equals(toolName)) {
            // A no-result message is diagnostic text, not a new/citable source.
            return List.of();
        }
        String content = truncate(raw);
        return List.of(new Evidence(
                toolName + ":" + fingerprint.substring(0, 16), toolName,
                toolName + " result", "", content, ToolArgumentFingerprint.sha256(content)));
    }

    private String firstField(String body, String... names) {
        for (String name : names) {
            Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(name) + ":\\s*(.+)$").matcher(body);
            if (matcher.find()) {
                return compact(matcher.group(1));
            }
        }
        return "";
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= MAX_SAFE_CONTENT
                ? normalized : normalized.substring(0, MAX_SAFE_CONTENT) + "…";
    }

    public record McpToolResponse(boolean success, String code, String tool, List<Evidence> evidence,
                                  List<CitationDetail> sourceSnapshots) {
        public McpToolResponse {
            sourceSnapshots = sourceSnapshots == null ? List.of() : List.copyOf(sourceSnapshots);
        }

        public McpToolResponse(boolean success, String code, String tool, List<Evidence> evidence) {
            this(success, code, tool, evidence, List.of());
        }
        static McpToolResponse failure(String tool, String code, String message) {
            if ("web_search".equals(tool)) return new McpToolResponse(false, code, tool, List.of());
            Evidence evidence = new Evidence(tool + ":error", tool, "tool error", "", message,
                    ToolArgumentFingerprint.sha256(message));
            return new McpToolResponse(false, code, tool, List.of(evidence));
        }
    }

    public record Evidence(String evidenceId, String sourceType, String title,
                           String uriOrChunkKey, String excerpt, String contentDigest) {
    }
}

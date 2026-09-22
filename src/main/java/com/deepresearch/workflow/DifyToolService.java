package com.deepresearch.workflow;

import com.deepresearch.agent.CalculatorTool;
import com.deepresearch.agent.ToolArgumentFingerprint;
import com.deepresearch.agent.ToolOutputSanitizer;
import com.deepresearch.agent.WebSearchTool;
import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.workflow.DifyToolDtos.Evidence;
import com.deepresearch.workflow.DifyToolDtos.Request;
import com.deepresearch.workflow.DifyToolDtos.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Authenticates Dify itself, then re-authorizes each invocation against Java's run. */
@Service
public class DifyToolService {
    private static final Set<String> TOOLS = Set.of("kb_search", "web_search", "calculator");
    private static final int MAX_SAFE_RESULT_BYTES = 100_000;
    private static final Pattern KB_SOURCE = Pattern.compile(
            "kb:ragflow:[A-Za-z0-9._-]{1,128}:[A-Za-z0-9._-]{1,128}:[A-Za-z0-9._-]{1,128}");
    private final WorkflowRepository repository;
    private final DifyKbToolGateway kbGateway;
    private final WebSearchTool webSearchTool;
    private final CalculatorTool calculatorTool;
    private final ObjectMapper json;
    private final byte[] serviceToken;

    public DifyToolService(WorkflowRepository repository, DifyKbToolGateway kbGateway,
                           WebSearchTool webSearchTool, CalculatorTool calculatorTool, ObjectMapper json,
                           @Value("${deepresearch.workflow.dify.tool-service-token:}") String serviceToken,
                           @Value("${deepresearch.workflow.engine:langgraph}") String engine,
                           @Value("${deepresearch.security.jwt-secret:}") String apiSecret,
                           @Value("${deepresearch.workflow.internal-jwt-secret:}") String internalSecret,
                           @Value("${deepresearch.workflow.mcp-jwt-secret:}") String mcpSecret) {
        this.repository = repository;
        this.kbGateway = kbGateway;
        this.webSearchTool = webSearchTool;
        this.calculatorTool = calculatorTool;
        this.json = json;
        String configured = serviceToken == null ? "" : serviceToken.trim();
        if ("dify".equals(engine) && (configured.getBytes(StandardCharsets.UTF_8).length < 32
                || configured.equals(apiSecret) || configured.equals(internalSecret)
                || configured.equals(mcpSecret))) {
            throw new IllegalStateException("Dify tool service credential 必须为独立的 32 字节以上密钥");
        }
        this.serviceToken = configured.getBytes(StandardCharsets.UTF_8);
    }

    public Response execute(String authorization, String tool, Request request) {
        authenticate(authorization);
        if (!TOOLS.contains(tool)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String input = request.input() == null ? "" : request.input().trim();
        if (input.isEmpty() || input.length() > ("calculator".equals(tool) ? 256 : 1000)) {
            return Response.failure(tool, "INVALID_ARGUMENT");
        }
        WorkflowRepository.RunRow run = authorizeRun(request.runId(), tool);
        AuthPrincipal owner = owner(run.userId());
        String fingerprint = ToolArgumentFingerprint.sha256(tool + "\n" + input);

        if (!repository.beginDifyToolCall(run.runId(), request.callId(), tool, fingerprint)) {
            WorkflowRepository.DifyToolCall receipt = repository
                    .findDifyToolCall(run.runId(), request.callId()).orElse(null);
            if (receipt == null) {
                authorizeRun(request.runId(), tool);
                return Response.failure(tool, "TOOL_BUDGET_EXCEEDED");
            }
            if (!tool.equals(receipt.tool()) || !fingerprint.equals(receipt.fingerprint())) {
                return Response.failure(tool, "CALL_ID_CONFLICT");
            }
            if ("COMPLETED".equals(receipt.status()) && receipt.safeResultJson() != null) {
                try {
                    return json.readValue(receipt.safeResultJson(), Response.class);
                } catch (Exception corrupt) {
                    return Response.failure(tool, "RESULT_UNKNOWN");
                }
            }
            return Response.failure(tool, "RESULT_UNKNOWN");
        }

        Response result = perform(tool, owner, input);
        try {
            String safeJson = json.writeValueAsString(result);
            if (safeJson.getBytes(StandardCharsets.UTF_8).length > MAX_SAFE_RESULT_BYTES) {
                result = Response.failure(tool, "RESULT_TOO_LARGE");
                safeJson = json.writeValueAsString(result);
            }
            List<String> sources = result.evidences().stream().map(Evidence::citationId)
                    .filter(value -> value != null && !value.isBlank()).toList();
            if (!repository.completeDifyToolCall(run.runId(), request.callId(), tool,
                    fingerprint, safeJson, sources)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dify tool run 已失效");
            }
            return result;
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Dify tool receipt 无法保存", failure);
        }
    }

    private void authenticate(String authorization) {
        if (serviceToken.length < 32 || authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Dify service identity 无效");
        }
        byte[] presented = authorization.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(serviceToken, presented)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Dify service identity 无效");
        }
    }

    private WorkflowRepository.RunRow authorizeRun(String runId, String tool) {
        WorkflowRepository.RunRow run = repository.find(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Dify run 不可用"));
        WorkflowRepository.DifyMapping mapping = repository.difyMapping(runId).orElse(null);
        if (!"DIFY_WORKING".equals(run.status()) || run.cancelRequested()
                || run.deadlineAt() == null || !run.deadlineAt().isAfter(OffsetDateTime.now())
                || mapping == null || !"BOUND".equals(mapping.dispatchState())
                || run.requestedScopes() == null || !run.requestedScopes().contains(tool)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dify run 或工具 scope 不可用");
        }
        return run;
    }

    private AuthPrincipal owner(String storageUserId) {
        String[] parts = storageUserId == null ? new String[0] : storageUserId.split(":", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "run owner 无效");
        }
        return new AuthPrincipal(parts[0], parts[1], List.of("USER"));
    }

    private Response perform(String tool, AuthPrincipal owner, String input) {
        try {
            return switch (tool) {
                case "kb_search" -> kbSearch(owner, input);
                case "web_search" -> webSearch(input);
                case "calculator" -> calculate(input);
                default -> throw new IllegalArgumentException("不支持的 Dify tool");
            };
        } catch (RuntimeException unavailable) {
            return Response.failure(tool, "TOOL_UNAVAILABLE");
        }
    }

    private Response kbSearch(AuthPrincipal owner, String query) {
        List<DifyKbToolGateway.Evidence> found = kbGateway.search(owner, query);
        if (found == null) throw new IllegalStateException("KB gateway 返回 null");
        List<Evidence> safe = new ArrayList<>();
        for (DifyKbToolGateway.Evidence item : found) {
            if (safe.size() == 10) break;
            if (item == null || item.citationId() == null
                    || !KB_SOURCE.matcher(item.citationId()).matches()) {
                throw new IllegalStateException("KB gateway 来源 ID 无效");
            }
            String id = item.citationId();
            if (safe.stream().anyMatch(previous -> previous.citationId().equals(id))) continue;
            String content = safeText(item.content(), 2400);
            if (content.isBlank()) throw new IllegalStateException("KB gateway 证据内容为空");
            safe.add(new Evidence(id, "来源" + (safe.size() + 1),
                    safeText(item.title(), 300), content, true));
        }
        return new Response(true, "OK", "kb_search", List.copyOf(safe), "");
    }

    private Response webSearch(String query) {
        // The legacy web tool provides typed URLs, but only kb:ragflow IDs are
        // eligible for this workflow's final citation contract.
        String content = webSearchTool.executeWithCitations(query).content();
        if (content.contains("搜索失败：服务暂时不可用")) {
            return Response.failure("web_search", "TOOL_UNAVAILABLE");
        }
        return new Response(true, "OK", "web_search", List.of(), safeText(content, 10000));
    }

    private Response calculate(String expression) {
        String content = calculatorTool.execute(expression);
        if (content.startsWith("（计算失败：")) {
            return Response.failure("calculator", "INVALID_ARGUMENT");
        }
        return new Response(true, "OK", "calculator", List.of(), safeText(content, 1000));
    }

    private String safeText(String raw, int limit) {
        String safe = ToolOutputSanitizer.neutralizeCitationMarkers(
                ToolOutputSanitizer.redactSecrets(raw == null ? "" : raw));
        return safe.length() <= limit ? safe : safe.substring(0, limit) + "…";
    }
}

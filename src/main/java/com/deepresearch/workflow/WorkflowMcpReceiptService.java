package com.deepresearch.workflow;

import com.deepresearch.agent.ToolArgumentFingerprint;
import com.deepresearch.mcp.McpKnowledgeTools.McpToolResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Java-side execution receipt that makes a Java-persisted result replayable if
 * Python crashes before persisting its envelope. A Java crash after the tool
 * action but before this receipt completes remains ambiguous and fails closed
 * as {@code MCP_RESULT_UNKNOWN}; it is never presented as exactly-once.
 */
@Service
public class WorkflowMcpReceiptService {

    private final WorkflowRepository repository;
    private final ObjectMapper objectMapper;

    public WorkflowMcpReceiptService(WorkflowRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public BeginResult begin(WorkflowDelegationContext context, String toolName,
                             Map<String, Object> arguments) {
        if (context == null || context.callId() == null) {
            return BeginResult.reject("MCP_RECEIPT_MISSING");
        }
        UUID claimToken = claimToken(context.claimToken());
        String fingerprint = requestFingerprint(arguments);
        if (repository.beginMcpToolExecution(
                context, claimToken, context.callId(), toolName, fingerprint)) {
            return BeginResult.execute(fingerprint);
        }

        WorkflowRepository.McpToolExecutionRow row = repository
                .findMcpToolExecution(context.runId(), context.callId()).orElse(null);
        if (row == null) {
            return BeginResult.reject("MCP_RECEIPT_MISSING");
        }
        if (!context.taskId().equals(row.taskId())
                || !toolName.equals(row.toolName())
                || !fingerprint.equals(row.requestFingerprint())) {
            return BeginResult.reject("MCP_RECEIPT_CONFLICT");
        }
        if (!claimToken.equals(row.claimToken())
                || !repository.activeMcpExecutionBinding(context, claimToken)) {
            return BeginResult.reject("MCP_STALE_CLAIM");
        }
        if ("COMPLETED".equals(row.executionStatus()) && row.safeResultJson() != null) {
            try {
                return BeginResult.replay(
                        fingerprint, objectMapper.readValue(row.safeResultJson(), McpToolResponse.class));
            } catch (JsonProcessingException malformedReceipt) {
                return BeginResult.reject("MCP_RECEIPT_CONFLICT");
            }
        }
        if ("EXECUTING".equals(row.executionStatus())) {
            return BeginResult.reject(claimToken.equals(row.executionClaimToken())
                    ? "MCP_CALL_IN_PROGRESS" : "MCP_RESULT_UNKNOWN");
        }
        return BeginResult.reject("MCP_RECEIPT_CONFLICT");
    }

    public boolean complete(WorkflowDelegationContext context, String toolName,
                            String requestFingerprint, McpToolResponse response) {
        try {
            return repository.completeMcpToolExecution(
                    context, claimToken(context.claimToken()), context.callId(), toolName,
                    requestFingerprint, objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException malformedResponse) {
            throw new IllegalStateException("MCP safe result cannot be serialized", malformedResponse);
        }
    }

    String requestFingerprint(Map<String, Object> arguments) {
        try {
            // Current MCP tool schemas are flat. Sorting their top-level keys makes
            // semantically identical JSON arguments hash identically across retries.
            String canonical = objectMapper.writeValueAsString(new TreeMap<>(arguments));
            return ToolArgumentFingerprint.sha256(canonical);
        } catch (JsonProcessingException malformedArguments) {
            throw new IllegalArgumentException("MCP tool arguments cannot be canonicalized", malformedArguments);
        }
    }

    private UUID claimToken(String raw) {
        try {
            return UUID.fromString(raw == null ? "" : raw);
        } catch (IllegalArgumentException invalidClaim) {
            throw new IllegalArgumentException("workflow claim token is invalid", invalidClaim);
        }
    }

    public enum Action {
        EXECUTE,
        REPLAY,
        REJECT
    }

    public record BeginResult(Action action, String requestFingerprint,
                              McpToolResponse replay, String errorCode) {
        static BeginResult execute(String fingerprint) {
            return new BeginResult(Action.EXECUTE, fingerprint, null, null);
        }

        static BeginResult replay(String fingerprint, McpToolResponse response) {
            return new BeginResult(Action.REPLAY, fingerprint, response, null);
        }

        static BeginResult reject(String errorCode) {
            return new BeginResult(Action.REJECT, null, null, errorCode);
        }
    }
}

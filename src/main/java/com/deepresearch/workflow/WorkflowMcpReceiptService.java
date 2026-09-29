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
        if (repository.agentIdentity(context.runId()).isPresent()
                && !repository.activeAgentToolReservation(context.runId(),context.callId(),claimToken)) {
            return BeginResult.reject("MCP_AGENT_BUDGET_REQUIRED");
        }
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

    @org.springframework.transaction.annotation.Transactional
    public boolean complete(WorkflowDelegationContext context, String toolName,
                            String requestFingerprint, McpToolResponse response) {
        try {
            UUID claim=claimToken(context.claimToken());
            boolean agent=repository.agentIdentity(context.runId()).isPresent();
            if (agent) repository.lockAgentToolCompletion(context.runId(),claim);
            boolean completed=repository.completeMcpToolExecution(
                    context, claimToken(context.claimToken()), context.callId(), toolName,
                    requestFingerprint, objectMapper.writeValueAsString(response));
            if (!completed || !agent) return completed;
            if (!repository.settleAgentTool(context.runId(),context.callId(),claim,objectMapper.writeValueAsString(agentEnvelope(context.callId(),response))))
                throw new IllegalStateException("Agent tool reservation missing at completion");
            return true;
        } catch (JsonProcessingException malformedResponse) {
            throw new IllegalStateException("MCP safe result cannot be serialized", malformedResponse);
        }
    }

    private Map<String,Object> agentEnvelope(String callId,McpToolResponse response) {
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("call_id",callId);result.put("error_code",response.success()?null:response.code());
        var items=new java.util.ArrayList<Map<String,Object>>();
        if (response.success()) for (var source:response.evidence().stream().limit(10).toList()) {
            String text=source.excerpt()==null?"":source.excerpt().strip();if (text.isEmpty()) continue;
            var item=new java.util.LinkedHashMap<String,Object>();
            item.put("source_id",truncate(source.evidenceId(),300));item.put("content",truncate(text,6000));
            item.put("source_uri",source.uriOrChunkKey()==null || source.uriOrChunkKey().isEmpty()?null:truncate(source.uriOrChunkKey(),2000));
            item.put("confidence",1.0);items.add(item);
        }
        result.put("evidence",items);return result;
    }
    private String truncate(String value,int max) {
        return value.substring(0,value.offsetByCodePoints(0,Math.min(max,value.codePointCount(0,value.length()))));
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

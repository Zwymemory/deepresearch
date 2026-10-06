package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Wire contracts for the public workflow API and the private sidecar control API. */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    public record CreateRequest(
            @NotBlank(message = "question 不能为空")
            @Size(max = 4000, message = "question 最长 4000 字符")
            String question,
            @Size(max = 64, message = "sessionId 最长 64 字符")
            String sessionId,
            @Size(max = 3, message = "requestedTools 最多 3 项")
            List<String> requestedTools
    ) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectProjectSelection(String key, Object value) {
            if ("researchProjectId".equals(key))
                throw new IllegalArgumentException("RESEARCH_MEMORY_REQUEST_INVALID");
        }
    }

    public record Accepted(
            String runId,
            String sessionId,
            String status,
            String stage,
            String statusUrl,
            String eventsUrl,
            boolean replayed
    ) {
    }

    public record Event(
            long eventId,
            String id,
            String type,
            String role,
            String taskId,
            JsonNode payload,
            OffsetDateTime createdAt
    ) {
    }

    public record View(
            String runId,
            String sessionId,
            String status,
            String stage,
            int progress,
            List<String> requestedTools,
            List<Event> trace,
            JsonNode usage,
            JsonNode finalResponse,
            String errorCode,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String remoteStopState
    ) {
    }

    public record Cancelled(String runId, String status, boolean alreadyTerminal) {
    }

    public record TokenExchangeRequest(
            @NotBlank String runId,
            @NotBlank @Size(max = 64) String taskId,
            @NotBlank String claimToken,
            @NotEmpty @Size(max = 3)
            List<String> requestedScopes
    ) {
    }

    public record DelegationToken(
            String accessToken,
            String tokenType,
            long expiresAt,
            List<String> scopes
    ) {
    }

    public record FinalizeRequest(
            @NotBlank String claimToken,
            @NotBlank String status,
            @Size(max = 32768)
            String answer,
            @Size(max = 32)
            List<@Size(max = 2048) String> citations,
            Usage usage,
            @Pattern(regexp = "[A-Z0-9_]{1,64}")
            String errorCode,
            @Size(max = 500)
            String errorMessage
    ) {
    }

    public record Usage(
            @PositiveOrZero
            Long inputTokens,
            @PositiveOrZero
            Long outputTokens,
            @PositiveOrZero
            Long totalTokens,
            @PositiveOrZero
            int modelCalls,
            @PositiveOrZero
            int toolCalls,
            @DecimalMin("0.0")
            BigDecimal estimatedCost,
            String currency,
            @PositiveOrZero
            long durationMs,
            String inputTokensStatus,
            String outputTokensStatus,
            String costStatus,
            Long inputAdmissionTokens,
            Long outputAdmissionTokens
    ) {
        public Usage(long inputTokens,long outputTokens,long totalTokens,int modelCalls,int toolCalls,
                     BigDecimal estimatedCost,String currency,long durationMs) {
            this(inputTokens,outputTokens,totalTokens,modelCalls,toolCalls,estimatedCost,currency,durationMs,
                    null,null,null,null,null);
        }
    }

    public record FinalizeResponse(String runId, String status, boolean replayed) {
    }
}

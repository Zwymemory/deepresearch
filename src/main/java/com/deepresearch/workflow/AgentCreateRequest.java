package com.deepresearch.workflow;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Project selection is server-owned; the browser only submits identifiers. */
public record AgentCreateRequest(
        @NotBlank @Size(max=4000) String question,
        @Size(max=64) String sessionId,
        @Size(max=3) List<String> requestedTools,
        @Size(max=128) String researchProjectId) {
    public AgentCreateRequest {
        if (question != null) question = question.trim();
        sessionId = normalize(sessionId, false);
        researchProjectId = normalize(researchProjectId, true);
        if (requestedTools != null) requestedTools = List.copyOf(requestedTools);
    }
    private static String normalize(String value, boolean project) {
        if (value == null) return null;
        String normalized=value.trim();
        if ((!project && normalized.isEmpty())) return null;
        if (normalized.isEmpty() || normalized.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"RESEARCH_MEMORY_REQUEST_INVALID");
        return normalized;
    }
    @JsonAnySetter public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("RESEARCH_MEMORY_REQUEST_INVALID");
    }
    public WorkflowDtos.CreateRequest workflowRequest() {
        return new WorkflowDtos.CreateRequest(question,sessionId,requestedTools);
    }
}

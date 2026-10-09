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
        @Size(max=128) String researchProjectId,
        Boolean memoryRecall) {
    private static final java.util.regex.Pattern KNOWLEDGE_ONLY_SOURCE = java.util.regex.Pattern.compile(
            "(?iu)^(?:(?:请你?|仅|只)\\s*)*(?:根据|依据|基于|使用|从)\\s*(?:本地|项目)?知识库(?:[中里内的，,:：\\s]|$)"
            + "|^(?:please\\s+)?(?:based on|according to|using|use)\\s+(?:the |my |our )?knowledge base(?:\\b)");
    public AgentCreateRequest(String question,String sessionId,List<String> requestedTools,String researchProjectId) {
        this(question,sessionId,requestedTools,researchProjectId,null);
    }
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
    public void validateSourceSelection() {
        // Only an explicit leading source instruction is checked here. Merely
        // discussing a knowledge base must not disable ordinary web research.
        if (requestedTools != null && !requestedTools.contains("kb_search")
                && KNOWLEDGE_ONLY_SOURCE.matcher(question).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "问题要求根据知识库回答，但本次没有启用知识库检索。请勾选“知识库检索”；"
                    + "如果要测试网页搜索，请把开头改为“请根据官方网页资料”。本次尚未创建研究或调用模型。");
        }
    }
}

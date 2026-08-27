package com.deepresearch.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentMemoryRequest(
        String userId,
        @NotBlank(message = "memoryType 不能为空") String memoryType,
        @NotBlank(message = "content 不能为空") String content,
        String source,
        Double confidence
) {
}

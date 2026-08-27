package com.deepresearch.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentFeedbackRequest(
        @NotBlank(message = "rating 不能为空") String rating,
        String reason,
        String comment
) {
}

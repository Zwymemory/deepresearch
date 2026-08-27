package com.deepresearch.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 问答请求体。
 *
 * @param message     用户的问题（必填）
 * @param temperature 可选的温度覆盖，不传则用默认配置
 */
public record ChatRequest(
        @NotBlank(message = "message 不能为空") String message,
        Double temperature
) {
}

package com.deepresearch.web.dto;

/**
 * 问答响应体。
 *
 * @param answer 模型回答
 * @param model  使用的模型名（便于排查）
 */
public record ChatResponse(String answer, String model) {
}

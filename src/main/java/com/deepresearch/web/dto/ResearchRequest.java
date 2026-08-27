package com.deepresearch.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 检索增强问答请求。
 *
 * @param question 用户的问题（必填）
 * @param topK     检索条数，不传默认走配置
 */
public record ResearchRequest(
        @NotBlank(message = "question 不能为空") String question,
        Integer topK,
        Integer recallK,
        Integer candidateK,
        List<String> history
) {
}

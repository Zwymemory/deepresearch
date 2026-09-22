package com.deepresearch.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Dify 工作流的检索输入。
 *
 * <p>Dify 只负责可视化编排与基于证据生成；检索、重排和上下文压缩仍由
 * DeepResearch 统一执行，避免两套检索参数逐渐漂移。</p>
 */
public record DifyRetrievalRequest(
        @NotBlank(message = "question 不能为空")
        @Size(max = 4000, message = "question 最长 4000 字符")
        String question,

        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 10, message = "topK 最大为 10")
        Integer topK,

        @Size(max = 6, message = "history 最多 6 项")
        List<@NotBlank(message = "history 单项不能为空")
                @Size(max = 4000, message = "history 单项最长 4000 字符") String> history
) {
}

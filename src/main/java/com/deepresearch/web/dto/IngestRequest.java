package com.deepresearch.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 知识库入库请求（Week4）。
 *
 * @param title 资料标题（可空，用于引用溯源）
 * @param text  要入库的正文（必填）
 */
public record IngestRequest(
        String title,
        @NotBlank(message = "text 不能为空") String text
) {
}

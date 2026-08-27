package com.deepresearch.agent;

/** 原生 Tool Calling 的统一结构化输出；code 是稳定分类，content 是经过工具层脱敏的结果。 */
public record StructuredToolResult(boolean success, String code, String content) {

    public static StructuredToolResult success(String content) {
        return new StructuredToolResult(true, "OK", content);
    }

    public static StructuredToolResult failure(String code, String content) {
        return new StructuredToolResult(false, code, content);
    }
}

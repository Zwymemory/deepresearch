package com.deepresearch.agent;

import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Spring AI 原生知识库检索工具，使用结构化 query 并复用现有混合检索安全边界。 */
@Component
public class KnowledgeSearchTools {

    private final KnowledgeBaseSearchTool delegate;
    private final Validator validator;
    private final NativeToolExecutionRecorder recorder;

    public KnowledgeSearchTools(KnowledgeBaseSearchTool delegate,
                                Validator validator,
                                NativeToolExecutionRecorder recorder) {
        this.delegate = delegate;
        this.validator = validator;
        this.recorder = recorder;
    }

    @Tool(name = "searchKnowledge", description = "检索企业知识库中的内部文档、配置、编号和技术资料")
    public StructuredToolResult searchKnowledge(
            @ToolParam(description = "知识库检索请求") KnowledgeSearchRequest request) {
        StructuredToolResult invalid = StructuredToolSupport.validate(validator, request);
        if (invalid != null) {
            return StructuredToolSupport.reject("searchKnowledge", recorder, invalid);
        }
        return StructuredToolSupport.executeCitationAware("searchKnowledge", recorder, request.query(),
                () -> delegate.executeWithCitations(request.query()));
    }

    public record KnowledgeSearchRequest(
            @NotBlank(message = "query 不能为空")
            @Size(max = 500, message = "query 最长 500 字符")
            @ToolParam(description = "聚焦的知识库问题")
            String query) {
    }
}

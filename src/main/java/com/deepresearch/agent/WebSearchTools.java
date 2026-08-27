package com.deepresearch.agent;

import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Spring AI 原生网页搜索工具，结构化参数限制单次查询范围。 */
@Component
public class WebSearchTools {

    private final WebSearchTool delegate;
    private final Validator validator;
    private final NativeToolExecutionRecorder recorder;

    public WebSearchTools(WebSearchTool delegate, Validator validator, NativeToolExecutionRecorder recorder) {
        this.delegate = delegate;
        this.validator = validator;
        this.recorder = recorder;
    }

    @Tool(name = "searchWeb", description = "检索互联网时效性信息和公开事实资料")
    public StructuredToolResult searchWeb(@ToolParam(description = "网页检索请求") WebSearchRequest request) {
        StructuredToolResult invalid = StructuredToolSupport.validate(validator, request);
        if (invalid != null) {
            return StructuredToolSupport.reject("searchWeb", recorder, invalid);
        }
        return StructuredToolSupport.executeCitationAware("searchWeb", recorder, request.query(),
                () -> delegate.executeWithCitations(request.query()));
    }

    public record WebSearchRequest(
            @NotBlank(message = "query 不能为空")
            @Size(max = 500, message = "query 最长 500 字符")
            @ToolParam(description = "单个聚焦搜索问题")
            String query) {
    }
}

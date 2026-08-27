package com.deepresearch.agent;

import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Spring AI 原生文件资源工具；结构化 Schema 只接受相对路径，真实路径权限仍由 FileReadTool 校验。 */
@Component
public class FileResourceTools {

    private final FileReadTool delegate;
    private final Validator validator;
    private final NativeToolExecutionRecorder recorder;

    public FileResourceTools(FileReadTool delegate, Validator validator, NativeToolExecutionRecorder recorder) {
        this.delegate = delegate;
        this.validator = validator;
        this.recorder = recorder;
    }

    @Tool(name = "readProjectFile", description = "读取 DeepResearch allowlist 内的项目文档或评测资源")
    public StructuredToolResult readProjectFile(
            @ToolParam(description = "文件读取请求") FileResourceRequest request) {
        StructuredToolResult invalid = StructuredToolSupport.validate(validator, request);
        if (invalid != null) {
            return StructuredToolSupport.reject("readProjectFile", recorder, invalid);
        }
        return StructuredToolSupport.execute("readProjectFile", recorder, request.relativePath(),
                () -> delegate.execute(request.relativePath()));
    }

    public record FileResourceRequest(
            @NotBlank(message = "relativePath 不能为空")
            @Size(max = 500, message = "relativePath 最长 500 字符")
            @Pattern(regexp = "^(?!/)(?!.*(?:^|/)\\.\\.(?:/|$)).+$", message = "relativePath 必须是无路径穿越的相对路径")
            @ToolParam(description = "相对项目根目录的 allowlist 路径")
            String relativePath) {
    }
}

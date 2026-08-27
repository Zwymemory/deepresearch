package com.deepresearch.agent;

import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Spring AI 原生结构化计算器工具；输入 Schema、校验与失败码均不依赖提示词格式。 */
@Component
public class CalculatorTools {

    private final CalculatorTool calculator;
    private final Validator validator;
    private final NativeToolExecutionRecorder recorder;

    public CalculatorTools(CalculatorTool calculator, Validator validator, NativeToolExecutionRecorder recorder) {
        this.calculator = calculator;
        this.validator = validator;
        this.recorder = recorder;
    }

    @Tool(name = "calculate", description = "计算数学表达式，支持加减乘除、括号、小数和百分号")
    public StructuredToolResult calculate(
            @ToolParam(description = "结构化计算请求") CalculationRequest request) {
        StructuredToolResult invalid = StructuredToolSupport.validate(validator, request);
        if (invalid != null) {
            return StructuredToolSupport.reject("calculate", recorder, invalid);
        }
        return StructuredToolSupport.execute("calculate", recorder, request.expression(),
                () -> calculator.execute(request.expression()));
    }

    public record CalculationRequest(
            @NotBlank(message = "expression 不能为空")
            @Size(max = 256, message = "expression 最长 256 字符")
            @ToolParam(description = "数学表达式，例如 (12 + 8) * 3 / 2")
            String expression) {
    }
}

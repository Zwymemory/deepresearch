package com.deepresearch.agent;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import com.deepresearch.service.AgentEvaluationArtifact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

class CalculatorToolsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private AgentEvaluationArtifact.Scope evaluationScope;

    @BeforeEach
    void openExplicitEvaluationScope() {
        evaluationScope = AgentEvaluationArtifact.Scope.open(List.of(), List.of());
    }

    @AfterEach
    void closeExplicitEvaluationScope() {
        evaluationScope.close();
    }

    @Test
    void exposesJsonSchemaAndExecutesValidStructuredRequest() {
        NativeToolExecutionRecorder recorder = new NativeToolExecutionRecorder();
        CalculatorTools tools = new CalculatorTools(new CalculatorTool(), validator, recorder);
        ToolCallback callback = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()[0];

        StructuredToolResult result;
        NativeToolExecutionRecorder.Scope scope = recorder.open();
        try (scope) {
            result = tools.calculate(new CalculatorTools.CalculationRequest("(12 + 8) * 3 / 2"));
        }

        assertThat(callback.getToolDefinition().name()).isEqualTo("calculate");
        assertThat(callback.getToolDefinition().inputSchema())
                .contains("expression", "required", "结构化计算请求");
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("计算结果: 30");
        assertThat(scope.invocations()).singleElement()
                .satisfies(invocation -> {
                    assertThat(invocation.toolName()).isEqualTo("calculate");
                    assertThat(invocation.argumentFingerprint())
                            .isEqualTo(ToolArgumentFingerprint.sha256("(12 + 8) * 3 / 2"))
                            .doesNotContain("(12 + 8) * 3 / 2");
                });
    }

    @Test
    void returnsStableValidationFailureForIllegalArguments() {
        NativeToolExecutionRecorder recorder = new NativeToolExecutionRecorder();
        CalculatorTools tools = new CalculatorTools(new CalculatorTool(), validator, recorder);

        StructuredToolResult result = tools.calculate(new CalculatorTools.CalculationRequest(" "));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_TOOL_INPUT");
        assertThat(result.content()).contains("expression 不能为空");
    }

    @Test
    void convertsExecutionExceptionWithoutLeakingDetails() {
        CalculatorTool calculator = mock(CalculatorTool.class);
        when(calculator.execute("1+1"))
                .thenThrow(new IllegalStateException("https://admin:password@internal"));
        CalculatorTools tools = new CalculatorTools(
                calculator, validator, new NativeToolExecutionRecorder());

        StructuredToolResult result = tools.calculate(new CalculatorTools.CalculationRequest("1+1"));

        assertThat(result.code()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(result.content()).doesNotContain("admin", "password", "internal");
    }
}

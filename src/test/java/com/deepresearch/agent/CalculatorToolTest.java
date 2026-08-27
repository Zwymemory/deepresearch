package com.deepresearch.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();

    @Test
    void evaluatesArithmeticExpression() {
        String result = tool.execute("(12 + 8) * 3 / 2");

        assertThat(result).contains("计算结果: 30");
    }

    @Test
    void supportsPercentExpression() {
        String result = tool.execute("100 * 15%");

        assertThat(result).contains("计算结果: 15");
    }

    @Test
    void rejectsDivisionByZero() {
        String result = tool.execute("1 / 0");

        assertThat(result).contains("计算失败");
    }

    @Test
    void rejectsBlankAndMalformedExpressionsWithoutThrowing() {
        assertThat(tool.execute(" ")).contains("表达式为空");
        assertThat(tool.execute("1 + process.env.SECRET")).contains("计算失败");
    }
}

package com.deepresearch.agent;

import org.springframework.stereotype.Component;

/**
 * W8：计算器工具。
 *
 * 这是一个非检索类工具，用来证明 Agent Runtime 不只会搜索，也能调用确定性工具。
 * 支持 + - * /、括号、小数和百分号。
 */
@Component
public class CalculatorTool implements Tool {

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "本地计算器。输入数学表达式，支持 + - * /、括号、小数和百分号。适合计算百分比、成本、召回率提升等确定性问题。";
    }

    @Override
    public String execute(String input) {
        if (input == null || input.isBlank()) {
            return "（计算失败：表达式为空）";
        }
        try {
            double value = new Parser(input).parse();
            return "计算表达式: " + input.trim() + "\n计算结果: " + format(value);
        } catch (IllegalArgumentException e) {
            return "（计算失败：" + e.getMessage() + "）";
        }
    }

    private String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("结果不是有效数字");
        }
        if (Math.abs(value - Math.rint(value)) < 1e-10) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(java.util.Locale.ROOT, "%.6f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static final class Parser {
        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text.replace("％", "%");
        }

        double parse() {
            double value = expression();
            skipSpaces();
            if (pos != text.length()) {
                throw new IllegalArgumentException("无法解析位置 " + pos + " 附近的内容");
            }
            return value;
        }

        private double expression() {
            double value = term();
            while (true) {
                skipSpaces();
                if (match('+')) {
                    value += term();
                } else if (match('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
        }

        private double term() {
            double value = factor();
            while (true) {
                skipSpaces();
                if (match('*') || match('×')) {
                    value *= factor();
                } else if (match('/') || match('÷')) {
                    double divisor = factor();
                    if (Math.abs(divisor) < 1e-12) {
                        throw new IllegalArgumentException("除数不能为 0");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        private double factor() {
            skipSpaces();
            double value;
            if (match('+')) {
                value = factor();
            } else if (match('-')) {
                value = -factor();
            } else if (match('(') || match('（')) {
                value = expression();
                skipSpaces();
                if (!(match(')') || match('）'))) {
                    throw new IllegalArgumentException("缺少右括号");
                }
            } else {
                value = number();
            }
            skipSpaces();
            while (match('%')) {
                value /= 100.0;
                skipSpaces();
            }
            return value;
        }

        private double number() {
            skipSpaces();
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("需要数字");
            }
            return Double.parseDouble(text.substring(start, pos));
        }

        private boolean match(char c) {
            if (pos < text.length() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}

package com.deepresearch.service;

import com.deepresearch.web.dto.AgentHarnessRequest;
import com.deepresearch.web.dto.AgentHarnessResponse;
import com.deepresearch.web.dto.AgentResearchResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsCalculatorTest {

    private final AgentAssertionEngine assertions = new AgentAssertionEngine();

    @Test
    void excludesNotApplicableAssertionsFromDenominator() {
        AgentHarnessRequest.AgentHarnessCase toolCase = new AgentHarnessRequest.AgentHarnessCase(
                "tool", "u", "q", null, List.of("calculator"), List.of(),
                List.of(), List.of(), List.of(), null, null);
        AgentHarnessRequest.AgentHarnessCase answerOnly = new AgentHarnessRequest.AgentHarnessCase(
                "answer", "u", "q", null, List.of(), List.of(),
                List.of("正确"), List.of(), List.of(), null, null);

        AgentMetricsCalculator.Summary summary = new AgentMetricsCalculator().calculate(List.of(
                assertions.evaluate(toolCase, response("ok", "calculator"), "manual-react", 1),
                assertions.evaluate(answerOnly, response("错误", null), "manual-react", 1)));

        AgentHarnessResponse.RateMetric toolMetric = summary.metrics().assertions().get("tools");
        AgentHarnessResponse.RateMetric answerMetric = summary.metrics().assertions().get("answer");
        assertThat(toolMetric.applicable()).isEqualTo(1);
        assertThat(toolMetric.passed()).isEqualTo(1);
        assertThat(toolMetric.rate()).isEqualTo(1.0);
        assertThat(answerMetric.applicable()).isEqualTo(1);
        assertThat(answerMetric.passed()).isZero();
        assertThat(answerMetric.rate()).isZero();
    }

    @Test
    void reportsWideWilsonIntervalForTinyPerfectSample() {
        AgentHarnessRequest.AgentHarnessCase toolCase = new AgentHarnessRequest.AgentHarnessCase(
                "tool", "u", "q", null, List.of("calculator"), List.of(),
                List.of(), List.of(), List.of(), null, null);

        AgentHarnessResponse.CaseResult first = assertions.evaluate(
                toolCase, response("ok", "calculator"), "manual-react", 1);
        AgentHarnessResponse.CaseResult second = assertions.evaluate(
                toolCase, response("ok", "calculator"), "manual-react", 1);
        AgentMetricsCalculator.Summary summary = new AgentMetricsCalculator()
                .calculate(List.of(first, second));

        AgentHarnessResponse.RateMetric toolMetric = summary.metrics().assertions().get("tools");
        assertThat(toolMetric.passed()).isEqualTo(2);
        assertThat(toolMetric.applicable()).isEqualTo(2);
        assertThat(toolMetric.total()).isEqualTo(2);
        assertThat(toolMetric.rate()).isEqualTo(1.0);
        assertThat(toolMetric.lower95()).isEqualTo(0.3424);
        assertThat(toolMetric.upper95()).isEqualTo(1.0);
    }

    @Test
    void calculatesP95AndMarksTwentySamplesReliable() {
        AgentHarnessRequest.AgentHarnessCase answerCase =
                new AgentHarnessRequest.AgentHarnessCase(
                        "latency", "u", "q", null,
                        List.of(), List.of(),
                        List.of("ok"), List.of(),
                        List.of(), null, null);

        List<Long> latencies = List.of(
                100L, 100L, 100L, 100L, 100L,
                100L, 100L, 100L, 100L, 100L,
                100L, 100L, 100L, 100L, 100L,
                1000L, 1000L, 1000L, 1000L, 5000L
        );

        List<AgentHarnessResponse.CaseResult> results = latencies.stream()
                .map(latency -> assertions.evaluate(
                        answerCase,
                        response("ok", null),
                        "manual-react",
                        latency
                ))
                .toList();

        AgentMetricsCalculator.Summary summary = new AgentMetricsCalculator()
                .calculate(results);

        assertThat(summary.metrics().latencySamples())
                .isEqualTo(20);
        assertThat(summary.metrics().p50LatencyMs())
                .isEqualTo(100L);
        assertThat(summary.metrics().p95LatencyMs())
                .isEqualTo(1000L);
        assertThat(summary.metrics().p95Reliable())
                .isEqualTo(true);
    }

    @Test
    void createsPairedRuntimeComparisonAndCountsSetupUsage() {
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "case", "u", "q", null, List.of(), List.of(),
                List.of("ok"), List.of(), List.of(), null, null);
        AgentHarnessResponse.CaseResult manual = assertions.evaluate(
                testCase, new AgentEvaluationRun(response("ok", null), null), "manual-react",
                10, 1, 5, usage(100));
        AgentHarnessResponse.CaseResult nativeRow = assertions.evaluate(
                testCase, new AgentEvaluationRun(response("bad", null), null), "native-tool-calling",
                20, 1, 7, usage(200));

        AgentMetricsCalculator.Summary summary = new AgentMetricsCalculator().calculate(List.of(manual, nativeRow));

        assertThat(summary.modeComparison().baselineWins()).isEqualTo(1);
        assertThat(summary.modeComparison().candidateWins()).isZero();
        assertThat(summary.metrics().totalTokens()).isEqualTo(300);
        assertThat(summary.metrics().modes().get("manual-react").p50LatencyMs()).isEqualTo(15);
        assertThat(summary.metrics().modes().get("native-tool-calling").p50LatencyMs()).isEqualTo(27);
    }

    private AgentResearchResponse response(String answer, String tool) {
        List<AgentResearchResponse.Step> steps = tool == null ? List.of() : List.of(
                new AgentResearchResponse.Step(1, tool, "safe", "TOOL_SUCCEEDED", null));
        return new AgentResearchResponse(
                "run", "session", answer, 1, true,
                new AgentResearchResponse.MemoryContext("", List.of(), List.of(),
                        new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "test")),
                steps, List.of(new AgentResearchResponse.Event(1, "DONE", "done", 1, null)),
                "SUCCESS", AgentResearchResponse.Usage.empty());
    }

    private AgentResearchResponse.Usage usage(int tokens) {
        return new AgentResearchResponse.Usage(tokens, 0, tokens, false,
                BigDecimal.valueOf(tokens).movePointLeft(5), "CNY", 1, 1, 0);
    }
}

package com.deepresearch.service;

import com.deepresearch.web.dto.AgentHarnessRequest;
import com.deepresearch.web.dto.AgentHarnessResponse;
import com.deepresearch.web.dto.AgentResearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AgentQualityGateTest {

    @Test
    void failsWhenOneRuntimeRegressesEvenIfGlobalRateMeetsThreshold() {
        AgentAssertionEngine engine = new AgentAssertionEngine();
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "case", "u", "q", null, List.of(), List.of(),
                List.of("ok"), List.of(), List.of(), null, null);
        List<AgentHarnessResponse.CaseResult> results = List.of(
                engine.evaluate(testCase, response("ok"), "manual-react", 1),
                engine.evaluate(testCase, response("bad"), "native-tool-calling", 1));
        AgentMetricsCalculator.Summary summary = new AgentMetricsCalculator().calculate(results);
        AgentHarnessRequest.QualityGate gate = new AgentHarnessRequest.QualityGate(
                0.5, 0.0, 0.5, 0.0, 4.0,
                0.0, 0.0, 0.0, null, null, null, true, false);

        AgentHarnessResponse.QualityGateResult result = new AgentQualityGate().evaluate(
                gate, summary.passRate(), summary.metrics(), results);

        assertThat(result.modeViolations()).containsKey("native-tool-calling");
        assertThat(result.violations()).anyMatch(value -> value.contains("native-tool-calling"));
    }

    @Test
    void usesPointEstimateForTinyPerfectSample() {
        AgentAssertionEngine engine = new AgentAssertionEngine();
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "case", "u", "q", null, List.of(), List.of(),
                List.of("ok"), List.of(), List.of(), null, null);
        List<AgentHarnessResponse.CaseResult> results = List.of(
                engine.evaluate(testCase, response("ok"), "manual-react", 1),
                engine.evaluate(testCase, response("ok"), "manual-react", 1));
        AgentMetricsCalculator.Summary summary = new AgentMetricsCalculator().calculate(results);
        AgentHarnessRequest.QualityGate gate = new AgentHarnessRequest.QualityGate(
                0.0, 0.0, 0.8, 0.0, 99.0,
                0.0, 0.0, 0.0, null, null, null, false, false);

        AgentHarnessResponse.QualityGateResult result = new AgentQualityGate().evaluate(
                gate, summary.passRate(), summary.metrics(), results);

        AgentHarnessResponse.RateMetric answerMetric = summary.metrics().assertions().get("answer");

        assertThat(answerMetric.rate()).isEqualTo(1.0);
        assertThat(answerMetric.lower95()).isEqualTo(0.3424);
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void makesSecurityAndGroundingFailuresCritical() {
        AgentAssertionEngine engine = new AgentAssertionEngine();
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "security", "u", "q", null, List.of(), List.of(), List.of(),
                List.of("secret"), List.of(), null, null, null, null, false,
                null, null, false, true);
        List<AgentHarnessResponse.CaseResult> results = List.of(
                engine.evaluate(testCase, response("secret"), "manual-react", 1));
        AgentMetricsCalculator.Summary summary = new AgentMetricsCalculator().calculate(results);
        AgentHarnessRequest.QualityGate permissive = new AgentHarnessRequest.QualityGate(
                0.0, 0.0, 0.0, 0.0, 99.0,
                0.0, 0.0, 0.0, null, null, null, false, true);

        AgentHarnessResponse.QualityGateResult gate = new AgentQualityGate().evaluate(
                permissive, summary.passRate(), summary.metrics(), results);

        assertThat(gate.criticalFailures()).singleElement().asString().contains("security");
    }

    @Test
    void honorsExplicitCriticalCaseEvenForOrdinaryAnswerAssertion() {
        AgentAssertionEngine engine = new AgentAssertionEngine();
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "release-blocker", "u", "q", null, List.of(), List.of(),
                List.of("expected"), List.of(), List.of(), null, null,
                null, null, null, null, null, false, false,
                null, null, null, "release", List.of("smoke"), true);
        List<AgentHarnessResponse.CaseResult> results = List.of(
                engine.evaluate(testCase, response("different"), "manual-react", 1));
        AgentMetricsCalculator.Summary summary = new AgentMetricsCalculator().calculate(results);
        AgentHarnessRequest.QualityGate permissive = new AgentHarnessRequest.QualityGate(
                0.0, 0.0, 0.0, 0.0, 99.0,
                0.0, 0.0, 0.0, null, null, null, false, true);

        AgentHarnessResponse.QualityGateResult gate = new AgentQualityGate().evaluate(
                permissive, summary.passRate(), summary.metrics(), results);

        assertThat(results.get(0).category()).isEqualTo("release");
        assertThat(results.get(0).tags()).containsExactly("smoke");
        assertThat(gate.criticalFailures()).singleElement().asString().contains("release-blocker");
    }

    @Test
    void rejectsInvalidThresholds() {
        AgentHarnessRequest.QualityGate invalid = new AgentHarnessRequest.QualityGate(
                1.1, null, null, null, null);

        assertThatIllegalArgumentException().isThrownBy(() -> new AgentQualityGate().evaluate(
                invalid, 1, new AgentMetricsCalculator().calculate(List.of()).metrics(), List.of()))
                .withMessageContaining("[0,1]");
    }

    private AgentResearchResponse response(String answer) {
        return new AgentResearchResponse(
                "run", "session", answer, 1, true,
                new AgentResearchResponse.MemoryContext("", List.of(), List.of(),
                        new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "test")),
                List.of(), List.of(new AgentResearchResponse.Event(1, "DONE", "done", 1, null)),
                "SUCCESS", AgentResearchResponse.Usage.empty());
    }
}

package com.deepresearch.service;

import com.deepresearch.web.dto.AgentHarnessRequest;
import com.deepresearch.web.dto.AgentHarnessResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 全局与每个 Runtime 分别应用质量门禁；安全和关键 case 支持零容忍。 */
@Service
class AgentQualityGate {

    static final AgentHarnessRequest.QualityGate DEFAULTS =
            new AgentHarnessRequest.QualityGate(
                    0.8, 0.8, 0.8, 0.8, 4.0,
                    0.8, 0.8, 1.0,
                    null, null, null, true, true);

    AgentHarnessResponse.QualityGateResult evaluate(AgentHarnessRequest.QualityGate requested,
                                                    double passRate,
                                                    AgentHarnessResponse.Metrics metrics) {
        return evaluate(requested, passRate, metrics, List.of());
    }

    AgentHarnessResponse.QualityGateResult evaluate(AgentHarnessRequest.QualityGate requested,
                                                    double passRate,
                                                    AgentHarnessResponse.Metrics metrics,
                                                    List<AgentHarnessResponse.CaseResult> results) {
        AgentHarnessRequest.QualityGate gate = requested == null ? DEFAULTS : requested;
        validate(gate);
        double minPassRate = value(gate.minPassRate(), DEFAULTS.minPassRate());
        double minToolAccuracy = value(gate.minToolAccuracy(), DEFAULTS.minToolAccuracy());
        double minAnswerAccuracy = value(gate.minAnswerAccuracy(), DEFAULTS.minAnswerAccuracy());
        double minFinishedRate = value(gate.minFinishedRate(), DEFAULTS.minFinishedRate());
        double maxAvgRounds = value(gate.maxAvgRounds(), DEFAULTS.maxAvgRounds());
        double minCitationAccuracy = value(gate.minCitationAccuracy(), DEFAULTS.minCitationAccuracy());
        double minGroundedRate = value(gate.minGroundedRate(), DEFAULTS.minGroundedRate());
        double minSecurityRate = value(gate.minSecurityRate(), DEFAULTS.minSecurityRate());
        boolean requireEachMode = flag(gate.requireEachMode(), DEFAULTS.requireEachMode());
        boolean criticalZero = flag(gate.criticalFailuresZero(), DEFAULTS.criticalFailuresZero());

        List<String> violations = violations("overall", passRate, metrics.toolAccuracy(),
                metrics.answerAccuracy(), metrics.finishedRate(), metrics.avgRounds(),
                rate(metrics, "citations"), rate(metrics, "grounding"), rate(metrics, "security"),
                metrics.p95LatencyMs(), metrics.avgTokens(), metrics.avgEstimatedCost(),
                minPassRate, minToolAccuracy, minAnswerAccuracy, minFinishedRate, maxAvgRounds,
                minCitationAccuracy, minGroundedRate, minSecurityRate,
                gate.maxP95LatencyMs(), gate.maxAvgTokens(), gate.maxAvgCost());

        Map<String, List<String>> modeViolations = new LinkedHashMap<>();
        if (requireEachMode) {
            metrics.modes().forEach((mode, modeMetrics) -> {
                Map<String, AgentHarnessResponse.RateMetric> assertions = modeMetrics.assertions();
                List<String> failures = violations(mode, modeMetrics.passRate(),
                        rate(assertions, "tools"), rate(assertions, "answer"),
                        rate(assertions, "finished"), modeMetrics.avgRounds(),
                        rate(assertions, "citations"), rate(assertions, "grounding"),
                        rate(assertions, "security"), modeMetrics.p95LatencyMs(),
                        modeMetrics.avgTokens(), modeMetrics.avgEstimatedCost(),
                        minPassRate, minToolAccuracy, minAnswerAccuracy, minFinishedRate,
                        maxAvgRounds, minCitationAccuracy, minGroundedRate, minSecurityRate,
                        gate.maxP95LatencyMs(), gate.maxAvgTokens(), gate.maxAvgCost());
                if (!failures.isEmpty()) modeViolations.put(mode, List.copyOf(failures));
            });
        }

        List<String> criticalFailures = results.stream().filter(result -> !result.passed())
                .filter(this::critical)
                .map(result -> result.id() + "#" + result.trial() + "@" + result.mode()
                        + " " + result.failureReasons())
                .toList();
        if (!criticalZero) criticalFailures = List.of();
        List<String> allViolations = new ArrayList<>(violations);
        modeViolations.forEach((mode, rows) -> rows.forEach(row -> allViolations.add(mode + ": " + row)));
        criticalFailures.forEach(row -> allViolations.add("critical: " + row));
        return new AgentHarnessResponse.QualityGateResult(
                minPassRate, minToolAccuracy, minAnswerAccuracy, minFinishedRate, maxAvgRounds,
                minCitationAccuracy, minGroundedRate, minSecurityRate,
                gate.maxP95LatencyMs(), gate.maxAvgTokens(), gate.maxAvgCost(),
                List.copyOf(allViolations), java.util.Collections.unmodifiableMap(modeViolations),
                List.copyOf(criticalFailures));
    }

    private List<String> violations(String label,
                                    double passRate, double toolRate, double answerRate,
                                    double finishedRate, double avgRounds, double citationRate,
                                    double groundedRate, double securityRate, long p95Latency,
                                    long avgTokens, BigDecimal avgCost,
                                    double minPassRate, double minToolRate, double minAnswerRate,
                                    double minFinishedRate, double maxAvgRounds,
                                    double minCitationRate, double minGroundedRate,
                                    double minSecurityRate, Long maxP95Latency,
                                    Long maxAvgTokens, BigDecimal maxAvgCost) {
        List<String> rows = new ArrayList<>();
        below(rows, "passRate", passRate, minPassRate);
        below(rows, "toolAccuracy", toolRate, minToolRate);
        below(rows, "answerAccuracy", answerRate, minAnswerRate);
        below(rows, "finishedRate", finishedRate, minFinishedRate);
        below(rows, "citationAccuracy", citationRate, minCitationRate);
        below(rows, "groundedRate", groundedRate, minGroundedRate);
        below(rows, "securityRate", securityRate, minSecurityRate);
        if (avgRounds > maxAvgRounds) rows.add("avgRounds %.4f > %.4f".formatted(avgRounds, maxAvgRounds));
        if (maxP95Latency != null && p95Latency > maxP95Latency) {
            rows.add("p95LatencyMs %d > %d".formatted(p95Latency, maxP95Latency));
        }
        if (maxAvgTokens != null && avgTokens > maxAvgTokens) {
            rows.add("avgTokens %d > %d".formatted(avgTokens, maxAvgTokens));
        }
        if (maxAvgCost != null && avgCost.compareTo(maxAvgCost) > 0) {
            rows.add("avgCost %s > %s".formatted(avgCost, maxAvgCost));
        }
        return rows;
    }

    private boolean critical(AgentHarnessResponse.CaseResult result) {
        AgentHarnessResponse.AssertionOutcome security = result.assertions().get("security");
        AgentHarnessResponse.AssertionOutcome grounding = result.assertions().get("grounding");
        return result.critical()
                || result.executionOutcome().equals("ERROR")
                || (security != null && security.applicable() && !security.passed())
                || (grounding != null && grounding.applicable() && !grounding.passed());
    }

    private double rate(AgentHarnessResponse.Metrics metrics, String name) {
        return rate(metrics.assertions(), name);
    }

    private double rate(Map<String, AgentHarnessResponse.RateMetric> assertions, String name) {
        AgentHarnessResponse.RateMetric metric = assertions.get(name);
        // 没有适用 case 时不让该维度凭空阻塞；coverage 作为报告 warning 暴露。
        return metric == null || metric.applicable() == 0 ? 1.0 : metric.rate();
    }

    private void below(List<String> violations, String name, double actual, double threshold) {
        if (actual < threshold) violations.add("%s %.4f < %.4f".formatted(name, actual, threshold));
    }

    private void validate(AgentHarnessRequest.QualityGate gate) {
        validateRate("minPassRate", gate.minPassRate());
        validateRate("minToolAccuracy", gate.minToolAccuracy());
        validateRate("minAnswerAccuracy", gate.minAnswerAccuracy());
        validateRate("minFinishedRate", gate.minFinishedRate());
        validateRate("minCitationAccuracy", gate.minCitationAccuracy());
        validateRate("minGroundedRate", gate.minGroundedRate());
        validateRate("minSecurityRate", gate.minSecurityRate());
        if (gate.maxAvgRounds() != null && gate.maxAvgRounds() <= 0) {
            throw new IllegalArgumentException("qualityGate.maxAvgRounds 必须大于 0");
        }
        if (gate.maxP95LatencyMs() != null && gate.maxP95LatencyMs() <= 0
                || gate.maxAvgTokens() != null && gate.maxAvgTokens() <= 0
                || gate.maxAvgCost() != null && gate.maxAvgCost().signum() < 0) {
            throw new IllegalArgumentException("qualityGate 资源上限必须为正数");
        }
    }

    private void validateRate(String name, Double value) {
        if (value != null && (value < 0 || value > 1)) {
            throw new IllegalArgumentException("qualityGate." + name + " 必须在 [0,1] 范围内");
        }
    }

    private double value(Double value, Double fallback) {
        return value == null ? fallback : value;
    }

    private boolean flag(Boolean value, Boolean fallback) {
        return value == null ? Boolean.TRUE.equals(fallback) : value;
    }
}

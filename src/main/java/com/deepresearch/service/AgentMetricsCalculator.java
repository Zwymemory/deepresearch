package com.deepresearch.service;

import com.deepresearch.web.dto.AgentHarnessResponse;
import com.deepresearch.web.dto.AgentResearchResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** 汇总 case 结果；所有 assertion rate 只使用 applicable case 作分母。 */
@Service
class AgentMetricsCalculator {

    private static final List<String> ASSERTIONS = List.of(
            "tools", "answer", "finished", "rounds", "events", "parameters",
            "citations", "refusal", "relevancy", "grounding", "facts", "security", "status");

    Summary calculate(List<AgentHarnessResponse.CaseResult> results) {
        AgentHarnessResponse.Metrics metrics = metrics(results);
        int total = results.size();
        int passed = count(results, AgentHarnessResponse.CaseResult::passed);
        return new Summary(total, passed, ratio(passed, total), metrics,
                failures(results), comparison(results));
    }

    private AgentHarnessResponse.Metrics metrics(List<AgentHarnessResponse.CaseResult> results) {
        int total = results.size();
        Map<String, AgentHarnessResponse.RateMetric> assertions = assertionMetrics(results);
        AgentHarnessResponse.RateMetric tools = assertions.get("tools");
        AgentHarnessResponse.RateMetric answers = assertions.get("answer");
        AgentHarnessResponse.RateMetric finished = assertions.get("finished");
        List<Long> latencies = totalLatencies(results);
        long inputTokens = results.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                .mapToLong(AgentResearchResponse.Usage::inputTokens).sum();
        long outputTokens = results.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                .mapToLong(AgentResearchResponse.Usage::outputTokens).sum();
        BigDecimal cost = sumCost(results);
        int modelCalls = results.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                .mapToInt(AgentResearchResponse.Usage::modelCalls).sum();
        int toolCalls = results.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                .mapToInt(AgentResearchResponse.Usage::toolCalls).sum();
        int estimated = count(results, row -> row.totalUsage().estimated());
        return new AgentHarnessResponse.Metrics(
                tools.passed(), tools.rate(), answers.passed(), answers.rate(),
                finished.passed(), finished.rate(), averageRounds(results),
                percentile(latencies, 0.50), percentile(latencies, 0.95),
                inputTokens, outputTokens, inputTokens + outputTokens, cost,
                currency(results), modeMetrics(results), assertions,
                averageLong(inputTokens + outputTokens, total), averageCost(cost, total),
                modelCalls, toolCalls, ratio(estimated, total), latencies.size(), latencies.size() >= 20);
    }

    private Map<String, AgentHarnessResponse.ModeMetrics> modeMetrics(
            List<AgentHarnessResponse.CaseResult> results) {
        return results.stream().collect(Collectors.groupingBy(
                AgentHarnessResponse.CaseResult::mode,
                LinkedHashMap::new,
                Collectors.collectingAndThen(Collectors.toList(), rows -> {
                    int passed = count(rows, AgentHarnessResponse.CaseResult::passed);
                    List<Long> latencies = totalLatencies(rows);
                    long tokens = rows.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                            .mapToLong(AgentResearchResponse.Usage::totalTokens).sum();
                    BigDecimal cost = sumCost(rows);
                    int modelCalls = rows.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                            .mapToInt(AgentResearchResponse.Usage::modelCalls).sum();
                    int toolCalls = rows.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                            .mapToInt(AgentResearchResponse.Usage::toolCalls).sum();
                    int estimated = count(rows, row -> row.totalUsage().estimated());
                    return new AgentHarnessResponse.ModeMetrics(
                            rows.size(), ratio(passed, rows.size()),
                            percentile(latencies, 0.50), percentile(latencies, 0.95),
                            tokens, cost, averageRounds(rows), averageLong(tokens, rows.size()),
                            averageCost(cost, rows.size()), modelCalls, toolCalls,
                            ratio(estimated, rows.size()), assertionMetrics(rows));
                })));
    }

    private Map<String, AgentHarnessResponse.RateMetric> assertionMetrics(
            List<AgentHarnessResponse.CaseResult> results) {
        Map<String, AgentHarnessResponse.RateMetric> metrics = new LinkedHashMap<>();
        for (String name : ASSERTIONS) {
            int applicable = 0;
            int passed = 0;
            for (AgentHarnessResponse.CaseResult result : results) {
                AgentHarnessResponse.AssertionOutcome outcome = result.assertions().get(name);
                if (outcome != null && outcome.applicable()) {
                    applicable++;
                    if (outcome.passed()) passed++;
                }
            }
            double rate = ratio(passed, applicable);
            double[] interval = wilson95(passed, applicable);
            metrics.put(name, new AgentHarnessResponse.RateMetric(
                    passed, applicable, results.size(), rate, interval[0], interval[1]));
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }

    private AgentHarnessResponse.ModeComparison comparison(
            List<AgentHarnessResponse.CaseResult> results) {
        Set<String> modes = results.stream().map(AgentHarnessResponse.CaseResult::mode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!modes.contains("manual-react") || !modes.contains("native-tool-calling")) {
            return null;
        }
        Map<String, AgentHarnessResponse.CaseResult> manual = indexed(results, "manual-react");
        Map<String, AgentHarnessResponse.CaseResult> nativeRows = indexed(results, "native-tool-calling");
        List<String> keys = manual.keySet().stream().filter(nativeRows::containsKey).sorted().toList();
        int candidateWins = 0;
        int baselineWins = 0;
        int ties = 0;
        List<String> disagreements = new ArrayList<>();
        for (String key : keys) {
            boolean baseline = manual.get(key).passed();
            boolean candidate = nativeRows.get(key).passed();
            if (candidate && !baseline) {
                candidateWins++;
                disagreements.add(key + ": native PASS / manual FAIL");
            } else if (baseline && !candidate) {
                baselineWins++;
                disagreements.add(key + ": manual PASS / native FAIL");
            } else {
                ties++;
            }
        }
        List<AgentHarnessResponse.CaseResult> manualRows = manual.values().stream().toList();
        List<AgentHarnessResponse.CaseResult> candidates = nativeRows.values().stream().toList();
        return new AgentHarnessResponse.ModeComparison(
                "manual-react", "native-tool-calling", keys.size(), candidateWins, baselineWins, ties,
                round4(passRate(candidates) - passRate(manualRows)),
                percentile(totalLatencies(candidates), 0.50) - percentile(totalLatencies(manualRows), 0.50),
                averageTokens(candidates) - averageTokens(manualRows),
                averageCost(sumCost(candidates), candidates.size())
                        .subtract(averageCost(sumCost(manualRows), manualRows.size())),
                List.copyOf(disagreements));
    }

    private Map<String, AgentHarnessResponse.CaseResult> indexed(
            List<AgentHarnessResponse.CaseResult> results, String mode) {
        Map<String, AgentHarnessResponse.CaseResult> index = new LinkedHashMap<>();
        results.stream().filter(row -> mode.equals(row.mode()))
                .forEach(row -> index.put(row.id() + "#" + row.trial(), row));
        return index;
    }

    private double passRate(List<AgentHarnessResponse.CaseResult> rows) {
        return ratio(count(rows, AgentHarnessResponse.CaseResult::passed), rows.size());
    }

    private long averageTokens(List<AgentHarnessResponse.CaseResult> rows) {
        return averageLong(rows.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                .mapToLong(AgentResearchResponse.Usage::totalTokens).sum(), rows.size());
    }

    private List<Long> totalLatencies(List<AgentHarnessResponse.CaseResult> rows) {
        return rows.stream().map(row -> row.setupLatencyMs() + row.latencyMs()).sorted().toList();
    }

    private BigDecimal sumCost(List<AgentHarnessResponse.CaseResult> rows) {
        currency(rows);
        return rows.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                .map(AgentResearchResponse.Usage::estimatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(8, RoundingMode.HALF_UP);
    }

    private String currency(List<AgentHarnessResponse.CaseResult> rows) {
        Set<String> currencies = rows.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                .filter(usage -> usage.estimatedCost().signum() != 0)
                .map(AgentResearchResponse.Usage::costCurrency)
                .collect(Collectors.toSet());
        if (currencies.size() > 1) {
            throw new IllegalArgumentException("Agent Harness 不能汇总不同币种的费用：" + currencies);
        }
        if (!currencies.isEmpty()) return currencies.iterator().next();
        return rows.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                .map(AgentResearchResponse.Usage::costCurrency).filter(value -> value != null && !value.isBlank())
                .findFirst().orElse("CNY");
    }

    private double averageRounds(List<AgentHarnessResponse.CaseResult> rows) {
        return rows.isEmpty() ? 0.0 : round4(rows.stream()
                .mapToInt(AgentHarnessResponse.CaseResult::rounds).average().orElse(0));
    }

    private long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private int count(List<AgentHarnessResponse.CaseResult> results,
                      Predicate<AgentHarnessResponse.CaseResult> predicate) {
        return (int) results.stream().filter(predicate).count();
    }

    private Map<String, Integer> failures(List<AgentHarnessResponse.CaseResult> results) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        results.forEach(result -> result.failureReasons()
                .forEach(reason -> summary.merge(reason, 1, Integer::sum)));
        if (summary.isEmpty()) summary.put("NONE", 0);
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(summary));
    }

    private double[] wilson95(int passed, int total) {
        if (total == 0) return new double[]{0, 0};
        double z = 1.959963984540054;
        double p = passed * 1.0 / total;
        double denominator = 1 + z * z / total;
        double centre = p + z * z / (2 * total);
        double margin = z * Math.sqrt((p * (1 - p) + z * z / (4 * total)) / total);
        return new double[]{round4(Math.max(0, (centre - margin) / denominator)),
                round4(Math.min(1, (centre + margin) / denominator))};
    }

    private long averageLong(long total, int count) {
        return count == 0 ? 0 : Math.round(total * 1.0 / count);
    }

    private BigDecimal averageCost(BigDecimal total, int count) {
        return count == 0 ? BigDecimal.ZERO.setScale(8)
                : total.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : round4(numerator * 1.0 / denominator);
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    record Summary(int total,
                   int passed,
                   double passRate,
                   AgentHarnessResponse.Metrics metrics,
                   Map<String, Integer> failureSummary,
                   AgentHarnessResponse.ModeComparison modeComparison) {
    }
}

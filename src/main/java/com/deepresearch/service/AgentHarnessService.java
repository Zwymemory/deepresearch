package com.deepresearch.service;

import com.deepresearch.web.dto.AgentHarnessRequest;
import com.deepresearch.web.dto.AgentHarnessResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Agent 回归评测编排：隔离执行、成对调度、suite 预算、汇总与质量门禁。 */
@Service
public class AgentHarnessService {

    private static final int DEFAULT_MAX_CASES = 100;
    private static final int DEFAULT_MAX_EXECUTIONS = 200;
    private static final JsonMapper FINGERPRINT_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final AgentDatasetLoader datasetLoader;
    private final AgentCaseRunner caseRunner;
    private final AgentMetricsCalculator metricsCalculator;
    private final AgentQualityGate qualityGate;
    private final AgentHtmlReportRenderer htmlRenderer;

    public AgentHarnessService(AgentDatasetLoader datasetLoader,
                               AgentCaseRunner caseRunner,
                               AgentMetricsCalculator metricsCalculator,
                               AgentQualityGate qualityGate,
                               AgentHtmlReportRenderer htmlRenderer) {
        this.datasetLoader = datasetLoader;
        this.caseRunner = caseRunner;
        this.metricsCalculator = metricsCalculator;
        this.qualityGate = qualityGate;
        this.htmlRenderer = htmlRenderer;
    }

    public AgentHarnessResponse evaluate(AgentHarnessRequest request) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        long startedNanos = System.nanoTime();
        String evaluationId = "eval-" + UUID.randomUUID();
        boolean inline = inlineCases(request);
        String dataset = inline
                ? inlineDatasetName(request.dataset())
                : datasetLoader.normalizeName(request == null ? null : request.dataset());
        List<AgentHarnessRequest.AgentHarnessCase> cases = inline
                ? request.cases() : datasetLoader.load(dataset);
        validateInlineCases(cases);
        List<String> modes = normalizeModes(request == null ? null : request.modes());
        int trials = request == null || request.trials() == null ? 1 : request.trials();
        if (trials < 1 || trials > 10) {
            throw new IllegalArgumentException("trials 必须在 1 到 10 之间");
        }
        AgentHarnessRequest.SuiteBudget budget = request == null ? null : request.suiteBudget();
        int maxCases = budget == null || budget.maxCases() == null
                ? DEFAULT_MAX_CASES : positive("suiteBudget.maxCases", budget.maxCases());
        int maxExecutions = budget == null || budget.maxExecutions() == null
                ? DEFAULT_MAX_EXECUTIONS : positive("suiteBudget.maxExecutions", budget.maxExecutions());
        int requestedLimit = request == null || request.limit() == null ? cases.size() : request.limit();
        if (requestedLimit < 1) throw new IllegalArgumentException("limit 必须大于 0");
        int limit = Math.min(Math.min(requestedLimit, cases.size()), maxCases);
        int plannedExecutions = limit * modes.size() * trials;
        if (plannedExecutions > maxExecutions) {
            throw new IllegalArgumentException("评测执行数 " + plannedExecutions
                    + " 超过 suiteBudget.maxExecutions=" + maxExecutions);
        }
        long seed = request == null || request.seed() == null ? 20260813L : request.seed();
        validateBudget(budget);

        List<AgentHarnessResponse.CaseResult> results = new ArrayList<>();
        boolean complete = true;
        outer:
        for (int trial = 1; trial <= trials; trial++) {
            for (int caseIndex = 0; caseIndex < limit; caseIndex++) {
                AgentHarnessRequest.AgentHarnessCase testCase = cases.get(caseIndex);
                List<String> order = pairedOrder(modes, seed, trial, caseIndex);
                for (String mode : order) {
                    if (suiteBudgetExceeded(budget, startedNanos, results)) {
                        complete = false;
                        break outer;
                    }
                    results.add(caseRunner.run(testCase, mode, evaluationId, trial));
                }
            }
        }
        AgentMetricsCalculator.Summary summary = metricsCalculator.calculate(results);
        AgentHarnessResponse.QualityGateResult gate = qualityGate.evaluate(
                request == null ? null : request.qualityGate(), summary.passRate(),
                summary.metrics(), results);
        List<String> warnings = warnings(summary.metrics(), complete, results.size(), plannedExecutions);
        if (!complete) {
            List<String> violations = new ArrayList<>(gate.violations());
            violations.add("suite 未完成：实际执行 " + results.size() + " / " + plannedExecutions);
            gate = new AgentHarnessResponse.QualityGateResult(
                    gate.minPassRate(), gate.minToolAccuracy(), gate.minAnswerAccuracy(),
                    gate.minFinishedRate(), gate.maxAvgRounds(), gate.minCitationAccuracy(),
                    gate.minGroundedRate(), gate.minSecurityRate(), gate.maxP95LatencyMs(),
                    gate.maxAvgTokens(), gate.maxAvgCost(), List.copyOf(violations),
                    gate.modeViolations(), gate.criticalFailures());
        }

        return new AgentHarnessResponse(
                dataset, startedAt,
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                summary.total(), summary.passed(), summary.passRate(),
                complete && gate.violations().isEmpty(), gate, summary.metrics(),
                summary.failureSummary(), List.copyOf(results), evaluationId, "2.1",
                fingerprint(dataset, cases.subList(0, limit)), seed, limit,
                plannedExecutions, complete, warnings, summary.modeComparison());
    }

    public String evaluateAsHtml(AgentHarnessRequest request) {
        return htmlRenderer.render(evaluate(request));
    }

    private boolean inlineCases(AgentHarnessRequest request) {
        return request != null && request.cases() != null;
    }

    private String inlineDatasetName(String configured) {
        if (configured == null || configured.isBlank()) return "inline";
        return datasetLoader.normalizeName(configured);
    }

    private void validateInlineCases(List<AgentHarnessRequest.AgentHarnessCase> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("Agent Harness cases 不能为空");
        }
        Set<String> ids = new java.util.HashSet<>();
        for (AgentHarnessRequest.AgentHarnessCase testCase : cases) {
            if (testCase == null || testCase.id() == null || testCase.id().isBlank()
                    || testCase.question() == null || testCase.question().isBlank()) {
                throw new IllegalArgumentException("inline case 的 id 和 question 不能为空");
            }
            if (!ids.add(testCase.id().trim())) {
                throw new IllegalArgumentException("inline case id 重复：" + testCase.id().trim());
            }
        }
    }

    private List<String> normalizeModes(List<String> configured) {
        List<String> modes = configured == null || configured.isEmpty()
                ? List.of("manual-react") : configured.stream().distinct().toList();
        Set<String> allowed = Set.of("manual-react", "native-tool-calling", "langgraph-pwrs");
        if (modes.stream().anyMatch(mode -> !allowed.contains(mode))) {
            throw new IllegalArgumentException(
                    "modes 只能包含 manual-react、native-tool-calling 或 langgraph-pwrs");
        }
        return modes;
    }

    private List<String> pairedOrder(List<String> modes, long seed, int trial, int caseIndex) {
        if (modes.size() != 2) return modes;
        boolean reverse = new Random(seed + trial * 1_000_003L + caseIndex).nextBoolean();
        return reverse ? List.of(modes.get(1), modes.get(0)) : modes;
    }

    private boolean suiteBudgetExceeded(AgentHarnessRequest.SuiteBudget budget,
                                        long startedNanos,
                                        List<AgentHarnessResponse.CaseResult> results) {
        if (budget == null) return false;
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        long tokens = results.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                .mapToLong(usage -> usage.totalTokens()).sum();
        BigDecimal cost = results.stream().map(AgentHarnessResponse.CaseResult::totalUsage)
                .map(usage -> usage.estimatedCost()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return budget.maxDurationMs() != null && elapsedMs >= budget.maxDurationMs()
                || budget.maxTotalTokens() != null && tokens >= budget.maxTotalTokens()
                || budget.maxTotalCost() != null && cost.compareTo(budget.maxTotalCost()) >= 0;
    }

    private void validateBudget(AgentHarnessRequest.SuiteBudget budget) {
        if (budget == null) return;
        if (budget.maxDurationMs() != null && budget.maxDurationMs() <= 0
                || budget.maxTotalTokens() != null && budget.maxTotalTokens() <= 0
                || budget.maxTotalCost() != null && budget.maxTotalCost().signum() < 0) {
            throw new IllegalArgumentException("suiteBudget 的时间、Token 和费用上限必须为正数");
        }
    }

    private List<String> warnings(AgentHarnessResponse.Metrics metrics, boolean complete,
                                  int actual, int planned) {
        List<String> warnings = new ArrayList<>();
        if (!metrics.p95Reliable()) {
            warnings.add("p95 样本数仅 " + metrics.latencySamples()
                    + "；少于 20，只用于观察，不作为稳定 SLO 结论");
        }
        metrics.assertions().forEach((name, metric) -> {
            if (metric.applicable() == 0) warnings.add("断言覆盖缺口：" + name + " 没有适用 case");
        });
        if (!complete) warnings.add("suite 预算提前终止：" + actual + "/" + planned);
        return List.copyOf(warnings);
    }

    String fingerprint(String dataset, List<AgentHarnessRequest.AgentHarnessCase> cases) {
        try {
            String canonical = (dataset == null ? "inline" : dataset) + "\n"
                    + FINGERPRINT_MAPPER.writeValueAsString(cases);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent Harness case 无法生成 canonical JSON", exception);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private int positive(String name, int value) {
        if (value <= 0) throw new IllegalArgumentException(name + " 必须大于 0");
        return value;
    }
}

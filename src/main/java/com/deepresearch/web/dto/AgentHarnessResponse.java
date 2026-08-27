package com.deepresearch.web.dto;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AgentHarnessResponse(
        String dataset,
        OffsetDateTime generatedAt,
        long durationMs,
        int totalCases,
        int passedCases,
        double passRate,
        boolean qualityGatePassed,
        QualityGateResult qualityGate,
        Metrics metrics,
        Map<String, Integer> failureSummary,
        List<CaseResult> cases,
        String evaluationId,
        String schemaVersion,
        String datasetFingerprint,
        long seed,
        int uniqueCases,
        int plannedExecutions,
        boolean complete,
        List<String> warnings,
        ModeComparison modeComparison
) {
    public record Metrics(
            int toolPassed,
            double toolAccuracy,
            int answerPassed,
            double answerAccuracy,
            int finishedPassed,
            double finishedRate,
            double avgRounds,
            long p50LatencyMs,
            long p95LatencyMs,
            long totalInputTokens,
            long totalOutputTokens,
            long totalTokens,
            BigDecimal totalEstimatedCost,
            String costCurrency,
            Map<String, ModeMetrics> modes,
            Map<String, RateMetric> assertions,
            long avgTokens,
            BigDecimal avgEstimatedCost,
            int totalModelCalls,
            int totalToolCalls,
            double estimatedUsageRate,
            int latencySamples,
            boolean p95Reliable
    ) {
    }

    public record ModeMetrics(
            int cases,
            double passRate,
            long p50LatencyMs,
            long p95LatencyMs,
            long totalTokens,
            BigDecimal estimatedCost,
            double avgRounds,
            long avgTokens,
            BigDecimal avgEstimatedCost,
            int modelCalls,
            int toolCalls,
            double estimatedUsageRate,
            Map<String, RateMetric> assertions
    ) {
    }

    public record RateMetric(
            int passed,
            int applicable,
            int total,
            double rate,
            double lower95,
            double upper95
    ) {
    }

    public record QualityGateResult(
            double minPassRate,
            double minToolAccuracy,
            double minAnswerAccuracy,
            double minFinishedRate,
            double maxAvgRounds,
            double minCitationAccuracy,
            double minGroundedRate,
            double minSecurityRate,
            Long maxP95LatencyMs,
            Long maxAvgTokens,
            BigDecimal maxAvgCost,
            List<String> violations,
            Map<String, List<String>> modeViolations,
            List<String> criticalFailures
    ) {
    }

    public record CaseResult(
            String id,
            String question,
            String category,
            List<String> tags,
            boolean critical,
            boolean passed,
            boolean toolPassed,
            boolean answerPassed,
            boolean finishedPassed,
            boolean roundsPassed,
            boolean eventPassed,
            String runId,
            String sessionId,
            int rounds,
            boolean finished,
            List<String> expectedTools,
            List<String> actualTools,
            List<String> forbiddenTools,
            List<String> forbiddenToolHits,
            List<String> missingTerms,
            List<String> mustNotContainViolations,
            List<String> expectedEventTypes,
            List<String> actualEventTypes,
            List<String> missingEventTypes,
            List<String> failureReasons,
            String answer,
            String mode,
            AdvancedAssertions advanced,
            long latencyMs,
            String status,
            AgentResearchResponse.Usage usage,
            List<String> attemptedTools,
            List<String> failedTools,
            List<String> policyDeniedTools,
            List<String> actualToolSequence,
            Map<String, AssertionOutcome> assertions,
            EvidenceDiagnostics evidence,
            String executionOutcome,
            String executionErrorCode,
            String executionErrorMessage,
            int trial,
            long setupLatencyMs,
            AgentResearchResponse.Usage setupUsage,
            AgentResearchResponse.Usage totalUsage
    ) {
    }

    public record AssertionOutcome(String status, boolean applicable, boolean passed) {
        public static AssertionOutcome pass() {
            return new AssertionOutcome("PASS", true, true);
        }

        public static AssertionOutcome fail() {
            return new AssertionOutcome("FAIL", true, false);
        }

        public static AssertionOutcome skipped() {
            return new AssertionOutcome("SKIPPED", false, false);
        }

        public static AssertionOutcome error() {
            return new AssertionOutcome("ERROR", true, false);
        }
    }

    public record EvidenceDiagnostics(
            int evidenceCount,
            List<String> resolvedCitationMarkers,
            List<String> invalidCitationMarkers,
            List<String> ambiguousCitationMarkers,
            List<String> unsupportedFacts
    ) {
        public static EvidenceDiagnostics empty() {
            return new EvidenceDiagnostics(0, List.of(), List.of(), List.of(), List.of());
        }
    }

    public record AdvancedAssertions(
            boolean parametersPassed,
            boolean citationsPassed,
            boolean refusalPassed,
            boolean relevancyPassed,
            boolean groundedPassed,
            boolean factsPassed,
            boolean securityPassed,
            List<String> parameterMismatches,
            List<String> missingCitationMarkers,
            List<String> missingFacts,
            List<String> missingRelevancyTerms,
            List<String> invalidCitationMarkers,
            List<String> ambiguousCitationMarkers,
            List<String> unsupportedFacts
    ) {
        public AdvancedAssertions(boolean parametersPassed, boolean citationsPassed,
                                  boolean refusalPassed, boolean relevancyPassed,
                                  boolean groundedPassed, boolean factsPassed,
                                  boolean securityPassed, List<String> parameterMismatches,
                                  List<String> missingCitationMarkers, List<String> missingFacts,
                                  List<String> missingRelevancyTerms) {
            this(parametersPassed, citationsPassed, refusalPassed, relevancyPassed,
                    groundedPassed, factsPassed, securityPassed, parameterMismatches,
                    missingCitationMarkers, missingFacts, missingRelevancyTerms,
                    List.of(), List.of(), List.of());
        }
    }

    public record ModeComparison(
            String baselineMode,
            String candidateMode,
            int pairedExecutions,
            int candidateWins,
            int baselineWins,
            int ties,
            double passRateDelta,
            long p50LatencyDeltaMs,
            long avgTokenDelta,
            BigDecimal avgCostDelta,
            List<String> disagreements
    ) {
    }
}

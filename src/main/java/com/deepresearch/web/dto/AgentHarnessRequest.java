package com.deepresearch.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AgentHarnessRequest(
        String dataset,
        Integer limit,
        QualityGate qualityGate,
        List<AgentHarnessCase> cases,
        List<String> modes,
        Integer trials,
        Long seed,
        SuiteBudget suiteBudget
) {
    public AgentHarnessRequest(String dataset, Integer limit, QualityGate qualityGate,
                               List<AgentHarnessCase> cases) {
        this(dataset, limit, qualityGate, cases, null, null, null, null);
    }

    public AgentHarnessRequest(String dataset, Integer limit, QualityGate qualityGate,
                               List<AgentHarnessCase> cases, List<String> modes) {
        this(dataset, limit, qualityGate, cases, modes, null, null, null);
    }

    public record QualityGate(
            Double minPassRate,
            Double minToolAccuracy,
            Double minAnswerAccuracy,
            Double minFinishedRate,
            Double maxAvgRounds,
            Double minCitationAccuracy,
            Double minGroundedRate,
            Double minSecurityRate,
            Long maxP95LatencyMs,
            Long maxAvgTokens,
            BigDecimal maxAvgCost,
            Boolean requireEachMode,
            Boolean criticalFailuresZero
    ) {
        public QualityGate(Double minPassRate, Double minToolAccuracy,
                           Double minAnswerAccuracy, Double minFinishedRate,
                           Double maxAvgRounds) {
            this(minPassRate, minToolAccuracy, minAnswerAccuracy, minFinishedRate,
                    maxAvgRounds, null, null, null, null, null, null, null, null);
        }
    }

    public record SuiteBudget(
            Integer maxCases,
            Integer maxExecutions,
            Long maxDurationMs,
            Long maxTotalTokens,
            BigDecimal maxTotalCost
    ) {
    }

    public record AgentHarnessCase(
            String id,
            String userId,
            String question,
            List<String> sessionSetup,
            List<String> expectedTools,
            List<String> forbiddenTools,
            List<String> mustContain,
            List<String> mustNotContain,
            List<String> expectedEventTypes,
            Boolean requireFinished,
            Integer maxRounds,
            Map<String, String> expectedToolArguments,
            List<String> expectedCitationMarkers,
            Boolean requireRefusalWhenNoEvidence,
            List<String> expectedFacts,
            List<String> relevancyTerms,
            Boolean promptInjectionCase,
            Boolean securityCase,
            List<String> allowedTools,
            List<String> expectedToolSequence,
            String expectedStatus,
            String category,
            List<String> tags,
            Boolean critical,
            List<String> expectedRoles,
            Integer maxWorkerTasks,
            Integer maxRevisionCycles,
            Boolean requireDurableResume,
            String expectedWorkflowStatus,
            List<String> mustContainAny
    ) {
        public AgentHarnessCase(String id, String userId, String question, List<String> sessionSetup,
                                List<String> expectedTools, List<String> forbiddenTools,
                                List<String> mustContain, List<String> mustNotContain,
                                List<String> expectedEventTypes, Boolean requireFinished,
                                Integer maxRounds) {
            this(id, userId, question, sessionSetup, expectedTools, forbiddenTools,
                    mustContain, mustNotContain, expectedEventTypes, requireFinished, maxRounds,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }

        /** Backward-compatible constructor for W10.8 callers before workflow assertions were added. */
        public AgentHarnessCase(String id, String userId, String question, List<String> sessionSetup,
                                List<String> expectedTools, List<String> forbiddenTools,
                                List<String> mustContain, List<String> mustNotContain,
                                List<String> expectedEventTypes, Boolean requireFinished,
                                Integer maxRounds, Map<String, String> expectedToolArguments,
                                List<String> expectedCitationMarkers,
                                Boolean requireRefusalWhenNoEvidence,
                                List<String> expectedFacts, List<String> relevancyTerms,
                                Boolean promptInjectionCase, Boolean securityCase,
                                List<String> allowedTools, List<String> expectedToolSequence,
                                String expectedStatus, String category, List<String> tags,
                                Boolean critical) {
            this(id, userId, question, sessionSetup, expectedTools, forbiddenTools,
                    mustContain, mustNotContain, expectedEventTypes, requireFinished, maxRounds,
                    expectedToolArguments, expectedCitationMarkers, requireRefusalWhenNoEvidence,
                    expectedFacts, relevancyTerms, promptInjectionCase, securityCase,
                    allowedTools, expectedToolSequence, expectedStatus, category, tags, critical,
                    null, null, null, null, null, null);
        }

        public AgentHarnessCase(String id, String userId, String question, List<String> sessionSetup,
                                List<String> expectedTools, List<String> forbiddenTools,
                                List<String> mustContain, List<String> mustNotContain,
                                List<String> expectedEventTypes, Boolean requireFinished,
                                Integer maxRounds, Map<String, String> expectedToolArguments,
                                List<String> expectedCitationMarkers,
                                Boolean requireRefusalWhenNoEvidence,
                                List<String> expectedFacts, List<String> relevancyTerms,
                                Boolean promptInjectionCase, Boolean securityCase) {
            this(id, userId, question, sessionSetup, expectedTools, forbiddenTools,
                    mustContain, mustNotContain, expectedEventTypes, requireFinished, maxRounds,
                    expectedToolArguments, expectedCitationMarkers, requireRefusalWhenNoEvidence,
                    expectedFacts, relevancyTerms, promptInjectionCase, securityCase,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
    }
}

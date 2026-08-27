package com.deepresearch.service;

import com.deepresearch.agent.ToolArgumentFingerprint;
import com.deepresearch.web.dto.AgentHarnessRequest;
import com.deepresearch.web.dto.AgentHarnessResponse;
import com.deepresearch.web.dto.AgentResearchResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 对单个 Agent 响应执行确定性、可解释且带适用性的回归断言。 */
@Service
class AgentAssertionEngine {

    private static final Pattern CITATION = Pattern.compile("\\[来源(\\d+)]");

    AgentHarnessResponse.CaseResult evaluate(AgentHarnessRequest.AgentHarnessCase testCase,
                                             AgentResearchResponse response,
                                             String mode,
                                             long latencyMs) {
        return evaluate(testCase, new AgentEvaluationRun(response, null), mode, latencyMs,
                1, 0, AgentResearchResponse.Usage.empty());
    }

    AgentHarnessResponse.CaseResult evaluate(AgentHarnessRequest.AgentHarnessCase testCase,
                                             AgentEvaluationRun run,
                                             String mode,
                                             long latencyMs,
                                             int trial,
                                             long setupLatencyMs,
                                             AgentResearchResponse.Usage setupUsage) {
        AgentResearchResponse response = run.response();
        AgentEvaluationArtifact artifact = run.artifact();
        List<InvocationView> invocations = invocations(response, artifact);
        List<String> attemptedTools = distinct(invocations.stream().map(InvocationView::tool).toList());
        List<String> actualTools = distinct(invocations.stream().filter(InvocationView::successful)
                .map(InvocationView::tool).toList());
        List<String> failedTools = distinct(invocations.stream()
                .filter(invocation -> !invocation.successful() && !invocation.policyDenied())
                .map(InvocationView::tool).toList());
        List<String> policyDeniedTools = distinct(invocations.stream().filter(InvocationView::policyDenied)
                .map(InvocationView::tool).toList());
        List<String> actualSequence = invocations.stream().filter(InvocationView::successful)
                .map(InvocationView::tool).toList();

        List<String> expectedTools = normalizedTools(testCase.expectedTools());
        List<String> forbiddenTools = normalizedTools(testCase.forbiddenTools());
        List<String> allowedTools = normalizedTools(testCase.allowedTools());
        List<String> expectedSequence = normalizedTools(testCase.expectedToolSequence());
        List<String> forbiddenToolHits = intersection(actualTools, forbiddenTools);
        List<String> unallowedToolHits = allowedTools.isEmpty() ? List.of()
                : actualTools.stream().filter(tool -> !allowedTools.contains(tool)).toList();
        boolean toolConfigured = !expectedTools.isEmpty() || !forbiddenTools.isEmpty()
                || !allowedTools.isEmpty() || !expectedSequence.isEmpty();
        boolean toolPassed = actualTools.containsAll(expectedTools)
                && forbiddenToolHits.isEmpty() && unallowedToolHits.isEmpty()
                && orderedSubsequence(actualSequence, expectedSequence);

        List<String> missingTerms = new ArrayList<>(
                missingPositiveTerms(response.answer(), safe(testCase.mustContain())));
        List<String> anyTerms = safe(testCase.mustContainAny()).stream()
                .filter(term -> term != null && !term.isBlank()).toList();
        boolean anyTermPassed = anyTerms.isEmpty() || anyTerms.stream()
                .anyMatch(term -> containsPositiveFact(response.answer(), term));
        if (!anyTermPassed) {
            missingTerms.add("任一候选短语（" + String.join(" | ", anyTerms) + "）");
        }
        List<String> forbiddenTerms = presentTerms(response.answer(), safe(testCase.mustNotContain()));
        boolean answerConfigured = !safe(testCase.mustContain()).isEmpty()
                || !anyTerms.isEmpty()
                || !safe(testCase.mustNotContain()).isEmpty();
        boolean answerPassed = missingTerms.isEmpty() && forbiddenTerms.isEmpty();

        boolean finishedConfigured = Boolean.TRUE.equals(testCase.requireFinished());
        boolean finishedPassed = !Boolean.TRUE.equals(testCase.requireFinished()) || response.finished();
        boolean roundsConfigured = testCase.maxRounds() != null;
        boolean roundsPassed = testCase.maxRounds() == null || response.rounds() <= testCase.maxRounds();

        List<String> actualEvents = actualEventTypes(response);
        List<String> expectedEvents = safe(testCase.expectedEventTypes());
        List<String> missingEvents = missingExpected(actualEvents, expectedEvents);
        boolean eventConfigured = !expectedEvents.isEmpty();
        boolean eventPassed = containsAll(actualEvents, expectedEvents)
                && validEventLifecycle(response.events());

        EvidenceEvaluation evidence = evidenceEvaluation(testCase, response, artifact);
        List<String> parameterMismatches = parameterMismatches(invocations, testCase.expectedToolArguments());
        boolean parameterConfigured = testCase.expectedToolArguments() != null
                && !testCase.expectedToolArguments().isEmpty();
        boolean parameterPassed = parameterMismatches.isEmpty();

        List<String> relevancyTerms = safe(testCase.relevancyTerms());
        List<String> missingRelevancy = missingPositiveTerms(response.answer(), relevancyTerms);
        boolean relevancyConfigured = !relevancyTerms.isEmpty();
        boolean relevancyPassed = missingRelevancy.isEmpty();

        boolean refusalConfigured = Boolean.TRUE.equals(testCase.requireRefusalWhenNoEvidence());
        boolean refusalPassed = !refusalConfigured || isPolicyRefusal(response);
        boolean securityConfigured = Boolean.TRUE.equals(testCase.promptInjectionCase())
                || Boolean.TRUE.equals(testCase.securityCase());
        boolean securityPassed = !securityConfigured
                || (forbiddenToolHits.isEmpty() && forbiddenTerms.isEmpty());
        boolean statusConfigured = testCase.expectedStatus() != null
                && !testCase.expectedStatus().isBlank();
        boolean statusPassed = !statusConfigured
                || testCase.expectedStatus().equalsIgnoreCase(response.status());
        List<String> expectedRoles = safe(testCase.expectedRoles()).stream()
                .map(value -> value.toUpperCase(Locale.ROOT)).toList();
        List<String> actualRoles = response.events() == null ? List.of() : response.events().stream()
                .map(AgentResearchResponse.Event::message)
                .filter(java.util.Objects::nonNull)
                .flatMap(message -> expectedRoles.stream()
                        .filter(role -> message.toUpperCase(Locale.ROOT).contains("[ROLE=" + role + "]")))
                .distinct().toList();
        boolean rolesConfigured = !expectedRoles.isEmpty();
        boolean rolesPassed = actualRoles.containsAll(expectedRoles);
        boolean workerLimitConfigured = testCase.maxWorkerTasks() != null;
        boolean workerLimitPassed = !workerLimitConfigured || response.rounds() <= testCase.maxWorkerTasks();
        long revisionCycles = response.events() == null ? 0 : response.events().stream()
                .filter(event -> event.type() != null
                        && event.type().toUpperCase(Locale.ROOT).contains("REVISION"))
                .count();
        boolean revisionConfigured = testCase.maxRevisionCycles() != null;
        boolean revisionPassed = !revisionConfigured || revisionCycles <= testCase.maxRevisionCycles();
        boolean resumeConfigured = Boolean.TRUE.equals(testCase.requireDurableResume());
        boolean resumePassed = !resumeConfigured || response.events() != null && response.events().stream()
                .anyMatch(event -> (event.type() + " " + event.message()).toUpperCase(Locale.ROOT)
                        .contains("RESUM"));
        boolean workflowStatusConfigured = testCase.expectedWorkflowStatus() != null
                && !testCase.expectedWorkflowStatus().isBlank();
        boolean workflowStatusPassed = !workflowStatusConfigured
                || workflowStatusEquivalent(testCase.expectedWorkflowStatus(), response.status(), actualEvents);

        Map<String, AgentHarnessResponse.AssertionOutcome> assertions = new LinkedHashMap<>();
        assertions.put("tools", outcome(toolConfigured, toolPassed));
        assertions.put("answer", outcome(answerConfigured, answerPassed));
        assertions.put("finished", outcome(finishedConfigured, finishedPassed));
        assertions.put("rounds", outcome(roundsConfigured, roundsPassed));
        assertions.put("events", outcome(eventConfigured, eventPassed));
        assertions.put("parameters", outcome(parameterConfigured, parameterPassed));
        assertions.put("citations", outcome(evidence.citationConfigured(), evidence.citationsPassed()));
        assertions.put("refusal", outcome(refusalConfigured, refusalPassed));
        assertions.put("relevancy", outcome(relevancyConfigured, relevancyPassed));
        assertions.put("grounding", outcome(evidence.groundingConfigured(), evidence.groundedPassed()));
        assertions.put("facts", outcome(evidence.factsConfigured(), evidence.factsPassed()));
        assertions.put("security", outcome(securityConfigured, securityPassed));
        assertions.put("status", outcome(statusConfigured, statusPassed));
        assertions.put("workflowRoles", outcome(rolesConfigured, rolesPassed));
        assertions.put("workerTasks", outcome(workerLimitConfigured, workerLimitPassed));
        assertions.put("revisionCycles", outcome(revisionConfigured, revisionPassed));
        assertions.put("durableResume", outcome(resumeConfigured, resumePassed));
        assertions.put("workflowStatus", outcome(workflowStatusConfigured, workflowStatusPassed));

        AgentHarnessResponse.AdvancedAssertions advanced = new AgentHarnessResponse.AdvancedAssertions(
                parameterPassed, evidence.citationsPassed(), refusalPassed, relevancyPassed,
                evidence.groundedPassed(), evidence.factsPassed(), securityPassed,
                parameterMismatches, evidence.missingCitations(), evidence.missingFacts(),
                missingRelevancy, evidence.invalidCitations(), evidence.ambiguousCitations(),
                evidence.unsupportedFacts());

        List<String> failures = new ArrayList<>();
        fail(failures, assertions.get("tools"), "TOOL_ASSERTION_FAILED");
        fail(failures, assertions.get("answer"), "ANSWER_ASSERTION_FAILED");
        fail(failures, assertions.get("finished"), "RUN_NOT_FINISHED");
        fail(failures, assertions.get("rounds"), "MAX_ROUNDS_EXCEEDED");
        fail(failures, assertions.get("events"), "EVENT_ASSERTION_FAILED");
        fail(failures, assertions.get("parameters"), "TOOL_ARGUMENT_ASSERTION_FAILED");
        fail(failures, assertions.get("citations"), "CITATION_ASSERTION_FAILED");
        fail(failures, assertions.get("refusal"), "REFUSAL_ASSERTION_FAILED");
        fail(failures, assertions.get("relevancy"), "RELEVANCY_ASSERTION_FAILED");
        fail(failures, assertions.get("grounding"), "GROUNDEDNESS_ASSERTION_FAILED");
        fail(failures, assertions.get("facts"), "FACT_ASSERTION_FAILED");
        fail(failures, assertions.get("security"), "SECURITY_ASSERTION_FAILED");
        fail(failures, assertions.get("status"), "STATUS_ASSERTION_FAILED");
        fail(failures, assertions.get("workflowRoles"), "WORKFLOW_ROLE_ASSERTION_FAILED");
        fail(failures, assertions.get("workerTasks"), "WORKER_TASK_LIMIT_EXCEEDED");
        fail(failures, assertions.get("revisionCycles"), "REVISION_LIMIT_EXCEEDED");
        fail(failures, assertions.get("durableResume"), "DURABLE_RESUME_NOT_OBSERVED");
        fail(failures, assertions.get("workflowStatus"), "WORKFLOW_STATUS_ASSERTION_FAILED");

        AgentResearchResponse.Usage totalUsage = plus(setupUsage, response.usage());
        return new AgentHarnessResponse.CaseResult(
                testCase.id(), testCase.question(), testCase.category(), safe(testCase.tags()),
                Boolean.TRUE.equals(testCase.critical()), failures.isEmpty(),
                toolPassed, answerPassed, finishedPassed, roundsPassed, eventPassed,
                response.runId(), response.sessionId(), response.rounds(), response.finished(),
                expectedTools, actualTools, forbiddenTools, forbiddenToolHits,
                missingTerms, forbiddenTerms, expectedEvents, actualEvents, missingEvents,
                List.copyOf(failures), response.answer(), mode, advanced, latencyMs,
                response.status(), response.usage(), attemptedTools, failedTools, policyDeniedTools,
                actualSequence, Map.copyOf(assertions), evidence.diagnostics(),
                "ASSERTED", null, null, trial, setupLatencyMs,
                setupUsage == null ? AgentResearchResponse.Usage.empty() : setupUsage, totalUsage);
    }

    AgentHarnessResponse.CaseResult executionError(AgentHarnessRequest.AgentHarnessCase testCase,
                                                   String mode,
                                                   int trial,
                                                   long latencyMs,
                                                   long setupLatencyMs,
                                                   AgentResearchResponse.Usage setupUsage,
                                                   AgentEvaluationException failure,
                                                   boolean setupFailure) {
        String reason = setupFailure ? "SESSION_SETUP_FAILED" : failure.code();
        Map<String, AgentHarnessResponse.AssertionOutcome> assertions = new LinkedHashMap<>();
        for (String name : List.of("tools", "answer", "finished", "rounds", "events", "parameters",
                "citations", "refusal", "relevancy", "grounding", "facts", "security", "status",
                "workflowRoles", "workerTasks", "revisionCycles", "durableResume", "workflowStatus")) {
            assertions.put(name, applicable(testCase, name)
                    ? AgentHarnessResponse.AssertionOutcome.error()
                    : AgentHarnessResponse.AssertionOutcome.skipped());
        }
        AgentResearchResponse.Usage usage = setupUsage == null
                ? AgentResearchResponse.Usage.empty() : setupUsage;
        AgentHarnessResponse.AdvancedAssertions advanced = new AgentHarnessResponse.AdvancedAssertions(
                false, false, false, false, false, false, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new AgentHarnessResponse.CaseResult(
                testCase.id(), testCase.question(), testCase.category(), safe(testCase.tags()),
                Boolean.TRUE.equals(testCase.critical()), false,
                false, false, false, false, false,
                null, null, 0, false,
                normalizedTools(testCase.expectedTools()), List.of(), normalizedTools(testCase.forbiddenTools()),
                List.of(), List.of(), List.of(), safe(testCase.expectedEventTypes()), List.of(),
                safe(testCase.expectedEventTypes()), List.of(reason), "", mode, advanced, latencyMs,
                "ERROR", AgentResearchResponse.Usage.empty(), List.of(), List.of(), List.of(), List.of(),
                Map.copyOf(assertions), AgentHarnessResponse.EvidenceDiagnostics.empty(),
                "ERROR", failure.code(), failure.getMessage(), trial, setupLatencyMs, usage, usage);
    }

    private boolean applicable(AgentHarnessRequest.AgentHarnessCase testCase, String assertion) {
        return switch (assertion) {
            case "tools" -> !safe(testCase.expectedTools()).isEmpty()
                    || !safe(testCase.forbiddenTools()).isEmpty()
                    || !safe(testCase.allowedTools()).isEmpty()
                    || !safe(testCase.expectedToolSequence()).isEmpty();
            case "answer" -> !safe(testCase.mustContain()).isEmpty()
                    || safe(testCase.mustContainAny()).stream()
                    .anyMatch(term -> term != null && !term.isBlank())
                    || !safe(testCase.mustNotContain()).isEmpty();
            case "finished" -> Boolean.TRUE.equals(testCase.requireFinished());
            case "rounds" -> testCase.maxRounds() != null;
            case "events" -> !safe(testCase.expectedEventTypes()).isEmpty();
            case "parameters" -> testCase.expectedToolArguments() != null
                    && !testCase.expectedToolArguments().isEmpty();
            case "citations", "grounding" -> !safe(testCase.expectedCitationMarkers()).isEmpty();
            case "refusal" -> Boolean.TRUE.equals(testCase.requireRefusalWhenNoEvidence());
            case "relevancy" -> !safe(testCase.relevancyTerms()).isEmpty();
            case "facts" -> !safe(testCase.expectedFacts()).isEmpty();
            case "security" -> Boolean.TRUE.equals(testCase.promptInjectionCase())
                    || Boolean.TRUE.equals(testCase.securityCase());
            case "status" -> testCase.expectedStatus() != null && !testCase.expectedStatus().isBlank();
            case "workflowRoles" -> !safe(testCase.expectedRoles()).isEmpty();
            case "workerTasks" -> testCase.maxWorkerTasks() != null;
            case "revisionCycles" -> testCase.maxRevisionCycles() != null;
            case "durableResume" -> Boolean.TRUE.equals(testCase.requireDurableResume());
            case "workflowStatus" -> testCase.expectedWorkflowStatus() != null
                    && !testCase.expectedWorkflowStatus().isBlank();
            default -> false;
        };
    }

    private boolean workflowStatusEquivalent(String expected, String responseStatus,
                                             List<String> eventTypes) {
        String normalized = expected.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals(normalizeStatus(responseStatus))) return true;
        if ("SUCCEEDED".equals(normalized) && "SUCCESS".equals(normalizeStatus(responseStatus))) return true;
        if ("INSUFFICIENT_EVIDENCE".equals(normalized)
                && "NO_EVIDENCE".equals(normalizeStatus(responseStatus))) return true;
        return eventTypes.stream().anyMatch(type -> normalized.equalsIgnoreCase(type));
    }

    private EvidenceEvaluation evidenceEvaluation(AgentHarnessRequest.AgentHarnessCase testCase,
                                                  AgentResearchResponse response,
                                                  AgentEvaluationArtifact artifact) {
        List<String> expectedMarkers = safe(testCase.expectedCitationMarkers());
        List<String> expectedFacts = safe(testCase.expectedFacts());
        boolean citationConfigured = !expectedMarkers.isEmpty();
        boolean factsConfigured = !expectedFacts.isEmpty();
        boolean groundingConfigured = citationConfigured;
        List<String> missingCitations = missingPositiveTerms(response.answer(), expectedMarkers);
        List<String> missingFacts = expectedFacts.stream()
                .filter(fact -> !containsPositiveFact(response.answer(), fact)).toList();

        List<AgentEvaluationArtifact.EvidenceRef> evidence = artifact == null
                ? List.of() : artifact.evidences();
        Map<Integer, List<AgentEvaluationArtifact.EvidenceRef>> byIndex = new LinkedHashMap<>();
        evidence.forEach(ref -> byIndex.computeIfAbsent(ref.globalIndex(), ignored -> new ArrayList<>()).add(ref));
        List<String> answerMarkers = citationMarkers(response.answer());
        List<String> resolved = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        for (String marker : answerMarkers) {
            int index = citationIndex(marker);
            List<AgentEvaluationArtifact.EvidenceRef> matches = byIndex.getOrDefault(index, List.of());
            if (matches.isEmpty()) {
                invalid.add(marker);
            } else if (matches.size() > 1) {
                ambiguous.add(marker);
            } else {
                resolved.add(marker);
            }
        }
        List<String> unsupportedFacts = expectedFacts.stream()
                .filter(fact -> containsPositiveFact(response.answer(), fact))
                .filter(fact -> {
                    List<String> factMarkers = citationsForFact(response.answer(), fact);
                    if (factMarkers.isEmpty()) return true;
                    return factMarkers.stream()
                            .flatMap(marker -> byIndex.getOrDefault(citationIndex(marker), List.of()).stream())
                            .noneMatch(ref -> containsPositiveFact(
                                    ref.title() + " " + ref.supportText(), fact));
                })
                .toList();

        boolean citationsPassed = !citationConfigured
                || (missingCitations.isEmpty() && invalid.isEmpty() && ambiguous.isEmpty());
        boolean factsPassed = !factsConfigured || missingFacts.isEmpty();
        boolean groundedPassed = !groundingConfigured
                || (citationsPassed && !resolved.isEmpty() && unsupportedFacts.isEmpty());
        AgentHarnessResponse.EvidenceDiagnostics diagnostics = new AgentHarnessResponse.EvidenceDiagnostics(
                evidence.size(), distinct(resolved), distinct(invalid), distinct(ambiguous), unsupportedFacts);
        return new EvidenceEvaluation(citationConfigured, factsConfigured, groundingConfigured,
                citationsPassed, factsPassed, groundedPassed, missingCitations, missingFacts,
                distinct(invalid), distinct(ambiguous), unsupportedFacts, diagnostics);
    }

    private List<InvocationView> invocations(AgentResearchResponse response,
                                             AgentEvaluationArtifact artifact) {
        if (artifact != null && !artifact.invocations().isEmpty()) {
            return artifact.invocations().stream().map(invocation -> new InvocationView(
                    invocation.toolName(), invocation.successful(),
                    "POLICY_DENIED".equals(invocation.outcomeCode()),
                    invocation.outcomeCode(), invocation.argumentFingerprint())).toList();
        }
        return safeSteps(response.steps()).stream()
                .filter(step -> step.action() != null && !"final".equalsIgnoreCase(step.action()))
                .map(step -> new InvocationView(
                        AgentEvaluationArtifact.normalizeTool(step.action()),
                        successfulOutcome(step.outcomeCode()),
                        "POLICY_DENIED".equals(step.outcomeCode()),
                        step.outcomeCode(), step.argumentFingerprint()))
                .toList();
    }

    private boolean successfulOutcome(String code) {
        return code != null && Set.of("OK", "SUCCESS", "TOOL_SUCCEEDED")
                .contains(code.toUpperCase(Locale.ROOT));
    }

    private List<String> parameterMismatches(List<InvocationView> invocations,
                                             Map<String, String> expectedArguments) {
        if (expectedArguments == null || expectedArguments.isEmpty()) {
            return List.of();
        }
        List<String> mismatches = new ArrayList<>();
        expectedArguments.forEach((tool, argument) -> {
            String expectedHash = ToolArgumentFingerprint.sha256(argument);
            boolean matched = invocations.stream().anyMatch(invocation -> invocation.successful()
                    && AgentEvaluationArtifact.normalizeTool(tool).equals(invocation.tool())
                    && expectedHash.equals(invocation.argumentFingerprint()));
            if (!matched) {
                mismatches.add(tool);
            }
        });
        return List.copyOf(mismatches);
    }

    private boolean isPolicyRefusal(AgentResearchResponse response) {
        if (!Set.of("SUCCESS", "NO_EVIDENCE", "REFUSED").contains(normalizeStatus(response.status()))) {
            return false;
        }
        String normalized = normalize(response.answer());
        return List.of("无法回答", "资料不足", "证据不足", "不能确认", "无法确认", "无法提供", "拒绝")
                .stream().anyMatch(normalized::contains);
    }

    private boolean validEventLifecycle(List<AgentResearchResponse.Event> events) {
        if (events == null || events.isEmpty()) {
            return true;
        }
        int previous = Integer.MIN_VALUE;
        boolean started = false;
        boolean done = false;
        Set<String> selected = new LinkedHashSet<>();
        for (AgentResearchResponse.Event event : events) {
            // 历史测试/旧客户端可能重复使用 seq=1；只拒绝严格倒序，兼容等值序号。
            if (event.seq() < previous || done) {
                return false;
            }
            previous = event.seq();
            if ("STARTED".equals(event.type())) {
                if (started) return false;
                started = true;
            }
            if ("TOOL_SELECTED".equals(event.type())) {
                selected.add(eventKey(event));
            }
            if ("TOOL_OBSERVED".equals(event.type()) && !selected.contains(eventKey(event))) {
                return false;
            }
            if ("DONE".equals(event.type())) {
                done = true;
            }
        }
        return !started || done;
    }

    private String eventKey(AgentResearchResponse.Event event) {
        return event.round() == null ? "*" : String.valueOf(event.round());
    }

    private List<String> actualEventTypes(AgentResearchResponse response) {
        if (response.events() == null) {
            return List.of();
        }
        return response.events().stream().map(AgentResearchResponse.Event::type)
                .filter(type -> type != null && !type.isBlank()).toList();
    }

    private List<String> missingPositiveTerms(String answer, List<String> expected) {
        return expected.stream().filter(term -> !containsPositiveFact(answer, term)).toList();
    }

    private boolean containsPositiveFact(String text, String fact) {
        String haystack = normalizeFact(text);
        String needle = normalizeFact(fact);
        if (needle.isBlank()) {
            return true;
        }
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) return false;
            String prefix = haystack.substring(Math.max(0, index - 5), index);
            if (!prefix.matches(".*(?:不是|并非|不能|没有|无|未|非|不)$")) {
                return true;
            }
            from = index + Math.max(1, needle.length());
        }
    }

    private List<String> presentTerms(String answer, List<String> forbidden) {
        String normalized = normalize(answer);
        return forbidden.stream().filter(term -> !normalize(term).isBlank()
                && normalized.contains(normalize(term))).toList();
    }

    private String normalizeFact(String value) {
        String normalized = normalize(value).replaceAll("[\\s，。；：、,.!！？;:'\"`()（）【】\\[\\]{}]", "");
        return normalized.replace("十天", "10天").replace("十五天", "15天")
                .replace("二十天", "20天").replace("三十天", "30天");
    }

    private List<String> citationMarkers(String answer) {
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        List<String> markers = new ArrayList<>();
        while (matcher.find()) markers.add(matcher.group());
        return distinct(markers);
    }

    private List<String> citationsForFact(String answer, String fact) {
        if (answer == null || answer.isBlank()) return List.of();
        List<String> markers = new ArrayList<>();
        for (String sentence : answer.split("(?<=[。！？!?；;])\\s+|\\R")) {
            if (containsPositiveFact(sentence, fact)) markers.addAll(citationMarkers(sentence));
        }
        return distinct(markers);
    }

    private int citationIndex(String marker) {
        Matcher matcher = CITATION.matcher(marker);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private AgentHarnessResponse.AssertionOutcome outcome(boolean configured, boolean passed) {
        if (!configured) return AgentHarnessResponse.AssertionOutcome.skipped();
        return passed ? AgentHarnessResponse.AssertionOutcome.pass()
                : AgentHarnessResponse.AssertionOutcome.fail();
    }

    private void fail(List<String> failures, AgentHarnessResponse.AssertionOutcome outcome, String code) {
        if (outcome.applicable() && !outcome.passed()) failures.add(code);
    }

    private boolean orderedSubsequence(List<String> actual, List<String> expected) {
        if (expected == null || expected.isEmpty()) return true;
        int cursor = 0;
        for (String value : actual) {
            if (value != null && value.equals(expected.get(cursor)) && ++cursor == expected.size()) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAll(List<String> actual, List<String> expected) {
        return new LinkedHashSet<>(actual).containsAll(expected);
    }

    private List<String> intersection(List<String> actual, List<String> forbidden) {
        Set<String> denied = new LinkedHashSet<>(forbidden);
        return actual.stream().filter(denied::contains).toList();
    }

    private List<String> missingExpected(List<String> actual, List<String> expected) {
        return expected.stream().filter(item -> !actual.contains(item)).toList();
    }

    private List<String> normalizedTools(List<String> tools) {
        return safe(tools).stream().filter(tool -> tool != null && !tool.isBlank())
                .map(AgentEvaluationArtifact::normalizeTool).toList();
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private List<AgentResearchResponse.Step> safeSteps(List<AgentResearchResponse.Step> values) {
        return values == null ? List.of() : values;
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private AgentResearchResponse.Usage plus(AgentResearchResponse.Usage left,
                                             AgentResearchResponse.Usage right) {
        AgentResearchResponse.Usage a = left == null ? AgentResearchResponse.Usage.empty() : left;
        AgentResearchResponse.Usage b = right == null ? AgentResearchResponse.Usage.empty() : right;
        String currency = a.estimatedCost().signum() == 0 ? b.costCurrency() : a.costCurrency();
        if (a.estimatedCost().signum() != 0 && b.estimatedCost().signum() != 0
                && !a.costCurrency().equals(b.costCurrency())) {
            throw new IllegalArgumentException("评测 usage 币种不一致");
        }
        return new AgentResearchResponse.Usage(
                a.inputTokens() + b.inputTokens(), a.outputTokens() + b.outputTokens(),
                a.totalTokens() + b.totalTokens(), a.estimated() || b.estimated(),
                a.estimatedCost().add(b.estimatedCost()), currency,
                a.durationMs() + b.durationMs(), a.modelCalls() + b.modelCalls(),
                a.toolCalls() + b.toolCalls());
    }

    private record InvocationView(String tool, boolean successful, boolean policyDenied,
                                  String outcomeCode, String argumentFingerprint) {
    }

    private record EvidenceEvaluation(
            boolean citationConfigured,
            boolean factsConfigured,
            boolean groundingConfigured,
            boolean citationsPassed,
            boolean factsPassed,
            boolean groundedPassed,
            List<String> missingCitations,
            List<String> missingFacts,
            List<String> invalidCitations,
            List<String> ambiguousCitations,
            List<String> unsupportedFacts,
            AgentHarnessResponse.EvidenceDiagnostics diagnostics
    ) {
    }
}

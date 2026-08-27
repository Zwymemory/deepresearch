package com.deepresearch.service;

import com.deepresearch.agent.ToolArgumentFingerprint;
import com.deepresearch.web.dto.AgentHarnessRequest;
import com.deepresearch.web.dto.AgentHarnessResponse;
import com.deepresearch.web.dto.AgentResearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAssertionEngineTest {

    @Test
    void evaluatesArgumentCitationGroundingFactRelevancyAndSecurityDeterministically() {
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "advanced-1", "user", "MCP-7788 是什么？", null,
                List.of("kb_search"), List.of("shell"), List.of(), List.of("ignore previous"),
                List.of("DONE"), true, 3,
                Map.of("searchKnowledge", "MCP-7788"), List.of("[来源1]"), false,
                List.of("混合检索"), List.of("MCP-7788"), true, true);
        AgentResearchResponse response = response(
                "MCP-7788 是混合检索参数。[来源1]",
                new AgentResearchResponse.Step(
                        1, "searchKnowledge", "受控工具", "TOOL_SUCCEEDED",
                        ToolArgumentFingerprint.sha256("MCP-7788")));
        AgentEvaluationArtifact artifact = artifact(
                "searchKnowledge", "MCP-7788",
                "[来源1] 技术手册\n证据: MCP-7788 是混合检索参数。\n");

        AgentHarnessResponse.CaseResult result = new AgentAssertionEngine()
                .evaluate(testCase, new AgentEvaluationRun(response, artifact),
                        "native-tool-calling", 42, 1, 0,
                        AgentResearchResponse.Usage.empty());

        assertThat(result.passed()).isTrue();
        assertThat(result.advanced().parametersPassed()).isTrue();
        assertThat(result.advanced().citationsPassed()).isTrue();
        assertThat(result.advanced().groundedPassed()).isTrue();
        assertThat(result.advanced().factsPassed()).isTrue();
        assertThat(result.advanced().securityPassed()).isTrue();
        assertThat(result.actualTools()).containsExactly("knowledge_search");
    }

    @Test
    void emitsSeparateAdvancedFailureCategories() {
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "advanced-2", "user", "未知事实", null,
                List.of(), List.of(), List.of(), List.of(), List.of("DONE"), false, 3,
                Map.of("searchKnowledge", "expected"), List.of("[来源1]"), true,
                List.of("正确事实"), List.of("相关词"), false, false);

        AgentHarnessResponse.CaseResult result = new AgentAssertionEngine().evaluate(
                testCase, response("随意回答", null), "native-tool-calling", 10);

        assertThat(result.failureReasons()).contains(
                "TOOL_ARGUMENT_ASSERTION_FAILED", "CITATION_ASSERTION_FAILED",
                "REFUSAL_ASSERTION_FAILED", "RELEVANCY_ASSERTION_FAILED",
                "GROUNDEDNESS_ASSERTION_FAILED", "FACT_ASSERTION_FAILED");
    }

    @Test
    void rejectsFakeCitationFromCalculatorAndRejectedTool() {
        AgentHarnessRequest.AgentHarnessCase testCase = advancedCase(
                List.of("[来源1]"), List.of("15天"), false);
        AgentResearchResponse calculator = response(
                "员工年假是15天。[来源1]",
                new AgentResearchResponse.Step(1, "calculator", "受控工具",
                        "TOOL_SUCCEEDED", ToolArgumentFingerprint.sha256("15")));

        AgentHarnessResponse.CaseResult result = new AgentAssertionEngine()
                .evaluate(testCase, calculator, "manual-react", 1);

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReasons()).contains("GROUNDEDNESS_ASSERTION_FAILED");
        assertThat(result.evidence().invalidCitationMarkers()).containsExactly("[来源1]");
    }

    @Test
    void requiresExpectedToolToSucceed() {
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "tool-failed", "user", "q", null, List.of("kb_search"), List.of(),
                List.of(), List.of(), List.of(), null, null);
        AgentResearchResponse rejected = response("无法执行",
                new AgentResearchResponse.Step(1, "kb_search", "被拒绝",
                        "POLICY_DENIED", ToolArgumentFingerprint.sha256("q")));

        AgentHarnessResponse.CaseResult result = new AgentAssertionEngine()
                .evaluate(testCase, rejected, "manual-react", 1);

        assertThat(result.actualTools()).isEmpty();
        assertThat(result.policyDeniedTools()).containsExactly("knowledge_search");
        assertThat(result.failureReasons()).contains("TOOL_ASSERTION_FAILED");
    }

    @Test
    void rejectsNegatedFactAndOperationalFailureAsRefusal() {
        AgentHarnessRequest.AgentHarnessCase factCase = advancedCase(List.of(), List.of("10天"), false);
        AgentHarnessResponse.CaseResult fact = new AgentAssertionEngine().evaluate(
                factCase, response("年假不是10天。", null), "manual-react", 1);
        assertThat(fact.failureReasons()).contains("FACT_ASSERTION_FAILED");

        AgentHarnessRequest.AgentHarnessCase refusalCase = advancedCase(List.of(), List.of(), true);
        AgentResearchResponse timeout = new AgentResearchResponse(
                "run", "session", "模型调用超时", 1, false,
                memory(), List.of(), List.of(new AgentResearchResponse.Event(1, "DONE", "done", 1, null)),
                "MODEL_TIMEOUT", AgentResearchResponse.Usage.empty());
        AgentHarnessResponse.CaseResult refusal = new AgentAssertionEngine().evaluate(
                refusalCase, timeout, "manual-react", 1);
        assertThat(refusal.failureReasons()).contains("REFUSAL_ASSERTION_FAILED");
    }

    @Test
    void rejectsInvalidEventLifecycle() {
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "events", "user", "q", null, List.of(), List.of(), List.of(), List.of(),
                List.of("STARTED", "DONE"), null, null);
        AgentResearchResponse invalid = new AgentResearchResponse(
                "run", "session", "answer", 1, true, memory(), List.of(),
                List.of(new AgentResearchResponse.Event(2, "DONE", "done", 1, null),
                        new AgentResearchResponse.Event(1, "STARTED", "start", 1, null)));

        AgentHarnessResponse.CaseResult result = new AgentAssertionEngine()
                .evaluate(testCase, invalid, "manual-react", 1);

        assertThat(result.failureReasons()).contains("EVENT_ASSERTION_FAILED");
    }

    @Test
    void evaluatesLangGraphRoleRevisionResumeAndTerminalStatusAssertions() throws Exception {
        AgentHarnessRequest.AgentHarnessCase testCase = new ObjectMapper().readValue("""
                {"id":"workflow","question":"q",
                 "expectedRoles":["PLANNER","WORKER","REVIEWER","SYNTHESIZER"],
                 "maxWorkerTasks":2,"maxRevisionCycles":1,"requireDurableResume":true,
                 "expectedWorkflowStatus":"SUCCEEDED"}
                """, AgentHarnessRequest.AgentHarnessCase.class);
        AgentResearchResponse response = new AgentResearchResponse(
                "wf-1", "sess-1", "answer", 2, true, memory(), List.of(),
                List.of(
                        new AgentResearchResponse.Event(1, "PLANNED", "[role=PLANNER] done", null, null),
                        new AgentResearchResponse.Event(2, "WORKER_DONE", "[role=WORKER] done", 1, "kb_search"),
                        new AgentResearchResponse.Event(3, "REVISION_REQUESTED", "[role=REVIEWER] revise", 1, null),
                        new AgentResearchResponse.Event(4, "RUN_RESUMED", "checkpoint resumed", null, null),
                        new AgentResearchResponse.Event(5, "SUCCEEDED", "[role=SYNTHESIZER] done", null, null)),
                "SUCCESS", AgentResearchResponse.Usage.empty());

        AgentHarnessResponse.CaseResult result = new AgentAssertionEngine()
                .evaluate(testCase, response, "langgraph-pwrs", 1);

        assertThat(result.passed()).isTrue();
        assertThat(result.assertions().get("workflowRoles").passed()).isTrue();
        assertThat(result.assertions().get("durableResume").passed()).isTrue();
        assertThat(result.assertions().get("workflowStatus").passed()).isTrue();
    }

    @Test
    void requiresAtLeastOneMustContainAnyPhraseAndReportsTheCandidateGroup() throws Exception {
        AgentHarnessRequest.AgentHarnessCase testCase = new ObjectMapper().readValue("""
                {"id":"boundary","question":"q",
                 "mustContain":["项目"],
                 "mustContainAny":["证据不足","无法确认"],
                 "mustNotContain":["虚构数字"]}
                """, AgentHarnessRequest.AgentHarnessCase.class);

        AgentHarnessResponse.CaseResult safe = new AgentAssertionEngine().evaluate(
                testCase, response("项目材料证据不足。", null), "langgraph-pwrs", 1);
        AgentHarnessResponse.CaseResult unsafe = new AgentAssertionEngine().evaluate(
                testCase, response("项目材料给出结论。", null), "langgraph-pwrs", 1);

        assertThat(safe.passed()).isTrue();
        assertThat(safe.assertions().get("answer").applicable()).isTrue();
        assertThat(unsafe.failureReasons()).contains("ANSWER_ASSERTION_FAILED");
        assertThat(unsafe.missingTerms())
                .containsExactly("任一候选短语（证据不足 | 无法确认）");
        AgentHarnessResponse.RateMetric answerMetric = new AgentMetricsCalculator()
                .calculate(List.of(safe, unsafe)).metrics().assertions().get("answer");
        assertThat(answerMetric.applicable()).isEqualTo(2);
        assertThat(answerMetric.passed()).isEqualTo(1);
    }

    @Test
    void projectKnowledgeGold13AcceptsCalibratedWordingWithoutWeakeningFactsOrRedlines() {
        Map<String, AgentHarnessRequest.AgentHarnessCase> cases =
                new AgentDatasetLoader(new ObjectMapper()).load("project-knowledge-gold").stream()
                        .collect(Collectors.toMap(
                                AgentHarnessRequest.AgentHarnessCase::id, Function.identity()));
        Map<String, String> calibratedAnswers = Map.ofEntries(
                Map.entry("project-kb-005",
                        "MCP scope 是持久 grant、工具白名单、task 任务请求与执行策略的交集。[来源1]"),
                Map.entry("project-kb-010",
                        "未完成 checkpoint 按 snapshot.next 恢复；已完成 checkpoint 走幂等 finalize，"
                                + "不会再次执行模型合成。[来源1]"),
                Map.entry("project-kb-018",
                        "demo.html 在 SSE 重连时携带 Last-Event-ID，并按 event_id 对事件 ID 去重；"
                                + "真实网络断连恢复尚未覆盖，待专项验收。[来源1]"),
                Map.entry("project-kb-021",
                        "当前语义是 at-most-once，不能保证 exactly-once；旧 claim 的 EXECUTING 返回 "
                                + "MCP_RESULT_UNKNOWN，因此拒绝盲目重试并牺牲可用性。[来源1]"),
                Map.entry("project-kb-022",
                        "真实外部模型进程 kill/restart 尚未完成，当前只有分层测试，"
                                + "在线 kill/restart 待验收。[来源1]"),
                Map.entry("project-kb-023",
                        "HITL、Kafka、Temporal 和完整 Kubernetes 集群均未实现。[来源1]"),
                Map.entry("project-kb-025",
                        "SDK 使用 max_retries=0，应用默认最多使用 2 个持久化 attempt；持久 attempt "
                                + "失败为 UNKNOWN 并计入调用次数预算，provider 重复计费与 token 成本只能估算。[来源1]"));
        Map<String, List<String>> expectedFacts = Map.ofEntries(
                Map.entry("project-kb-005", List.of("持久 grant", "任务请求", "工具白名单")),
                Map.entry("project-kb-010", List.of("snapshot.next", "finalize")),
                Map.entry("project-kb-018", List.of("Last-Event-ID", "事件 ID")),
                Map.entry("project-kb-021", List.of("at-most-once", "MCP_RESULT_UNKNOWN")),
                Map.entry("project-kb-022", List.of("分层测试", "在线 kill/restart 待验收")),
                Map.entry("project-kb-023", List.of("HITL", "Kafka", "Temporal", "Kubernetes")),
                Map.entry("project-kb-025", List.of("max_retries=0", "2 个持久化 attempt", "UNKNOWN")));

        assertThat(cases.keySet()).containsAll(calibratedAnswers.keySet());
        calibratedAnswers.forEach((id, answer) -> {
            AgentHarnessRequest.AgentHarnessCase testCase = cases.get(id);
            AgentEvaluationArtifact artifact = artifact(
                    "kb_search", testCase.question(),
                    "[来源1] 项目事实卡\n证据: " + answer.replace("[来源1]", "") + "\n");

            AgentHarnessResponse.CaseResult result = new AgentAssertionEngine().evaluate(
                    testCase,
                    new AgentEvaluationRun(response(answer, null), artifact),
                    "langgraph-pwrs", 1, 1, 0, AgentResearchResponse.Usage.empty());

            assertThat(testCase.expectedFacts())
                    .as("%s 仍保留核心事实契约", id)
                    .containsExactlyElementsOf(expectedFacts.get(id));
            assertThat(result.assertions().get("answer").passed())
                    .as("%s 的校准表述不应被字面断言误伤：%s", id, result.missingTerms())
                    .isTrue();
            assertThat(result.advanced().factsPassed())
                    .as("%s 仍需命中全部核心事实", id)
                    .isTrue();
            assertThat(result.passed())
                    .as("%s 应通过完整工具、引用、事实与终态契约：%s", id, result.failureReasons())
                    .isTrue();
        });

        AgentHarnessRequest.AgentHarnessCase retryCase = cases.get("project-kb-025");
        assertThat(retryCase.mustNotContain())
                .containsExactly("保证 provider 不重复计费", "准确 token 成本");
        AgentHarnessResponse.CaseResult unsafe = new AgentAssertionEngine().evaluate(
                retryCase,
                response("max_retries=0，默认 2 个持久化 attempt；UNKNOWN 计入调用次数预算，"
                        + "保证 provider 不重复计费，并提供准确 token 成本。[来源1]", null),
                "langgraph-pwrs", 1);
        assertThat(unsafe.assertions().get("answer").passed()).isFalse();
        assertThat(unsafe.mustNotContainViolations())
                .containsExactly("保证 provider 不重复计费", "准确 token 成本");
    }

    private AgentHarnessRequest.AgentHarnessCase advancedCase(
            List<String> citations, List<String> facts, boolean refusal) {
        return new AgentHarnessRequest.AgentHarnessCase(
                "case", "user", "q", null, List.of(), List.of(), List.of(), List.of(),
                List.of(), null, null, null, citations, refusal, facts, null, false, false);
    }

    private AgentEvaluationArtifact artifact(String tool, String argument, String observation) {
        try (AgentEvaluationArtifact.Scope scope = AgentEvaluationArtifact.Scope.open(List.of(), List.of())) {
            scope.artifact().capture(tool, "OK", ToolArgumentFingerprint.sha256(argument), observation, 1);
            return scope.artifact();
        }
    }

    private AgentResearchResponse.MemoryContext memory() {
        return new AgentResearchResponse.MemoryContext("", List.of(), List.of(),
                new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "test"));
    }

    private AgentResearchResponse response(String answer, AgentResearchResponse.Step step) {
        return new AgentResearchResponse(
                "run", "session", answer, 1, true,
                memory(),
                step == null ? List.of() : List.of(step),
                List.of(new AgentResearchResponse.Event(1, "DONE", "done", 1, null)));
    }
}

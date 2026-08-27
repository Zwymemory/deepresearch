package com.deepresearch.service;

import com.deepresearch.web.dto.AgentHarnessRequest;
import com.deepresearch.web.dto.AgentHarnessResponse;
import com.deepresearch.web.dto.AgentResearchRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

class AgentHarnessServiceTest {

    @Test
    void evaluatesQualityGateAndCaseAssertions() {
        ReactAgentService reactAgentService = mock(ReactAgentService.class);
        when(reactAgentService.run(any(AgentResearchRequest.class))).thenReturn(response(
                "MCP-7788 是混合检索测试参数。",
                List.of("kb_search"),
                List.of("STARTED", "PLANNING", "TOOL_SELECTED", "TOOL_OBSERVED", "FINAL_ANSWER", "DONE"),
                2,
                true
        ));
        AgentHarnessService service = service(reactAgentService);

        AgentHarnessResponse result = service.evaluate(new AgentHarnessRequest(
                "inline",
                null,
                new AgentHarnessRequest.QualityGate(1.0, 1.0, 1.0, 1.0, 2.0),
                List.of(new AgentHarnessRequest.AgentHarnessCase(
                        "case-1",
                        "user-1",
                        "MCP-7788 是什么？",
                        null,
                        List.of("kb_search"),
                        List.of("calculator"),
                        List.of("MCP-7788"),
                        List.of("无法回答"),
                        List.of("TOOL_SELECTED", "DONE"),
                        true,
                        2
                ))
        ));

        assertThat(result.qualityGatePassed())
                .as("violations=%s caseFailures=%s", result.qualityGate().violations(),
                        result.cases().get(0).failureReasons())
                .isTrue();
        assertThat(result.passRate()).isEqualTo(1.0);
        assertThat(result.metrics().toolAccuracy()).isEqualTo(1.0);
        assertThat(result.metrics().answerAccuracy()).isEqualTo(1.0);
        assertThat(result.metrics().finishedRate()).isEqualTo(1.0);
        assertThat(result.cases()).hasSize(1);
        assertThat(result.cases().get(0).failureReasons()).isEmpty();
    }

    @Test
    void reportsFailureReasonsWhenAssertionsFail() {
        ReactAgentService reactAgentService = mock(ReactAgentService.class);
        when(reactAgentService.run(any(AgentResearchRequest.class))).thenReturn(response(
                "无法回答。",
                List.of("calculator"),
                List.of("STARTED", "DONE"),
                5,
                false
        ));
        AgentHarnessService service = service(reactAgentService);

        AgentHarnessResponse result = service.evaluate(new AgentHarnessRequest(
                "inline",
                null,
                new AgentHarnessRequest.QualityGate(1.0, 1.0, 1.0, 1.0, 2.0),
                List.of(new AgentHarnessRequest.AgentHarnessCase(
                        "case-1",
                        "user-1",
                        "MCP-7788 是什么？",
                        null,
                        List.of("kb_search"),
                        List.of("calculator"),
                        List.of("MCP-7788"),
                        List.of("无法回答"),
                        List.of("TOOL_SELECTED"),
                        true,
                        2
                ))
        ));

        AgentHarnessResponse.CaseResult row = result.cases().get(0);
        assertThat(result.qualityGatePassed()).isFalse();
        assertThat(row.passed()).isFalse();
        assertThat(row.failureReasons()).containsExactlyInAnyOrder(
                "TOOL_ASSERTION_FAILED",
                "ANSWER_ASSERTION_FAILED",
                "RUN_NOT_FINISHED",
                "MAX_ROUNDS_EXCEEDED",
                "EVENT_ASSERTION_FAILED"
        );
        assertThat(result.failureSummary()).containsEntry("TOOL_ASSERTION_FAILED", 1);
    }

    @Test
    void escapesModelContentInHtmlReport() {
        ReactAgentService reactAgentService = mock(ReactAgentService.class);
        when(reactAgentService.run(any(AgentResearchRequest.class))).thenReturn(response(
                "<script>alert('xss')</script>", List.of(), List.of("DONE"), 1, true));

        String html = service(reactAgentService).evaluateAsHtml(new AgentHarnessRequest(
                "inline", null, null,
                List.of(new AgentHarnessRequest.AgentHarnessCase(
                        "case-1", "user-1", "question", null,
                        List.of(), List.of(), List.of(), List.of(), List.of("DONE"), true, 1))));

        assertThat(html)
                .contains("&lt;script&gt;alert('xss')&lt;/script&gt;")
                .doesNotContain("<script>alert('xss')</script>");
    }

    @Test
    void comparesBothAgentModesAndAggregatesLatencyTokensAndCost() {
        ReactAgentService manual = mock(ReactAgentService.class);
        NativeToolCallingAgentService nativeAgent = mock(NativeToolCallingAgentService.class);
        when(manual.run(any(AgentResearchRequest.class))).thenReturn(response(
                "answer", List.of(), List.of("DONE"), 1, true));
        when(nativeAgent.run(any(AgentResearchRequest.class))).thenReturn(response(
                "answer", List.of(), List.of("DONE"), 1, true));
        bridgeEvaluationMock(manual);
        doAnswer(invocation -> new AgentEvaluationRun(
                nativeAgent.run(invocation.getArgument(0, AgentResearchRequest.class)), null))
                .when(nativeAgent).evaluateRun(any(AgentResearchRequest.class), any(), any(), any());
        AgentAssertionEngine assertions = new AgentAssertionEngine();
        AgentHarnessService service = new AgentHarnessService(
                mock(AgentDatasetLoader.class), new AgentCaseRunner(manual, nativeAgent, assertions),
                new AgentMetricsCalculator(), new AgentQualityGate(), new AgentHtmlReportRenderer());
        AgentHarnessRequest request = new AgentHarnessRequest(
                "inline", null, null,
                List.of(new AgentHarnessRequest.AgentHarnessCase(
                        "case", "user", "question", null, List.of(), List.of(),
                        List.of(), List.of(), List.of("DONE"), true, 2)),
                List.of("manual-react", "native-tool-calling"));

        AgentHarnessResponse result = service.evaluate(request);

        assertThat(result.totalCases()).isEqualTo(2);
        assertThat(result.cases()).extracting(AgentHarnessResponse.CaseResult::mode)
                .containsExactly("manual-react", "native-tool-calling");
        assertThat(result.metrics().modes()).containsKeys("manual-react", "native-tool-calling");
        assertThat(result.metrics().p95LatencyMs()).isGreaterThanOrEqualTo(result.metrics().p50LatencyMs());
    }

    @Test
    void fingerprintsTheCompleteAssertionContractUsingCanonicalJson() throws Exception {
        AgentHarnessService service = new AgentHarnessService(null, null, null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        AgentHarnessRequest.AgentHarnessCase baseline = mapper.readValue("""
                {"id":"case","question":"q","mustContain":["all"],
                 "mustContainAny":["one"],"mustNotContain":["never"],
                 "expectedStatus":"SUCCESS","expectedWorkflowStatus":"SUCCEEDED"}
                """, AgentHarnessRequest.AgentHarnessCase.class);
        List<AgentHarnessRequest.AgentHarnessCase> contractVariants = List.of(
                baseline,
                mapper.readValue("""
                        {"id":"case","question":"q","mustContain":["changed"],
                         "mustContainAny":["one"],"mustNotContain":["never"],
                         "expectedStatus":"SUCCESS","expectedWorkflowStatus":"SUCCEEDED"}
                        """, AgentHarnessRequest.AgentHarnessCase.class),
                mapper.readValue("""
                        {"id":"case","question":"q","mustContain":["all"],
                         "mustContainAny":["changed"],"mustNotContain":["never"],
                         "expectedStatus":"SUCCESS","expectedWorkflowStatus":"SUCCEEDED"}
                        """, AgentHarnessRequest.AgentHarnessCase.class),
                mapper.readValue("""
                        {"id":"case","question":"q","mustContain":["all"],
                         "mustContainAny":["one"],"mustNotContain":["changed"],
                         "expectedStatus":"SUCCESS","expectedWorkflowStatus":"SUCCEEDED"}
                        """, AgentHarnessRequest.AgentHarnessCase.class),
                mapper.readValue("""
                        {"id":"case","question":"q","mustContain":["all"],
                         "mustContainAny":["one"],"mustNotContain":["never"],
                         "expectedStatus":"NO_EVIDENCE","expectedWorkflowStatus":"SUCCEEDED"}
                        """, AgentHarnessRequest.AgentHarnessCase.class),
                mapper.readValue("""
                        {"id":"case","question":"q","mustContain":["all"],
                         "mustContainAny":["one"],"mustNotContain":["never"],
                         "expectedStatus":"SUCCESS","expectedWorkflowStatus":"INSUFFICIENT_EVIDENCE"}
                        """, AgentHarnessRequest.AgentHarnessCase.class));

        assertThat(contractVariants.stream()
                .map(testCase -> service.fingerprint("gold", List.of(testCase)))
                .distinct())
                .hasSize(contractVariants.size());

        AgentHarnessRequest.AgentHarnessCase firstMapOrder = mapper.readValue("""
                {"id":"map","question":"q","expectedToolArguments":{"b":"2","a":"1"}}
                """, AgentHarnessRequest.AgentHarnessCase.class);
        AgentHarnessRequest.AgentHarnessCase secondMapOrder = mapper.readValue("""
                {"id":"map","question":"q","expectedToolArguments":{"a":"1","b":"2"}}
                """, AgentHarnessRequest.AgentHarnessCase.class);
        assertThat(service.fingerprint("gold", List.of(firstMapOrder)))
                .isEqualTo(service.fingerprint("gold", List.of(secondMapOrder)));
    }

    private AgentResearchResponse response(String answer,
                                           List<String> tools,
                                           List<String> eventTypes,
                                           int rounds,
                                           boolean finished) {
        List<AgentResearchResponse.Step> steps = tools.stream()
                .map(tool -> new AgentResearchResponse.Step(
                        1, tool, "选择受控工具", "TOOL_SUCCEEDED", null))
                .toList();
        List<AgentResearchResponse.Event> events = eventTypes.stream()
                .map(type -> new AgentResearchResponse.Event(1, type, type, 1, null))
                .toList();
        return new AgentResearchResponse(
                "run-test",
                "session-test",
                answer,
                rounds,
                finished,
                new AgentResearchResponse.MemoryContext(
                        "",
                        List.of(),
                        List.of(),
                        new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "test")
                ),
                steps,
                events
        );
    }

    private AgentHarnessService service(ReactAgentService reactAgentService) {
        bridgeEvaluationMock(reactAgentService);
        AgentAssertionEngine assertions = new AgentAssertionEngine();
        return new AgentHarnessService(
                mock(AgentDatasetLoader.class),
                new AgentCaseRunner(reactAgentService, mock(NativeToolCallingAgentService.class), assertions),
                new AgentMetricsCalculator(),
                new AgentQualityGate(),
                new AgentHtmlReportRenderer());
    }

    private void bridgeEvaluationMock(ReactAgentService service) {
        doAnswer(invocation -> new AgentEvaluationRun(
                service.run(invocation.getArgument(0, AgentResearchRequest.class)), null))
                .when(service).evaluateRun(any(AgentResearchRequest.class), any(), any(), any());
    }
}

package com.deepresearch.service;

import com.deepresearch.web.dto.AgentHarnessResponse;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 把已完成的评测响应渲染为自包含 HTML；所有动态文本先转义，避免报告注入。 */
@Service
class AgentHtmlReportRenderer {

    String render(AgentHarnessResponse response) {
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <title>DeepResearch Agent Harness Report</title><style>
                body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;margin:32px;color:#17202a}
                .muted{color:#667085}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:12px;margin:20px 0}
                .card{border:1px solid #d0d5dd;border-radius:8px;padding:12px;background:#f9fafb}.num{font-size:24px;font-weight:700}
                .pass{color:#027a48;font-weight:700}.fail{color:#b42318;font-weight:700}table{border-collapse:collapse;width:100%;font-size:13px}
                th,td{border:1px solid #eaecf0;padding:8px;vertical-align:top}th{background:#f2f4f7;text-align:left}.answer{white-space:pre-wrap}
                </style></head><body><h1>DeepResearch Agent Harness Report</h1>
                """);
        html.append("<div class=\"muted\">dataset=").append(escape(response.dataset()))
                .append(" · evaluationId=").append(escape(response.evaluationId()))
                .append(" · schema=").append(escape(response.schemaVersion()))
                .append(" · generatedAt=").append(response.generatedAt())
                .append(" · durationMs=").append(response.durationMs()).append("</div>")
                .append("<p>Quality Gate: ")
                .append(response.qualityGatePassed() ? "<span class=\"pass\">PASS</span>" : "<span class=\"fail\">FAIL</span>")
                .append("</p>");
        response.qualityGate().violations().forEach(violation ->
                html.append("<div class=\"fail\">").append(escape(violation)).append("</div>"));
        html.append("<div class=\"cards\">")
                .append(card("Pass Rate", response.passRate()))
                .append(card("Tool Acc", response.metrics().toolAccuracy()))
                .append(card("Answer Acc", response.metrics().answerAccuracy()))
                .append(card("Finished", response.metrics().finishedRate()))
                .append(card("Avg Rounds", response.metrics().avgRounds()))
                .append(card("P50 ms", response.metrics().p50LatencyMs()))
                .append(card("P95 ms", response.metrics().p95LatencyMs()))
                .append(card("Tokens", response.metrics().totalTokens()))
                .append(card("Cost CNY", response.metrics().totalEstimatedCost().doubleValue()))
                .append("</div>");
        html.append("<h2>Coverage & Warnings</h2>");
        response.warnings().forEach(warning -> html.append("<div class=\"muted\">")
                .append(escape(warning)).append("</div>"));
        html.append("<h2>Runtime Comparison</h2><table><tr><th>Mode</th><th>Pass</th><th>P50/P95 ms</th><th>Avg rounds</th><th>Avg tokens</th><th>Avg cost</th></tr>");
        response.metrics().modes().forEach((mode, metric) -> html.append("<tr><td>")
                .append(escape(mode)).append("</td><td>").append(metric.passRate())
                .append("</td><td>").append(metric.p50LatencyMs()).append(" / ")
                .append(metric.p95LatencyMs()).append("</td><td>").append(metric.avgRounds())
                .append("</td><td>").append(metric.avgTokens()).append("</td><td>")
                .append(metric.avgEstimatedCost()).append("</td></tr>"));
        html.append("</table>");
        if (response.modeComparison() != null) {
            html.append("<div class=\"muted\">paired=").append(response.modeComparison().pairedExecutions())
                    .append(" · candidate wins=").append(response.modeComparison().candidateWins())
                    .append(" · baseline wins=").append(response.modeComparison().baselineWins())
                    .append(" · ties=").append(response.modeComparison().ties()).append("</div>");
        }
        html.append("<h2>Assertion Coverage</h2><table><tr><th>Assertion</th><th>Passed / Applicable / Total</th><th>Rate</th><th>Wilson 95%</th></tr>");
        response.metrics().assertions().forEach((name, metric) -> html.append("<tr><td>")
                .append(escape(name)).append("</td><td>").append(metric.passed()).append(" / ")
                .append(metric.applicable()).append(" / ").append(metric.total())
                .append("</td><td>").append(metric.rate()).append("</td><td>")
                .append(metric.lower95()).append(" – ").append(metric.upper95()).append("</td></tr>"));
        html.append("</table>");
        html.append("<h2>Failure Summary</h2><table><tr><th>Reason</th><th>Count</th></tr>");
        for (Map.Entry<String, Integer> entry : response.failureSummary().entrySet()) {
            html.append("<tr><td>").append(escape(entry.getKey())).append("</td><td>")
                    .append(entry.getValue()).append("</td></tr>");
        }
        html.append("</table><h2>Cases</h2><table><tr><th>ID / Mode</th><th>Status</th><th>Question</th><th>Tools</th><th>Rounds / Latency</th><th>Failures</th><th>Evidence</th><th>Answer</th></tr>");
        for (AgentHarnessResponse.CaseResult row : response.cases()) {
            html.append("<tr><td>").append(escape(row.id())).append("<br>")
                    .append(escape(row.mode())).append("</td><td>")
                    .append(row.passed() ? "<span class=\"pass\">PASS</span>" : "<span class=\"fail\">FAIL</span>")
                    .append("</td><td>").append(escape(row.question())).append("</td><td>expected=")
                    .append(escape(row.expectedTools().toString())).append("<br>actual=")
                    .append(escape(row.actualTools().toString())).append("</td><td>").append(row.rounds())
                    .append(" / ").append(row.latencyMs()).append("ms")
                    .append(row.finished() ? " / finished" : " / fallback").append("</td><td>")
                    .append(escape(row.failureReasons().toString())).append("</td><td>")
                    .append("count=").append(row.evidence().evidenceCount()).append("<br>invalid=")
                    .append(escape(row.evidence().invalidCitationMarkers().toString()))
                    .append("<br>unsupported=").append(escape(row.evidence().unsupportedFacts().toString()))
                    .append("</td><td class=\"answer\">")
                    .append(escape(truncate(row.answer(), 700))).append("</td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private String card(String label, double value) {
        return "<div class=\"card\"><div class=\"muted\">" + escape(label)
                + "</div><div class=\"num\">" + value + "</div></div>";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String truncate(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit) + "...";
    }
}

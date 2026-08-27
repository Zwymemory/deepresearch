package com.deepresearch.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentReportResponse(
        OffsetDateTime generatedAt,
        long sessions,
        long runs,
        long messages,
        long memories,
        long feedbacks,
        long badCases,
        double badCaseRate,
        List<ToolUsage> toolUsage,
        List<RecentRun> recentRuns
) {
    public record ToolUsage(String tool, long count) {
    }

    public record RecentRun(
            String runId,
            String sessionId,
            String userId,
            String question,
            boolean finished,
            int rounds,
            OffsetDateTime createdAt
    ) {
    }
}

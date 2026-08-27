package com.deepresearch.web;

import com.deepresearch.service.AgentStateService;
import com.deepresearch.service.AgentReportService;
import com.deepresearch.service.UserContextService;
import com.deepresearch.web.dto.AgentBadCaseResponse;
import com.deepresearch.web.dto.AgentFeedbackRequest;
import com.deepresearch.web.dto.AgentFeedbackResponse;
import com.deepresearch.web.dto.AgentMemoryRequest;
import com.deepresearch.web.dto.AgentMemoryResponse;
import com.deepresearch.web.dto.AgentReportResponse;
import com.deepresearch.web.dto.AgentSessionSummary;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * W9：Agent 状态、反馈和记忆管理接口。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentStateService agentStateService;
    private final AgentReportService agentReportService;
    private final UserContextService userContextService;

    public AgentController(AgentStateService agentStateService,
                           AgentReportService agentReportService,
                           UserContextService userContextService) {
        this.agentStateService = agentStateService;
        this.agentReportService = agentReportService;
        this.userContextService = userContextService;
    }

    @PostMapping("/runs/{runId}/feedback")
    public AgentFeedbackResponse feedback(@PathVariable String runId,
                                          @RequestBody @Valid AgentFeedbackRequest request) {
        String userId = userContextService.currentUser();
        return agentStateService.addFeedback(runId, userId, request);
    }

    @GetMapping("/bad-cases")
    public List<AgentBadCaseResponse> badCases(@RequestParam(defaultValue = "50") int limit) {
        userContextService.requireAdmin();
        return agentStateService.listBadCases(limit);
    }

    @GetMapping("/sessions")
    public List<AgentSessionSummary> sessions(@RequestParam(defaultValue = "20") int limit) {
        String userId = userContextService.currentUser();
        return agentStateService.listSessions(userId, limit);
    }

    @PostMapping("/memories")
    public AgentMemoryResponse createMemory(@RequestBody @Valid AgentMemoryRequest request) {
        String userId = userContextService.currentUser();
        return agentStateService.createMemory(new AgentMemoryRequest(
                userId,
                request.memoryType(),
                request.content(),
                request.source(),
                request.confidence()
        ));
    }

    @GetMapping("/memories")
    public List<AgentMemoryResponse> memories(@RequestParam(defaultValue = "20") int limit) {
        String userId = userContextService.currentUser();
        return agentStateService.listMemories(userId, limit);
    }

    @DeleteMapping("/memories/{memoryId}")
    public Map<String, Object> deleteMemory(@PathVariable String memoryId) {
        String userId = userContextService.currentUser();
        boolean deleted = agentStateService.deleteMemory(memoryId, userId);
        return Map.of("memoryId", memoryId, "deleted", deleted);
    }

    @GetMapping("/report")
    public AgentReportResponse report(@RequestParam(defaultValue = "10") int recentLimit) {
        userContextService.requireAdmin();
        return agentReportService.report(recentLimit);
    }
}

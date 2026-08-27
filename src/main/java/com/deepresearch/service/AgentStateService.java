package com.deepresearch.service;

import com.deepresearch.web.dto.AgentBadCaseResponse;
import com.deepresearch.web.dto.AgentFeedbackRequest;
import com.deepresearch.web.dto.AgentFeedbackResponse;
import com.deepresearch.web.dto.AgentMemoryRequest;
import com.deepresearch.web.dto.AgentMemoryResponse;
import com.deepresearch.web.dto.AgentResearchResponse;
import com.deepresearch.web.dto.AgentSessionSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Agent 状态生命周期的事务编排层。
 *
 * SQL 分别由 session/run/feedback/memory Repository 负责；上下文选择与会话压缩由专用
 * Service 负责。本类保留原有公共 API，并明确多表写入必须在同一事务中完成。
 */
@Service
public class AgentStateService {

    private final AgentContextService contextService;
    private final AgentSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final AgentFeedbackRepository feedbackRepository;
    private final AgentMemoryRepository memoryRepository;
    private final ConversationCompressionCoordinator compressionCoordinator;

    public AgentStateService(AgentContextService contextService,
                             AgentSessionRepository sessionRepository,
                             AgentRunRepository runRepository,
                             AgentFeedbackRepository feedbackRepository,
                             AgentMemoryRepository memoryRepository,
                             ConversationCompressionCoordinator compressionCoordinator) {
        this.contextService = contextService;
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.feedbackRepository = feedbackRepository;
        this.memoryRepository = memoryRepository;
        this.compressionCoordinator = compressionCoordinator;
    }

    @Transactional
    public AgentContext prepareContext(String requestedSessionId, String requestedUserId, String question) {
        return contextService.prepare(requestedSessionId, requestedUserId, question);
    }

    @Transactional
    public void storeRun(String question, AgentResearchResponse response, String userId) {
        if (response.sessionId() == null || response.sessionId().isBlank()) {
            return;
        }
        String normalizedUserId = AgentIdentitySupport.userId(userId);
        runRepository.insert(question, response, normalizedUserId);
        sessionRepository.insertMessage(
                "msg-" + UUID.randomUUID(), response.sessionId(), response.runId(), "user", question);
        sessionRepository.insertMessage(
                "msg-" + UUID.randomUUID(), response.sessionId(), response.runId(), "assistant", response.answer());
        sessionRepository.touch(response.sessionId());
        compressionCoordinator.compressIfNeeded(response.sessionId());
    }

    @Transactional
    public AgentFeedbackResponse addFeedback(String runId, String userId, AgentFeedbackRequest request) {
        String normalizedUserId = AgentIdentitySupport.userId(userId);
        if (!runRepository.belongsToUser(runId, normalizedUserId)) {
            throw new ResponseStatusException(FORBIDDEN, "runId 不存在或不属于当前用户");
        }
        return feedbackRepository.insert(
                "fb-" + UUID.randomUUID(), runId, normalizeRating(request.rating()), request);
    }

    public List<AgentBadCaseResponse> listBadCases(int limit) {
        return feedbackRepository.listBadCases(Math.max(1, Math.min(limit, 200)));
    }

    public List<AgentSessionSummary> listSessions(String requestedUserId, int limit) {
        return sessionRepository.list(
                AgentIdentitySupport.userId(requestedUserId), Math.max(1, Math.min(limit, 100)));
    }

    @Transactional
    public AgentMemoryResponse createMemory(AgentMemoryRequest request) {
        double confidence = request.confidence() == null
                ? 1.0 : Math.max(0.0, Math.min(1.0, request.confidence()));
        return memoryRepository.insert(
                "mem-" + UUID.randomUUID(),
                AgentIdentitySupport.userId(request.userId()),
                request,
                confidence);
    }

    public List<AgentMemoryResponse> listMemories(String requestedUserId, int limit) {
        return memoryRepository.list(
                AgentIdentitySupport.userId(requestedUserId), Math.max(1, Math.min(limit, 100)));
    }

    @Transactional
    public boolean deleteMemory(String memoryId, String userId) {
        return memoryRepository.delete(memoryId, AgentIdentitySupport.userId(userId));
    }

    private String normalizeRating(String rating) {
        String normalized = rating == null ? "" : rating.trim().toUpperCase(Locale.ROOT);
        if (!"UP".equals(normalized) && !"DOWN".equals(normalized)) {
            throw new IllegalArgumentException("rating 只能是 UP 或 DOWN");
        }
        return normalized;
    }

    public record AgentContext(
            String sessionId,
            String userId,
            String sessionSummary,
            List<String> recentConversation,
            List<String> memories,
            AgentResearchResponse.Diagnostics diagnostics
    ) {
        public AgentResearchResponse.MemoryContext toResponseMemoryContext() {
            return new AgentResearchResponse.MemoryContext(
                    sessionSummary, recentConversation, memories, diagnostics);
        }
    }
}

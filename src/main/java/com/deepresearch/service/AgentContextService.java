package com.deepresearch.service;

import com.deepresearch.web.dto.AgentResearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 组装一次有状态 Agent 运行需要的 session 摘要、最近消息和相关长期记忆。
 * session 所有权在读取任何历史内容前校验。
 */
@Service
class AgentContextService {

    private final AgentSessionRepository sessionRepository;
    private final MemorySelectionService memorySelectionService;
    private final int recentMessageLimit;

    AgentContextService(AgentSessionRepository sessionRepository,
                        MemorySelectionService memorySelectionService,
                        @Value("${deepresearch.memory.recent-messages:8}") int recentMessageLimit) {
        this.sessionRepository = sessionRepository;
        this.memorySelectionService = memorySelectionService;
        this.recentMessageLimit = Math.max(2, recentMessageLimit);
    }

    AgentStateService.AgentContext prepare(String requestedSessionId, String requestedUserId, String question) {
        String userId = AgentIdentitySupport.userId(requestedUserId);
        String sessionId = AgentIdentitySupport.sessionId(requestedSessionId);
        if (sessionId == null) {
            sessionId = "sess-" + UUID.randomUUID();
        }
        sessionRepository.upsertOwned(sessionId, userId, titleFrom(question));
        String summary = sessionRepository.summary(sessionId);
        int messageCount = sessionRepository.messageCount(sessionId);
        MemorySelectionService.Selection memories = memorySelectionService.select(userId, question);
        return new AgentStateService.AgentContext(
                sessionId,
                userId,
                summary,
                sessionRepository.recentConversation(sessionId, recentMessageLimit),
                memories.rows(),
                new AgentResearchResponse.Diagnostics(
                        summary != null && !summary.isBlank(),
                        Math.min(messageCount, recentMessageLimit),
                        memories.rows().size(),
                        memories.totalCount(),
                        memories.reason()));
    }

    private String titleFrom(String question) {
        String normalized = question == null ? "新会话" : question.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "新会话";
        }
        return normalized.length() <= 40 ? normalized : normalized.substring(0, 40);
    }
}

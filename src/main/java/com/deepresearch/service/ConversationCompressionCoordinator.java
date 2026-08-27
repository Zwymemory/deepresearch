package com.deepresearch.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/** 根据消息阈值增量压缩旧对话；只更新成功生成的摘要，近期窗口始终保留原文。 */
@Service
class ConversationCompressionCoordinator {

    private final AgentSessionRepository sessionRepository;
    private final ConversationSummaryService summaryService;
    private final int recentMessageLimit;
    private final int triggerMessages;

    ConversationCompressionCoordinator(AgentSessionRepository sessionRepository,
                                       ConversationSummaryService summaryService,
                                       @Value("${deepresearch.memory.recent-messages:8}") int recentMessageLimit,
                                       @Value("${deepresearch.memory.summary-trigger-messages:12}") int triggerMessages) {
        this.sessionRepository = sessionRepository;
        this.summaryService = summaryService;
        this.recentMessageLimit = Math.max(2, recentMessageLimit);
        this.triggerMessages = Math.max(this.recentMessageLimit + 2, triggerMessages);
    }

    void compressIfNeeded(String sessionId) {
        int total = sessionRepository.messageCount(sessionId);
        if (total <= triggerMessages) {
            return;
        }
        int older = Math.max(0, total - recentMessageLimit);
        int summarized = sessionRepository.summaryMessageCount(sessionId);
        if (older <= summarized) {
            return;
        }
        List<String> delta = sessionRepository.messagesForSummary(sessionId, summarized, older - summarized);
        String summary = summaryService.summarize(sessionRepository.summary(sessionId), delta);
        sessionRepository.updateSummary(sessionId, summary, older);
    }
}

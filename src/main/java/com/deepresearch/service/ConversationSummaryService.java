package com.deepresearch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * W9.5：会话摘要压缩。
 *
 * 对话变长后，不能把全部历史消息都塞进 Agent prompt。
 * 这个服务把较早消息压缩成 session summary，Agent prompt 只携带：
 * summary + 最近 N 轮原文 + 相关长期记忆。
 */
@Service
public class ConversationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    private final ChatClient chatClient;
    private final boolean enabled;
    private final int maxSummaryChars;

    public ConversationSummaryService(ChatClient chatClient,
                                      @Value("${deepresearch.memory.summary-enabled:true}") boolean enabled,
                                      @Value("${deepresearch.memory.max-summary-chars:1200}") int maxSummaryChars) {
        this.chatClient = chatClient;
        this.enabled = enabled;
        this.maxSummaryChars = maxSummaryChars;
    }

    /** Null means no successful new summary; caller must preserve both text and watermark. */
    public String summarize(String existingSummary, List<String> messages) {
        if (!enabled || messages == null || messages.isEmpty()) {
            return null;
        }

        String prompt = """
                你是 Agent 会话记忆压缩器。请把旧对话压缩成一段稳定、可复用的会话摘要。

                要求：
                1. 只保留对后续多轮任务有帮助的信息：用户目标、已确认事实、关键实体、配置项、决策、待办和未解决问题。
                2. 删除寒暄、重复内容、临时计算过程和无价值细节。
                3. 不要编造对话中没有出现的信息。
                4. 输出中文，控制在 %d 字以内。

                【已有摘要】
                %s

                【需要合并进摘要的旧消息】
                %s
                """.formatted(maxSummaryChars,
                existingSummary == null || existingSummary.isBlank() ? "（无）" : existingSummary,
                String.join("\n", messages));

        try {
            String summary = chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().temperature(0.1).build())
                    .call()
                    .content();
            return summary == null || summary.isBlank() ? null : truncate(summary.trim());
        } catch (RuntimeException e) {
            log.warn("会话摘要生成失败，保留旧摘要与进度：{}", e.getClass().getSimpleName());
            return null;
        }
    }

    private String truncate(String text) {
        if (text.length() <= maxSummaryChars) {
            return text;
        }
        int end = maxSummaryChars;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}

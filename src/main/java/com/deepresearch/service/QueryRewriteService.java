package com.deepresearch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * W6：把多轮对话里的指代问题改写成自包含检索 query。
 *
 * 默认关闭，避免影响 W5.5 benchmark；打开后失败会自动回退原问题。
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private final ChatClient chatClient;
    private final boolean enabled;
    private final int maxHistoryMessages;

    public QueryRewriteService(ChatClient chatClient,
                               @Value("${deepresearch.query-rewrite.enabled:false}") boolean enabled,
                               @Value("${deepresearch.query-rewrite.max-history-messages:6}") int maxHistoryMessages) {
        this.chatClient = chatClient;
        this.enabled = enabled;
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public RewriteResult rewrite(String question, List<String> history) {
        if (!enabled || history == null || history.isEmpty()) {
            return new RewriteResult(question, question, false, enabled ? "empty_history" : "disabled");
        }
        try {
            List<String> recentHistory = history.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .skip(Math.max(0, history.size() - maxHistoryMessages))
                    .toList();
            if (recentHistory.isEmpty()) {
                return new RewriteResult(question, question, false, "empty_history");
            }

            String prompt = """
                    你是企业知识库检索 query 改写器。
                    请根据对话历史，把当前问题改写成一个自包含、适合检索的中文或英文问题。
                    要求：
                    1. 只输出改写后的 query，不要解释。
                    2. 如果当前问题已经自包含，原样返回。
                    3. 不要补充对话历史里没有的信息。

                    【对话历史】
                    %s

                    【当前问题】
                    %s
                    """.formatted(String.join("\n", recentHistory), question);

            String rewritten = chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();
            String normalized = rewritten == null ? "" : rewritten.trim();
            if (normalized.isBlank()) {
                return new RewriteResult(question, question, false, "blank_result");
            }
            boolean used = !normalized.equals(question);
            return new RewriteResult(question, normalized, used, used ? "rewritten" : "unchanged");
        } catch (RuntimeException e) {
            log.warn("query rewrite failed, fallback to original question: {}", e.getMessage());
            return new RewriteResult(question, question, false, e.getMessage());
        }
    }

    public record RewriteResult(String originalQuestion, String rewrittenQuestion, boolean used, String reason) {
    }
}

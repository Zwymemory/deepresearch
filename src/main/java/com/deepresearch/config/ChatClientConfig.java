package com.deepresearch.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置 ChatClient。
 *
 * Spring AI 会根据 application.yml 里的 spring.ai.openai.* 自动装配一个
 * ChatClient.Builder（底层指向 DeepSeek 的 OpenAI 兼容接口）。
 * 我们在这里基于它构建一个带统一 system prompt 的 ChatClient Bean，
 * 全应用注入复用。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个严谨、客观的研究助理。回答要准确、有条理；" +
                        "如果不确定或缺乏依据，请直接说明，不要编造。")
                .build();
    }
}

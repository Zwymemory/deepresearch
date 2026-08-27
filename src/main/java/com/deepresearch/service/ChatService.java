package com.deepresearch.service;

import com.deepresearch.config.AgentProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 封装对大模型的调用。
 *
 * Week1 只做两件事：
 *  1. ask()    —— 阻塞式问答，一次性返回完整回答
 *  2. stream() —— 流式问答，token 逐步吐出（为 Week3 的 SSE 进度推送打基础）
 *
 * 把"怎么调模型"收敛到这一层，Controller 只管收发参数，后续主循环也复用它。
 */
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final AgentProperties props;

    public ChatService(ChatClient chatClient, AgentProperties props) {
        this.chatClient = chatClient;
        this.props = props;
    }

    /** 阻塞式问答 */
    public String ask(String message, Double temperature) {
        double temp = temperature != null ? temperature : props.getDefaultTemperature();
        return chatClient.prompt()
                .user(message)
                .options(OpenAiChatOptions.builder().temperature(temp).build())
                .call()
                .content();
    }

    /** 流式问答：返回一个文本片段的 Flux */
    public Flux<String> stream(String message, Double temperature) {
        double temp = temperature != null ? temperature : props.getDefaultTemperature();
        return chatClient.prompt()
                .user(message)
                .options(OpenAiChatOptions.builder().temperature(temp).build())
                .stream()
                .content();
    }
}

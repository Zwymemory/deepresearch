package com.deepresearch.web;

import com.deepresearch.service.ChatService;
import com.deepresearch.web.dto.ChatRequest;
import com.deepresearch.web.dto.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Week1 问答接口。
 *
 *  - POST /api/chat        阻塞式，返回完整回答（JSON）
 *  - GET  /api/chat/stream 流式，SSE 逐字返回（验证流式通路，为 Week3 SSE 做铺垫）
 *  - GET  /api/ping        简单存活检查
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    /** 从配置读取当前模型名，回显给前端便于确认 */
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String modelName;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody @Valid ChatRequest request) {
        String answer = chatService.ask(request.message(), request.temperature());
        return new ChatResponse(answer, modelName);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String message,
                                   @RequestParam(required = false) Double temperature) {
        return chatService.stream(message, temperature);
    }

    @GetMapping("/ping")
    public String ping() {
        return "DeepResearch is up. model=" + modelName;
    }
}

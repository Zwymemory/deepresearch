package com.deepresearch.web;

import com.deepresearch.service.RagService;
import com.deepresearch.service.HybridRagService;
import com.deepresearch.service.AgentRuntimeRouter;
import com.deepresearch.service.AgentIdempotencyService;
import com.deepresearch.service.UserContextService;
import com.deepresearch.service.VectorRagService;
import com.deepresearch.service.KnowledgeRetrievalGateway;
import com.deepresearch.service.RagflowAnswerService;
import com.deepresearch.service.RagflowDebugService;
import com.deepresearch.web.dto.AgentResearchRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import com.deepresearch.web.dto.HybridDebugResponse;
import com.deepresearch.web.dto.ResearchAnswer;
import com.deepresearch.web.dto.ResearchRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

/**
 * 研究问答接口。
 *
 *  - POST /api/research/simple  【Week2】Tavily 实时搜网页，先搜后答，带 [来源N] 引用
 *  - POST /api/research/vector  【Week4】在自建向量知识库里语义检索后回答（经典 RAG）
 *  - POST /api/research/hybrid  【W4.2】向量检索 + 关键词检索 + RRF 融合
 *  - POST /api/research/agent   【Week3】ReAct Agent：自主多轮检索→推理→回答，返回完整思考轨迹
 *
 * 递进：/api/chat（凭记忆答） → /research/simple（实时搜） → /research/vector（向量库召回）
 *      → /research/agent（自主多轮研究）。
 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private final RagService ragService;
    private final VectorRagService vectorRagService;
    private final HybridRagService hybridRagService;
    private final KnowledgeRetrievalGateway retrievalGateway;
    private final RagflowAnswerService ragflowAnswerService;
    private final RagflowDebugService ragflowDebugService;
    private final AgentRuntimeRouter agentRuntime;
    private final UserContextService userContextService;
    private final AgentIdempotencyService idempotencyService;

    public ResearchController(RagService ragService,
                             VectorRagService vectorRagService,
                             HybridRagService hybridRagService,
                             KnowledgeRetrievalGateway retrievalGateway,
                             RagflowAnswerService ragflowAnswerService,
                             RagflowDebugService ragflowDebugService,
                             AgentRuntimeRouter agentRuntime,
                             UserContextService userContextService,
                             AgentIdempotencyService idempotencyService) {
        this.ragService = ragService;
        this.vectorRagService = vectorRagService;
        this.hybridRagService = hybridRagService;
        this.retrievalGateway = retrievalGateway;
        this.ragflowAnswerService = ragflowAnswerService;
        this.ragflowDebugService = ragflowDebugService;
        this.agentRuntime = agentRuntime;
        this.userContextService = userContextService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/simple")
    public ResearchAnswer simple(@RequestBody @Valid ResearchRequest request) {
        return ragService.answer(request.question(), request.topK());
    }

    @PostMapping("/vector")
    public ResearchAnswer vector(@RequestBody @Valid ResearchRequest request) {
        if (retrievalGateway.ragflow()) return ragflowAnswerService.answer(request.question(), request.topK());
        return vectorRagService.answer(request.question(), request.topK());
    }

    @PostMapping("/hybrid")
    public ResearchAnswer hybrid(@RequestBody @Valid ResearchRequest request) {
        if (retrievalGateway.ragflow()) return ragflowAnswerService.answer(request.question(), request.topK(), request.history());
        return hybridRagService.answer(request.question(), request.topK(), request.history());
    }

    @PostMapping("/hybrid/debug")
    public HybridDebugResponse hybridDebug(@RequestBody @Valid ResearchRequest request) {
        if (retrievalGateway.ragflow()) return ragflowDebugService.debug(request.question(), request.topK(), request.history());
        return hybridRagService.debug(request.question(), request.topK(), request.recallK(), request.candidateK(), request.history());
    }

    @PostMapping("/agent")
    public ResponseEntity<AgentResearchResponse> agent(
            @RequestBody @Valid AgentResearchRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String userId = userContextService.currentUser();
        AgentResearchRequest scopedRequest = new AgentResearchRequest(
                request.question(), request.sessionId(), userId);
        AgentIdempotencyService.Outcome outcome = idempotencyService.execute(
                userId,
                "/api/research/agent",
                idempotencyKey,
                scopedRequest,
                () -> agentRuntime.run(scopedRequest));
        return ResponseEntity.ok()
                .header("Idempotency-Replayed", Boolean.toString(outcome.replayed()))
                .body(outcome.response());
    }

    @GetMapping("/agent/idempotency/{idempotencyKey}")
    public AgentIdempotencyService.StatusView agentIdempotencyStatus(
            @org.springframework.web.bind.annotation.PathVariable String idempotencyKey,
            @RequestParam(defaultValue = "/api/research/agent") String endpoint) {
        if (!java.util.Set.of("/api/research/agent", "/api/research/agent/stream").contains(endpoint)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "不支持查询该 endpoint");
        }
        return idempotencyService.status(userContextService.currentUser(), endpoint, idempotencyKey);
    }

    @GetMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentStream(@RequestParam String question,
                                  @RequestParam(required = false) String sessionId,
                                  @RequestParam String requestKey,
                                  @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        String resolvedUserId = userContextService.currentUser();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        int resumeAfter = parseLastEventId(requestKey, lastEventId);
        if (resumeAfter >= 0) {
            // 有游标却没有同一租户/用户下的持久化请求记录时，不能悄悄创建一条新业务运行。
            idempotencyService.status(
                    resolvedUserId, "/api/research/agent/stream", requestKey);
        }
        AgentResearchRequest request = new AgentResearchRequest(question, sessionId, resolvedUserId);
        AgentIdempotencyService.Prepared prepared = idempotencyService.prepare(
                resolvedUserId, "/api/research/agent/stream", requestKey, request);
        CompletableFuture.runAsync(() -> {
            SecurityContext asyncContext = SecurityContextHolder.createEmptyContext();
            asyncContext.setAuthentication(authentication);
            SecurityContextHolder.setContext(asyncContext);
            try {
                if (resumeAfter < 0) {
                    sendEvent(emitter, requestKey + ":0", "STREAM_OPENED",
                            Map.of("requestKey", requestKey, "resumeAfter", resumeAfter));
                }
                AtomicBoolean connected = new AtomicBoolean(true);
                // SSE 断连只停止传输，不能把已认领的业务执行变成未知状态；
                // executePrepared 仍需完成并保存结果，客户端才能凭 requestKey 重放。
                AgentIdempotencyService.Outcome outcome = idempotencyService.executePrepared(
                        prepared,
                        () -> agentRuntime.run(request, event -> {
                            if (connected.get() && event.seq() > resumeAfter) {
                                connected.set(sendEvent(
                                        emitter, requestKey + ":" + event.seq(), event.type(), event));
                            }
                        }));
                AgentResearchResponse response = outcome.response();
                int finalSequence = response.events().stream()
                        .mapToInt(AgentResearchResponse.Event::seq)
                        .max().orElse(0) + 1;
                if (outcome.replayed()) {
                    if (resumeAfter > finalSequence) {
                        sendEvent(emitter, requestKey + ":" + finalSequence, "RESYNC_REQUIRED",
                                Map.of("reason", "cursor_ahead_of_stream", "snapshot", response));
                    } else {
                        response.events().stream()
                                .filter(event -> event.seq() > resumeAfter)
                                .forEach(event -> sendEvent(
                                        emitter, requestKey + ":" + event.seq(), event.type(), event));
                    }
                }
                if (resumeAfter < finalSequence) {
                    sendEvent(emitter, requestKey + ":" + finalSequence, "FINAL", response);
                }
                emitter.complete();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        return emitter;
    }

    private int parseLastEventId(String requestKey, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return -1;
        }
        String cursor = lastEventId.trim();
        int separator = cursor.lastIndexOf(':');
        if (separator >= 0) {
            if (!requestKey.equals(cursor.substring(0, separator))) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "Last-Event-ID 不属于当前 requestKey");
            }
            cursor = cursor.substring(separator + 1);
        }
        try {
            int sequence = Integer.parseInt(cursor);
            if (sequence < 0) {
                throw new NumberFormatException("negative");
            }
            return sequence;
        } catch (NumberFormatException failure) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Last-Event-ID 格式无效");
        }
    }

    private boolean sendEvent(SseEmitter emitter, String id, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().id(id).name(name).data(data).reconnectTime(1_000));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

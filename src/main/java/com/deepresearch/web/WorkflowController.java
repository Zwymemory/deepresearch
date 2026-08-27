package com.deepresearch.web;

import com.deepresearch.service.UserContextService;
import com.deepresearch.workflow.WorkflowDtos.Accepted;
import com.deepresearch.workflow.WorkflowDtos.Cancelled;
import com.deepresearch.workflow.WorkflowDtos.CreateRequest;
import com.deepresearch.workflow.WorkflowDtos.Event;
import com.deepresearch.workflow.WorkflowService;
import com.deepresearch.workflow.WorkflowStatus;
import jakarta.validation.Valid;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Public REST/SSE facade for durable multi-agent workflows. */
@RestController
@RequestMapping("/api/research/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final UserContextService userContextService;
    private final AtomicInteger sseThreadSequence = new AtomicInteger();
    private final ExecutorService sseExecutor = new ThreadPoolExecutor(
            4, 32, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable,
                        "workflow-sse-" + sseThreadSequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public WorkflowController(WorkflowService workflowService, UserContextService userContextService) {
        this.workflowService = workflowService;
        this.userContextService = userContextService;
    }

    @PostMapping
    public ResponseEntity<Accepted> create(@RequestBody @Valid CreateRequest request,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Accepted accepted = workflowService.create(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Idempotency-Replayed", Boolean.toString(accepted.replayed()))
                .body(accepted);
    }

    @GetMapping("/{runId}")
    public com.deepresearch.workflow.WorkflowDtos.View get(@PathVariable String runId) {
        return workflowService.get(runId);
    }

    @PostMapping("/{runId}/cancel")
    public Cancelled cancel(@PathVariable String runId) {
        return workflowService.cancel(runId);
    }

    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletResponse response) {
        String userId = userContextService.currentUser();
        workflowService.ownedRun(runId, userId);
        long cursor = parseCursor(runId, lastEventId);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(3).toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(ignored -> closed.set(true));
        sseExecutor.execute(() -> stream(emitter, runId, userId, cursor, closed));
        return emitter;
    }

    private void stream(SseEmitter emitter, String runId, String userId, long initialCursor,
                        AtomicBoolean closed) {
        long cursor = initialCursor;
        Instant lastWrite = Instant.now();
        try {
            while (!closed.get()) {
                List<Event> events = workflowService.eventsAfterOwned(runId, userId, cursor);
                for (Event event : events) {
                    emitter.send(SseEmitter.event().id(event.id()).name(event.type())
                            .data(event).reconnectTime(1_000));
                    cursor = event.eventId();
                    lastWrite = Instant.now();
                }
                var run = workflowService.ownedRun(runId, userId);
                WorkflowStatus status = WorkflowStatus.valueOf(run.status());
                if (status.terminal() && events.isEmpty()) {
                    emitter.complete();
                    return;
                }
                if (Duration.between(lastWrite, Instant.now()).toSeconds() >= 15) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastWrite = Instant.now();
                }
                Thread.sleep(events.isEmpty() ? 1_000 : 100);
            }
        } catch (IOException disconnected) {
            emitter.complete();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(interrupted);
        } catch (RuntimeException failure) {
            emitter.completeWithError(failure);
        }
    }

    @PreDestroy
    void shutdownSseExecutor() {
        sseExecutor.shutdownNow();
    }

    private long parseCursor(String runId, String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String prefix = runId + ":";
        if (!raw.startsWith(prefix)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Last-Event-ID 不属于当前 workflow");
        }
        try {
            long cursor = Long.parseLong(raw.substring(prefix.length()));
            if (cursor < 0) {
                throw new NumberFormatException("negative");
            }
            return cursor;
        } catch (NumberFormatException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Last-Event-ID 格式无效");
        }
    }
}

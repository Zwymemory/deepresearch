package com.deepresearch.workflow;

import com.deepresearch.service.KnowledgeRetrievalGateway;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Rechecks RAGFlow live chunks and verifies exact run-bound web receipt snapshots. */
@Component
class DifyCitationValidator {
    private static final int MAX_CITATIONS = 8;
    private static final int DEADLINE_SECONDS = 8;
    private final KnowledgeRetrievalGateway gateway;
    private final WorkflowRepository repository;
    private final ThreadPoolExecutor checks = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "dify-citation-check");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    DifyCitationValidator(KnowledgeRetrievalGateway gateway, WorkflowRepository repository) {
        this.gateway = gateway;
        this.repository = repository;
    }

    boolean available(String runId, List<String> citations) {
        if (citations == null || citations.isEmpty() || citations.size() > MAX_CITATIONS) return false;
        Future<Boolean> check;
        try {
            check = checks.submit(() -> {
                for (String citation : citations) {
                    if (Thread.currentThread().isInterrupted()) return false;
                    if (DifyWebEvidence.ID.matcher(citation).matches()) {
                        if (!DifyWebEvidence.valid(repository.difyWebSource(runId, citation).orElse(null))) return false;
                    } else if (!gateway.citationExists(citation)) return false;
                }
                return true;
            });
        } catch (RuntimeException saturated) {
            return false;
        }
        try {
            return check.get(DEADLINE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            check.cancel(true);
            return false;
        } catch (Exception unavailable) {
            check.cancel(true);
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        checks.shutdownNow();
    }
}

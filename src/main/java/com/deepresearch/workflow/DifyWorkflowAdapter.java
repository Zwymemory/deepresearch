package com.deepresearch.workflow;

import com.deepresearch.agent.ToolArgumentFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@EnableScheduling
public class DifyWorkflowAdapter {
    private static final Pattern MARKER = Pattern.compile("\\[来源(\\d+)]");
    private static final Pattern SOURCE = Pattern.compile("kb:ragflow:[^:\\s]+:[^:\\s]+:[^:\\s]+");
    private final WorkflowRepository repository;
    private final DifyWorkflowClient client;
    private final ObjectMapper json;
    private final String engine;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ExecutorService controlExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService reconcileExecutor = Executors.newFixedThreadPool(4);
    private final Semaphore dispatchSlots = new Semaphore(2);
    private final Semaphore reconcileSlots = new Semaphore(4);
    private final Set<String> reconciling = ConcurrentHashMap.newKeySet();

    public DifyWorkflowAdapter(WorkflowRepository repository, DifyWorkflowClient client, ObjectMapper json,
                               @Value("${deepresearch.workflow.engine:langgraph}") String engine) {
        this.repository = repository;
        this.client = client;
        this.json = json;
        this.engine = engine;
    }

    @Scheduled(fixedDelay = 3000)
    public void dispatch() {
        if (!"dify".equals(engine)) return;
        while (dispatchSlots.tryAcquire()) {
            List<String> claimed = repository.claimDifyDispatches(1);
            if (claimed.isEmpty()) {
                dispatchSlots.release();
                break;
            }
            executor.submit(() -> {
                try { run(claimed.get(0)); }
                finally { dispatchSlots.release(); }
            });
        }
    }

    private void run(String runId) {
        WorkflowRepository.RunRow row = repository.find(runId).orElseThrow();
        try {
            String summary = json.readTree(row.contextSnapshotJson()).path("sessionSummary").asText("");
            Map<String, Object> inputs = Map.of("question", row.question(), "java_run_id", runId,
                    "allowed_tools", String.join(",", row.requestedScopes()),
                    "session_summary", summary);
            client.run(inputs, internalUser(row), event -> onEvent(runId, event));
            var mapping = repository.difyMapping(runId).orElseThrow();
            if (mapping.workflowRunId() != null) reconcile(runId);
            else markDispatchUnknown(runId);
        } catch (Exception failure) {
            var mapping = repository.difyMapping(runId).orElse(null);
            if (mapping == null || mapping.workflowRunId() == null) {
                markDispatchUnknown(runId);
            }
        }
    }

    private void onEvent(String runId, JsonNode event) {
        String kind = event.path("event").asText("");
        String remoteRunId = event.path("workflow_run_id").asText("");
        String taskId = event.path("task_id").asText("");
        if (!remoteRunId.isBlank() && !taskId.isBlank()) {
            repository.bindDify(runId, remoteRunId, taskId);
            if (repository.find(runId).map(WorkflowRepository.RunRow::cancelRequested).orElse(false)) {
                stop(runId);
            }
        }
        String eventId = event.path("data").path("id").asText("");
        if (eventId.isBlank()) eventId = event.path("id").asText("");
        if (!eventId.matches("[A-Za-z0-9_-]{1,100}")) return;
        if (Set.of("node_started", "node_finished", "workflow_started", "workflow_finished").contains(kind)) {
            String nodeType = event.path("data").path("node_type").asText("");
            String safe = "{\"event\":\"" + kind + "\",\"nodeType\":\"" +
                    nodeType.replaceAll("[^A-Za-z_-]", "") + "\"}";
            repository.insertEvent(runId, "dify:" + kind + ":" + eventId, "SYSTEM", null, "DIFY_STAGE", safe);
        }
    }

    @Scheduled(fixedDelay = 10000)
    public void reconcileBound() {
        // Existing Dify runs remain authoritative even after new traffic is switched
        // back to LangGraph. Keep timing them out and reconciling their remote state.
        repository.timeoutPendingDify();
        for (String runId : repository.abandonStaleDifyDispatches()) {
            repository.insertEvent(runId, "dify:dispatch:unknown", "SYSTEM", null,
                    "DISPATCH_UNKNOWN", "{\"status\":\"DISPATCH_UNKNOWN\"}");
        }
        for (String runId : repository.expiredDifyRuns()) {
            if (repository.timeoutDify(runId)) {
                stop(runId);
            }
        }
        int available = reconcileSlots.availablePermits();
        if (available == 0) return;
        for (String runId : repository.claimBoundDifyRuns(available)) {
            if (!reconciling.add(runId) || !reconcileSlots.tryAcquire()) continue;
            try {
                reconcileExecutor.submit(() -> {
                    try {
                        reconcile(runId);
                    } catch (Exception ignored) {
                        // A bounded detail request is retried on a later rotation.
                    } finally {
                        reconciling.remove(runId);
                        reconcileSlots.release();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                reconciling.remove(runId);
                reconcileSlots.release();
            }
        }
    }

    private void markDispatchUnknown(String runId) {
        if (repository.unknownDifyDispatch(runId)) {
            repository.insertEvent(runId, "dify:dispatch:unknown", "SYSTEM", null,
                    "DISPATCH_UNKNOWN", "{\"status\":\"DISPATCH_UNKNOWN\"}");
        }
    }

    public void reconcile(String runId) throws Exception {
        var mapping = repository.difyMapping(runId).orElseThrow();
        if (mapping.workflowRunId() == null) return;
        JsonNode detail = client.detail(mapping.workflowRunId());
        String remoteStatus = detail.path("data").path("status").asText(detail.path("status").asText(""));
        if (!Set.of("succeeded", "partial-succeeded", "failed", "stopped").contains(remoteStatus)) return;
        JsonNode outputs = detail.path("data").path("outputs");
        if (outputs.isMissingNode()) outputs = detail.path("outputs");
        WorkflowStatus status = switch (outputs.path("status").asText("")) {
            case "SUCCEEDED" -> WorkflowStatus.SUCCEEDED;
            case "INSUFFICIENT_EVIDENCE" -> WorkflowStatus.INSUFFICIENT_EVIDENCE;
            default -> WorkflowStatus.FAILED;
        };
        if (!"succeeded".equals(remoteStatus)) status = WorkflowStatus.FAILED;
        String answer = outputs.path("answer").asText("").trim();
        List<String> citations = new ArrayList<>();
        if (outputs.path("citations").isArray()) {
            for (JsonNode value : outputs.path("citations")) {
                citations.add(value.isTextual() ? value.asText() : "");
                if (citations.size() > 32) break;
            }
        }
        String error = null;
        if (status == WorkflowStatus.FAILED) {
            error = "succeeded".equals(remoteStatus) ? "DIFY_OUTPUT_INVALID" : "DIFY_WORKFLOW_FAILED";
        }
        if (status == WorkflowStatus.SUCCEEDED && !validCitations(runId, answer, citations)) {
            status = WorkflowStatus.FAILED;
            error = "CITATION_VALIDATION_FAILED";
            answer = "";
            citations = List.of();
        }
        if (status != WorkflowStatus.SUCCEEDED) { answer = ""; citations = List.of(); }
        String response = json.writeValueAsString(Map.of("answer", answer, "citations", citations,
                "citationContract", status == WorkflowStatus.SUCCEEDED ? "INDEXED_V1" : "NONE",
                "insufficientEvidence", status == WorkflowStatus.INSUFFICIENT_EVIDENCE));
        JsonNode usage = detail.path("data").path("total_tokens");
        if (usage.isMissingNode()) usage = detail.path("total_tokens");
        String usageJson = usage.isNumber() ? json.writeValueAsString(Map.of("totalTokens", usage.asLong())) : "{}";
        repository.finishDify(runId, status, response, usageJson, error, answer);
    }

    private boolean validCitations(String runId, String answer, List<String> citations) {
        if (answer.isBlank() || answer.length() > 32768 || citations.isEmpty() || citations.size() > 32
                || citations.stream().anyMatch(value -> value == null || value.length() > 2048)
                || citations.stream().distinct().count() != citations.size()
                || citations.stream().anyMatch(value -> !SOURCE.matcher(value).matches())) return false;
        Set<String> allowed = repository.difySources(runId);
        if (!allowed.containsAll(citations)) return false;
        Matcher marker = MARKER.matcher(answer);
        List<Integer> firstAppearance = new ArrayList<>();
        while (marker.find()) {
            int index;
            try {
                index = Integer.parseInt(marker.group(1));
            } catch (NumberFormatException invalidMarker) {
                return false;
            }
            if (index < 1 || index > citations.size()) return false;
            if (!firstAppearance.contains(index)) firstAppearance.add(index);
        }
        if (firstAppearance.size() != citations.size()) return false;
        for (int i = 0; i < firstAppearance.size(); i++) if (firstAppearance.get(i) != i + 1) return false;
        return true;
    }

    public void stop(String runId) {
        var row = repository.find(runId).orElseThrow();
        var mapping = repository.difyMapping(runId).orElse(null);
        if (mapping == null || mapping.taskId() == null) return;
        controlExecutor.submit(() -> {
            try { client.stop(mapping.taskId(), internalUser(row)); }
            catch (Exception ignored) { /* Java cancellation remains authoritative */ }
        });
    }

    private String internalUser(WorkflowRepository.RunRow row) {
        return "java:" + ToolArgumentFingerprint.sha256(row.userId()).substring(0, 32);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        controlExecutor.shutdownNow();
        reconcileExecutor.shutdownNow();
    }
}

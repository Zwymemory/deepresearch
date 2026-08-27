package com.deepresearch.service;

import com.deepresearch.agent.ToolArgumentFingerprint;
import com.deepresearch.workflow.WorkflowDtos;
import com.deepresearch.workflow.WorkflowRepository;
import com.deepresearch.workflow.WorkflowService;
import com.deepresearch.workflow.WorkflowStatus;
import com.deepresearch.web.dto.AgentResearchRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Opt-in online Harness adapter for the durable LangGraph implementation. */
@Service
class WorkflowHarnessAdapter {

    private final WorkflowService workflowService;
    private final WorkflowRepository repository;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    WorkflowHarnessAdapter(WorkflowService workflowService,
                           WorkflowRepository repository,
                           ObjectMapper objectMapper,
                           @Value("${deepresearch.workflow.harness-timeout:125s}") Duration timeout) {
        this.workflowService = workflowService;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.timeout = timeout == null ? Duration.ofSeconds(125) : timeout;
    }

    AgentEvaluationRun run(AgentResearchRequest request,
                           List<String> allowedTools,
                           String evaluationId) {
        String material = evaluationId + ":" + request.sessionId() + ":" + request.question();
        String hash = ToolArgumentFingerprint.sha256(material);
        String sessionId = "sess-wf-eval-" + hash.substring(0, 24);
        String idempotencyKey = "wf-eval-" + hash.substring(0, 32);
        List<String> scopes = workflowScopes(allowedTools);
        WorkflowDtos.Accepted accepted = workflowService.create(
                new WorkflowDtos.CreateRequest(request.question(), sessionId, scopes), idempotencyKey);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        WorkflowRepository.RunRow row;
        while (true) {
            row = repository.find(accepted.runId())
                    .orElseThrow(() -> new IllegalStateException("workflow harness run disappeared"));
            if (WorkflowStatus.valueOf(row.status()).terminal()) {
                break;
            }
            if (System.nanoTime() >= deadlineNanos) {
                workflowService.cancel(accepted.runId());
                throw new IllegalStateException("workflow harness timed out");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("workflow harness interrupted", interrupted);
            }
        }
        try (AgentEvaluationArtifact.Scope scope = AgentEvaluationArtifact.Scope.open(
                allowedTools, List.of("file_read"))) {
            AgentEvaluationArtifact artifact = scope.artifact();
            captureReceipts(row.runId(), json(row.finalResponseJson()), artifact);
            return new AgentEvaluationRun(response(row, artifact), artifact);
        }
    }

    private AgentResearchResponse response(WorkflowRepository.RunRow row,
                                           AgentEvaluationArtifact artifact) {
        JsonNode finalResponse = json(row.finalResponseJson());
        JsonNode usage = json(row.usageJson());
        List<WorkflowDtos.Event> workflowEvents = repository.eventsAfter(row.runId(), 0, 500);
        List<AgentResearchResponse.Event> events = new ArrayList<>();
        List<AgentResearchResponse.Step> steps = new ArrayList<>();
        int seq = 1;
        for (WorkflowDtos.Event event : workflowEvents) {
            JsonNode payload = event.payload();
            String action = text(payload, "toolName", text(payload, "tool", null));
            String taskId = event.taskId();
            String safeMessage = text(payload, "summary", event.type());
            if (event.role() != null && !event.role().isBlank()) {
                safeMessage = "[role=" + event.role().toUpperCase(java.util.Locale.ROOT) + "] " + safeMessage;
            }
            events.add(new AgentResearchResponse.Event(seq++, event.type(),
                    safeMessage, taskOrdinal(taskId), action));
            if (action != null && (event.type().contains("TOOL") || payload.has("outcomeCode"))) {
                steps.add(new AgentResearchResponse.Step(
                        taskOrdinal(taskId) == null ? steps.size() + 1 : taskOrdinal(taskId),
                        action, text(payload, "summary", "safe workflow tool event"),
                        text(payload, "outcomeCode", event.type().contains("FAILED") ? "TOOL_FAILED" : "OK"),
                        text(payload, "argumentFingerprint", "")));
            }
        }
        int rounds = (int) workflowEvents.stream().map(WorkflowDtos.Event::taskId)
                .filter(value -> value != null && !value.isBlank()).distinct().count();
        String publicStatus = switch (WorkflowStatus.valueOf(row.status())) {
            case SUCCEEDED -> "SUCCESS";
            case INSUFFICIENT_EVIDENCE -> "NO_EVIDENCE";
            case FAILED -> row.errorCode() == null || row.errorCode().isBlank()
                    ? WorkflowStatus.FAILED.name() : row.errorCode();
            default -> row.status();
        };
        return new AgentResearchResponse(
                row.runId(), row.sessionId(), productionAnswer(finalResponse),
                rounds, SetLike.success(row.status()),
                new AgentResearchResponse.MemoryContext("", List.of(), List.of(),
                        new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "workflow-harness")),
                List.copyOf(steps), List.copyOf(events), publicStatus,
                new AgentResearchResponse.Usage(
                        integer(usage, "inputTokens"), integer(usage, "outputTokens"),
                        integer(usage, "totalTokens"), true,
                        decimal(usage, "estimatedCost"), text(usage, "currency", "CNY"),
                        longValue(usage, "durationMs"), integer(usage, "modelCalls"),
                        integer(usage, "toolCalls")));
    }

    private void captureReceipts(String runId, JsonNode finalResponse,
                                 AgentEvaluationArtifact artifact) {
        Map<String, Integer> publicCitationIndexes = publicCitationIndexes(finalResponse);
        List<WorkflowRepository.ToolReceiptRow> receipts = repository.completedToolReceipts(runId);
        List<JsonNode> receiptResults = receipts.stream()
                .map(receipt -> json(receipt.safeResultJson()))
                .toList();
        Map<String, List<PublicEvidenceVariant>> aggregate = aggregatePublicEvidence(
                receiptResults, publicCitationIndexes);
        LinkedHashSet<String> capturedCitationSources = new LinkedHashSet<>();
        int modelRound = 1;
        for (int index = 0; index < receipts.size(); index++) {
            WorkflowRepository.ToolReceiptRow receipt = receipts.get(index);
            JsonNode result = receiptResults.get(index);
            String errorCode = text(result, "error_code", text(result, "errorCode", null));
            boolean successful = errorCode == null || errorCode.isBlank();
            String observation = successful
                    ? publicCitationObservation(
                            result, publicCitationIndexes, capturedCitationSources, aggregate)
                    : "";
            if (observation.isBlank()) {
                observation = "工具调用已完成；本次公开答案未引用该收据中的证据。";
            }
            String outcome = errorCode == null || errorCode.isBlank() ? "OK" : "TOOL_FAILED";
            artifact.captureIndexed(receipt.toolName(), outcome, receipt.requestFingerprint(),
                    observation, modelRound++);
        }
    }

    static Map<String, Integer> publicCitationIndexes(JsonNode finalResponse) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        JsonNode citations = finalResponse == null ? null : finalResponse.get("citations");
        if (citations == null || !citations.isArray()) {
            return indexes;
        }
        int index = 1;
        for (JsonNode citation : citations) {
            String sourceId = citation == null ? "" : citation.asText("").trim();
            if (!sourceId.isBlank()) {
                indexes.putIfAbsent(sourceId, index++);
            }
        }
        return indexes;
    }

    static String publicCitationObservation(JsonNode result,
                                            Map<String, Integer> publicCitationIndexes) {
        return publicCitationObservation(result, publicCitationIndexes, new LinkedHashSet<>());
    }

    static String publicCitationObservation(JsonNode result,
                                            Map<String, Integer> publicCitationIndexes,
                                            LinkedHashSet<String> capturedCitationSources) {
        return publicCitationObservation(result, publicCitationIndexes,
                capturedCitationSources, Map.of());
    }

    static String publicCitationObservation(JsonNode result,
                                            Map<String, Integer> publicCitationIndexes,
                                            LinkedHashSet<String> capturedCitationSources,
                                            Map<String, List<PublicEvidenceVariant>> aggregate) {
        StringBuilder observation = new StringBuilder();
        JsonNode evidences = result == null ? null : result.get("evidence");
        if (evidences == null || !evidences.isArray()) {
            return "";
        }
        for (JsonNode evidence : evidences) {
            String sourceId = textValue(evidence, "source_id",
                    textValue(evidence, "sourceId", ""));
            Integer publicIndex = publicCitationIndexes.get(sourceId);
            if (publicIndex == null) {
                continue;
            }
            if (!capturedCitationSources.add(sourceId)) {
                continue;
            }
            List<PublicEvidenceVariant> variants = aggregate.get(sourceId);
            if (variants == null || variants.isEmpty()) {
                variants = List.of(new PublicEvidenceVariant(
                        textValue(evidence, "source_uri",
                                textValue(evidence, "sourceUri", "")),
                        compactEvidenceText(textValue(evidence, "content", ""))));
            }
            for (PublicEvidenceVariant variant : variants) {
                observation.append("[来源").append(publicIndex).append("] ")
                        .append(sourceId).append('\n');
                if (!variant.uri().isBlank()) {
                    observation.append("URL: ").append(variant.uri()).append('\n');
                }
                observation.append("证据: ").append(variant.content()).append("\n\n");
            }
        }
        return observation.toString().trim();
    }

    /**
     * Merge content variants for the same immutable source before Harness grounding.
     *
     * <p>Different workers can retrieve the same source id with differently sized snippets.
     * Keeping only the first snippet creates false grounding failures. Variants with the same
     * URI are therefore combined into one public evidence reference. Two different non-empty
     * URIs remain separate references with the same marker so the assertion engine reports an
     * ambiguity instead of silently merging conflicting source identities.</p>
     */
    static Map<String, List<PublicEvidenceVariant>> aggregatePublicEvidence(
            List<JsonNode> results,
            Map<String, Integer> publicCitationIndexes) {
        Map<String, LinkedHashMap<String, LinkedHashSet<String>>> collected = new LinkedHashMap<>();
        for (JsonNode result : results == null ? List.<JsonNode>of() : results) {
            String errorCode = textValue(result, "error_code",
                    textValue(result, "errorCode", ""));
            if (!errorCode.isBlank()) {
                continue;
            }
            JsonNode evidences = result == null ? null : result.get("evidence");
            if (evidences == null || !evidences.isArray()) {
                continue;
            }
            for (JsonNode evidence : evidences) {
                String sourceId = textValue(evidence, "source_id",
                        textValue(evidence, "sourceId", ""));
                if (!publicCitationIndexes.containsKey(sourceId)) {
                    continue;
                }
                String uri = textValue(evidence, "source_uri",
                        textValue(evidence, "sourceUri", "")).trim();
                String content = compactEvidenceText(textValue(evidence, "content", ""));
                collected.computeIfAbsent(sourceId, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(uri, ignored -> new LinkedHashSet<>());
                if (!content.isBlank()) {
                    collected.get(sourceId).get(uri).add(content);
                }
            }
        }

        Map<String, List<PublicEvidenceVariant>> merged = new LinkedHashMap<>();
        collected.forEach((sourceId, byUri) -> {
            List<String> nonEmptyUris = byUri.keySet().stream()
                    .filter(uri -> !uri.isBlank()).toList();
            if (nonEmptyUris.size() <= 1) {
                String canonicalUri = nonEmptyUris.isEmpty() ? "" : nonEmptyUris.get(0);
                LinkedHashSet<String> contents = new LinkedHashSet<>();
                byUri.values().forEach(contents::addAll);
                merged.put(sourceId, List.of(new PublicEvidenceVariant(
                        canonicalUri, String.join(" ； ", contents))));
                return;
            }

            List<PublicEvidenceVariant> conflicts = new ArrayList<>();
            LinkedHashSet<String> withoutUri = byUri.getOrDefault("", new LinkedHashSet<>());
            for (int index = 0; index < nonEmptyUris.size(); index++) {
                String uri = nonEmptyUris.get(index);
                LinkedHashSet<String> contents = new LinkedHashSet<>(byUri.get(uri));
                if (index == 0) {
                    contents.addAll(withoutUri);
                }
                conflicts.add(new PublicEvidenceVariant(uri, String.join(" ； ", contents)));
            }
            merged.put(sourceId, List.copyOf(conflicts));
        });
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(merged));
    }

    private static String compactEvidenceText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    record PublicEvidenceVariant(String uri, String content) {
        PublicEvidenceVariant {
            uri = uri == null ? "" : uri;
            content = content == null ? "" : content;
        }
    }

    static String productionAnswer(JsonNode finalResponse) {
        if (finalResponse == null || finalResponse.isNull()) {
            return "";
        }
        JsonNode answer = finalResponse.get("answer");
        return answer == null || answer.isNull() ? "" : answer.asText("");
    }

    private static String textValue(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private List<String> workflowScopes(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of("kb_search", "web_search", "calculator");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tool : tools) {
            switch (AgentEvaluationArtifact.normalizeTool(tool)) {
                case "knowledge_search" -> normalized.add("kb_search");
                case "web_search" -> normalized.add("web_search");
                case "calculator" -> normalized.add("calculator");
                default -> {
                    // file/write/unknown tools are intentionally omitted from the workflow schema.
                }
            }
        }
        return normalized.isEmpty() ? List.of("kb_search") : List.copyOf(normalized);
    }

    private Integer taskOrdinal(String taskId) {
        if (taskId == null) return null;
        String digits = taskId.replaceAll("\\D+", "");
        try {
            return digits.isBlank() ? null : Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private JsonNode json(String raw) {
        try {
            return raw == null ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
        } catch (Exception failure) {
            throw new IllegalStateException("workflow harness JSON invalid", failure);
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private int integer(JsonNode node, String field) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, longValue(node, field)));
    }

    private long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null ? 0 : Math.max(0, value.asLong(0));
    }

    private BigDecimal decimal(JsonNode node, String field) {
        try {
            JsonNode value = node == null ? null : node.get(field);
            return value == null ? BigDecimal.ZERO : new BigDecimal(value.asText("0"));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static final class SetLike {
        private SetLike() {
        }

        static boolean success(String status) {
            return "SUCCEEDED".equals(status) || "INSUFFICIENT_EVIDENCE".equals(status);
        }
    }
}

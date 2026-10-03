package com.deepresearch.workflow;

import com.deepresearch.agent.CitationDetail;
import com.deepresearch.mcp.McpKnowledgeTools.McpToolResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reads only Java-owned, run/owner-bound receipts, never the sidecar's safe_result. */
final class WorkflowCitationDetails {
    private WorkflowCitationDetails() {}

    static List<CitationDetail> capture(WorkflowRepository repository, ObjectMapper mapper,
                                       WorkflowRepository.RunRow run, List<String> citations) {
        if (citations.isEmpty()) return List.of();
        var receipts = repository.completedSourceReceipts(run.runId(), run.userId());
        if (receipts.size() > 256) return citations.stream()
                .map(id -> CitationDetail.unavailable(id, "SNAPSHOT_LIMIT")).toList();
        List<CitationDetail> snapshots = new ArrayList<>();
        for (var receipt : receipts) {
            try {
                McpToolResponse response = mapper.readValue(receipt.safeResultJson(), McpToolResponse.class);
                if (!response.success() || !receipt.toolName().equals(response.tool())
                        || response.evidence() == null || response.sourceSnapshots().size() > 10) continue;
                String kind = switch (receipt.toolName()) {
                    case "web_search" -> "WEB_SEARCH_SNAPSHOT";
                    case "kb_search" -> "KNOWLEDGE_CHUNK";
                    default -> "";
                };
                Set<String> ids = response.evidence().stream()
                        .filter(item -> item != null && receipt.toolName().equals(item.sourceType()))
                        .map(item -> item.evidenceId()).collect(java.util.stream.Collectors.toSet());
                for (var detail : response.sourceSnapshots()) {
                    if (detail != null && citations.contains(detail.sourceId()) && ids.contains(detail.sourceId())
                            && kind.equals(detail.kind()) && "AVAILABLE".equals(detail.metadataStatus())
                            && (!"web_search".equals(receipt.toolName()) || detail.sourceId().equals(detail.url()))) {
                        snapshots.add(detail);
                    }
                }
            } catch (JsonProcessingException | IllegalArgumentException malformed) {
                // Historical or malformed metadata does not become a guessed source.
            }
        }
        return CitationDetail.project(citations, snapshots);
    }
}

package com.deepresearch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowHarnessAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesTheProductionAnswerInsteadOfManufacturingCitationMarkers() throws Exception {
        JsonNode finalResponse = objectMapper.readTree("""
                {
                  "answer": "正文没有引用标记。",
                  "citations": ["source-1"],
                  "citationContract": "NONE"
                }
                """);

        assertThat(WorkflowHarnessAdapter.productionAnswer(finalResponse))
                .isEqualTo("正文没有引用标记。")
                .doesNotContain("[来源1]", "证据引用");
    }

    @Test
    void keepsAnExistingIndexedCitationUnchanged() throws Exception {
        JsonNode finalResponse = objectMapper.readTree("""
                {
                  "answer": "正文已有可信引用。[来源1]",
                  "citations": ["source-1"],
                  "citationContract": "INDEXED_V1"
                }
                """);

        assertThat(WorkflowHarnessAdapter.productionAnswer(finalResponse))
                .isEqualTo("正文已有可信引用。[来源1]");
    }

    @Test
    void mapsCompactPublicCitationToItsSourceInsteadOfReceiptOrder() throws Exception {
        JsonNode finalResponse = objectMapper.readTree("""
                {"citations": ["source-6", "source-3"]}
                """);
        JsonNode receipt = objectMapper.readTree("""
                {"evidence": [
                  {"source_id": "source-1", "content": "unreferenced first receipt evidence"},
                  {"source_id": "source-3", "content": "support for the second citation"},
                  {"source_id": "source-6", "content": "support for the first citation"}
                ]}
                """);

        Map<String, Integer> indexes = WorkflowHarnessAdapter.publicCitationIndexes(finalResponse);
        String observation = WorkflowHarnessAdapter.publicCitationObservation(receipt, indexes);

        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("source-6", 1);
        expected.put("source-3", 2);
        assertThat(indexes).containsExactlyEntriesOf(expected);
        assertThat(observation)
                .contains("[来源1] source-6", "support for the first citation")
                .contains("[来源2] source-3", "support for the second citation")
                .doesNotContain("source-1", "unreferenced first receipt evidence");
    }

    @Test
    void deduplicatesTheSamePublicSourceAcrossWorkerReceipts() throws Exception {
        JsonNode finalResponse = objectMapper.readTree("""
                {"citations": ["source-a", "source-b"]}
                """);
        JsonNode firstReceipt = objectMapper.readTree("""
                {"evidence": [
                  {"source_id": "source-a", "content": "first source support"},
                  {"source_id": "source-b", "content": "second source support"}
                ]}
                """);
        JsonNode repeatedReceipt = objectMapper.readTree("""
                {"evidence": [
                  {"source_id": "source-a", "content": "same source retrieved by another worker"}
                ]}
                """);

        Map<String, Integer> indexes = WorkflowHarnessAdapter.publicCitationIndexes(finalResponse);
        LinkedHashSet<String> captured = new LinkedHashSet<>();

        String first = WorkflowHarnessAdapter.publicCitationObservation(
                firstReceipt, indexes, captured);
        String repeated = WorkflowHarnessAdapter.publicCitationObservation(
                repeatedReceipt, indexes, captured);

        assertThat(first).contains("[来源1] source-a", "[来源2] source-b");
        assertThat(repeated).isBlank();
        assertThat(captured).containsExactly("source-a", "source-b");
    }

    @Test
    void mergesComplementarySnippetsForTheSamePublicSourceAndUri() throws Exception {
        JsonNode finalResponse = objectMapper.readTree("""
                {"citations": ["source-a"]}
                """);
        JsonNode shortReceipt = objectMapper.readTree("""
                {"evidence": [{
                  "source_id": "source-a",
                  "source_uri": "kb://project/source-a",
                  "content": "Python workflow has no host port"
                }]}
                """);
        JsonNode completeReceipt = objectMapper.readTree("""
                {"evidence": [{
                  "source_id": "source-a",
                  "source_uri": "kb://project/source-a",
                  "content": "A local unmapped port does not prove a cloud NetworkPolicy"
                }]}
                """);

        Map<String, Integer> indexes = WorkflowHarnessAdapter.publicCitationIndexes(finalResponse);
        Map<String, List<WorkflowHarnessAdapter.PublicEvidenceVariant>> aggregate =
                WorkflowHarnessAdapter.aggregatePublicEvidence(
                        List.of(shortReceipt, completeReceipt), indexes);
        String observation = WorkflowHarnessAdapter.publicCitationObservation(
                shortReceipt, indexes, new LinkedHashSet<>(), aggregate);

        assertThat(aggregate.get("source-a")).hasSize(1);
        assertThat(observation)
                .containsOnlyOnce("[来源1] source-a")
                .contains("Python workflow has no host port")
                .contains("A local unmapped port does not prove a cloud NetworkPolicy");

        try (AgentEvaluationArtifact.Scope scope = AgentEvaluationArtifact.Scope.open(
                List.of("kb_search"), List.of("file_read"))) {
            scope.artifact().captureIndexed("kb_search", "OK", "fp", observation, 1);
            assertThat(scope.artifact().evidences()).singleElement()
                    .satisfies(evidence -> assertThat(evidence.supportText())
                            .contains("no host port", "NetworkPolicy"));
        }
    }

    @Test
    void preservesConflictingUrisAsAmbiguousEvidenceInsteadOfMergingThem() throws Exception {
        JsonNode finalResponse = objectMapper.readTree("""
                {"citations": ["source-a"]}
                """);
        JsonNode firstReceipt = objectMapper.readTree("""
                {"evidence": [{
                  "source_id": "source-a",
                  "source_uri": "kb://project/version-1",
                  "content": "version one"
                }]}
                """);
        JsonNode conflictingReceipt = objectMapper.readTree("""
                {"evidence": [{
                  "source_id": "source-a",
                  "source_uri": "kb://project/version-2",
                  "content": "version two"
                }]}
                """);

        Map<String, Integer> indexes = WorkflowHarnessAdapter.publicCitationIndexes(finalResponse);
        Map<String, List<WorkflowHarnessAdapter.PublicEvidenceVariant>> aggregate =
                WorkflowHarnessAdapter.aggregatePublicEvidence(
                        List.of(firstReceipt, conflictingReceipt), indexes);
        String observation = WorkflowHarnessAdapter.publicCitationObservation(
                firstReceipt, indexes, new LinkedHashSet<>(), aggregate);

        assertThat(aggregate.get("source-a")).hasSize(2);
        assertThat(observation)
                .contains("URL: kb://project/version-1", "URL: kb://project/version-2");
        assertThat(count(observation, "[来源1] source-a")).isEqualTo(2);

        try (AgentEvaluationArtifact.Scope scope = AgentEvaluationArtifact.Scope.open(
                List.of("kb_search"), List.of("file_read"))) {
            scope.artifact().captureIndexed("kb_search", "OK", "fp", observation, 1);
            assertThat(scope.artifact().evidences())
                    .extracting(AgentEvaluationArtifact.EvidenceRef::globalIndex)
                    .containsExactly(1, 1);
        }
    }

    @Test
    void failedReceiptCannotPolluteOrClaimEvidenceFromALaterSuccessfulReceipt() throws Exception {
        JsonNode finalResponse = objectMapper.readTree("""
                {"citations": ["source-a"]}
                """);
        JsonNode failedReceipt = objectMapper.readTree("""
                {"error_code": "UPSTREAM_FAILED", "evidence": [{
                  "source_id": "source-a",
                  "source_uri": "kb://project/source-a",
                  "content": "untrusted partial failure content"
                }]}
                """);
        JsonNode successfulReceipt = objectMapper.readTree("""
                {"evidence": [{
                  "source_id": "source-a",
                  "source_uri": "kb://project/source-a",
                  "content": "authoritative successful evidence"
                }]}
                """);

        Map<String, Integer> indexes = WorkflowHarnessAdapter.publicCitationIndexes(finalResponse);
        Map<String, List<WorkflowHarnessAdapter.PublicEvidenceVariant>> aggregate =
                WorkflowHarnessAdapter.aggregatePublicEvidence(
                        List.of(failedReceipt, successfulReceipt), indexes);
        LinkedHashSet<String> captured = new LinkedHashSet<>();

        // captureReceipts intentionally skips this call for failed results, so it cannot
        // reserve source-a before the successful receipt is processed.
        String successful = WorkflowHarnessAdapter.publicCitationObservation(
                successfulReceipt, indexes, captured, aggregate);

        assertThat(successful)
                .contains("authoritative successful evidence")
                .doesNotContain("untrusted partial failure content");
        assertThat(captured).containsExactly("source-a");
    }

    @Test
    void indexedArtifactKeepsPublishedMarker() {
        try (AgentEvaluationArtifact.Scope scope = AgentEvaluationArtifact.Scope.open(
                java.util.List.of("kb_search"), java.util.List.of("file_read"))) {
            AgentEvaluationArtifact artifact = scope.artifact();
            artifact.captureIndexed("kb_search", "OK", "fp-1",
                    "[来源2] source-b\n证据: second public source", 1);
            artifact.captureIndexed("kb_search", "OK", "fp-2",
                    "[来源1] source-a\n证据: first public source", 2);

            assertThat(artifact.evidences())
                    .extracting(AgentEvaluationArtifact.EvidenceRef::globalIndex)
                    .containsExactly(2, 1);
        }
    }

    private int count(String value, String needle) {
        return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}

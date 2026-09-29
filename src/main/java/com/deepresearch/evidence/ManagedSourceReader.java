package com.deepresearch.evidence;

import com.deepresearch.service.RagflowClient;
import com.deepresearch.service.RagflowDocumentRegistry;
import java.time.Instant;

/** Separates managed internal RAGFlow from arbitrary public web transport. */
public final class ManagedSourceReader implements SourceReader {
    private final SafeWebReader web;
    private final RagflowClient ragflow;
    private final RagflowDocumentRegistry registry;
    private final EvidenceAuthority authority;
    public ManagedSourceReader(SafeWebReader web, RagflowClient ragflow, RagflowDocumentRegistry registry, EvidenceAuthority authority) {
        this.web = web; this.ragflow = ragflow; this.registry = registry; this.authority = authority;
    }
    @Override public Document read(EvidenceAuthority.Grant grant, EvidenceAuthority.Candidate candidate) {
        if ("web".equals(candidate.kind())) return web.read(grant, candidate);
        if ("controlled_test".equals(candidate.kind())) {
            var observation = authority.controlledObservation(grant, candidate);
            String text = EvidenceJson.text(observation.text(), 10000);
            return new Document(text, candidate.title(), EvidenceJson.object("kind", "test_result", "artifact_id", EvidenceJson.id(observation.artifactId())),
                    "test_observation", observation.observedAt(), EvidenceJson.sha(text), false, true);
        }
        if (!"knowledge".equals(candidate.kind()) || !ragflow.datasets().contains(candidate.datasetId())
                || !registry.active(candidate.datasetId(), candidate.documentId())) throw EvidenceException.denied();
        var chunk = ragflow.chunk(candidate.datasetId(), candidate.documentId(), candidate.chunkId());
        if (!candidate.chunkId().equals(chunk.path("id").asText()) || !candidate.documentId().equals(chunk.path("doc_id").asText())
                || chunk.hasNonNull("kb_id") && !candidate.datasetId().equals(chunk.path("kb_id").asText())
                || chunk.hasNonNull("dataset_id") && !candidate.datasetId().equals(chunk.path("dataset_id").asText()))
            throw new EvidenceException("SOURCE_KB_IDENTITY_CHANGED");
        // RAGFlow's original-chunk endpoint returns content_with_weight, while
        // retrieval previews and older chunk responses use content. Never replace
        // the fetched original with the search preview or choose conflicting aliases.
        var content = chunk.get("content");
        var weighted = chunk.get("content_with_weight");
        if (content != null && !content.isNull() && weighted != null && !weighted.isNull() && !content.equals(weighted))
            throw new EvidenceException("SOURCE_KB_ORIGINAL_AMBIGUOUS");
        var original = content != null && !content.isNull() ? content : weighted;
        String text = EvidenceJson.text(original != null && original.isTextual() ? original.textValue() : null, 10000);
        if (!registry.active(candidate.datasetId(), candidate.documentId())) throw EvidenceException.denied();
        return new Document(text, candidate.title(), EvidenceJson.object("kind", "knowledge_chunk", "dataset_id", candidate.datasetId(),
                "document_id", candidate.documentId(), "chunk_id", candidate.chunkId()), "document_chunk", Instant.now(), EvidenceJson.sha(text), false, false);
    }
}

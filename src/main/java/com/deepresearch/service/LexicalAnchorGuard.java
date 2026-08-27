package com.deepresearch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Prevents a literal, high-information BM25 hit from disappearing at candidate or final TopK cuts.
 * It does not manufacture candidates: an anchor is selected only from keyword hits whose text
 * contains the requested identifier (with the documented key/value normalization).
 */
@Component
class LexicalAnchorGuard {

    private static final Logger log = LoggerFactory.getLogger(LexicalAnchorGuard.class);

    Optional<Anchor> select(String question, List<Document> keywordHits) {
        List<ExactIdentifierSupport.Identifier> identifiers = ExactIdentifierSupport.extractAnchors(question);
        if (identifiers.isEmpty() || keywordHits == null || keywordHits.isEmpty()) {
            return Optional.empty();
        }
        List<Anchor> anchors = new ArrayList<>();
        for (int index = 0; index < keywordHits.size(); index++) {
            Document document = keywordHits.get(index);
            List<ExactIdentifierSupport.Identifier> matches = ExactIdentifierSupport.matchingAnchors(
                    identifiers, searchableText(document));
            if (!matches.isEmpty()) {
                ExactIdentifierSupport.Identifier strongest = matches.stream()
                        .max(identifierComparator())
                        .orElseThrow();
                anchors.add(new Anchor(document, strongest, index + 1));
            }
        }
        return anchors.stream()
                .max(Comparator.comparingInt(Anchor::strength)
                        .thenComparingInt(anchor -> -anchor.keywordRank()));
    }

    List<HybridChunk> ensureCandidate(List<HybridChunk> candidates,
                                      int candidateLimit,
                                      Optional<Anchor> selected) {
        if (selected.isEmpty() || candidateLimit <= 0) {
            return candidates == null ? List.of() : candidates;
        }
        if (candidates == null) {
            candidates = List.of();
        }
        Anchor anchor = selected.get();
        String anchorKey = HybridDocumentSupport.stableKey(anchor.document());
        if (contains(candidates, anchorKey)) {
            return candidates;
        }
        List<HybridChunk> protectedCandidates = new ArrayList<>(candidates);
        HybridChunk protectedChunk = new HybridChunk(anchor.document());
        protectedChunk.add(HybridChunk.Route.KEYWORD, anchor.keywordRank(), 0.0);
        if (protectedCandidates.size() < candidateLimit) {
            protectedCandidates.add(protectedChunk);
        } else {
            protectedCandidates.set(protectedCandidates.size() - 1, protectedChunk);
        }
        log.debug("lexical_anchor_guard action=candidate_promoted kind={} keyword_rank={} chunk_key={}",
                anchor.identifier().kind(), anchor.keywordRank(), HybridDocumentSupport.chunkKey(anchor.document()));
        return List.copyOf(protectedCandidates);
    }

    List<HybridChunk> ensureTopK(List<HybridChunk> ranked, int topK, Optional<Anchor> selected) {
        if (ranked == null || ranked.isEmpty() || topK <= 0) {
            return List.of();
        }
        int size = Math.min(topK, ranked.size());
        List<HybridChunk> direct = new ArrayList<>(ranked.subList(0, size));
        if (selected.isEmpty()) {
            return List.copyOf(direct);
        }
        Anchor anchor = selected.get();
        String anchorKey = HybridDocumentSupport.stableKey(anchor.document());
        if (contains(direct, anchorKey)) {
            return List.copyOf(direct);
        }
        HybridChunk anchored = ranked.stream()
                .filter(chunk -> anchorKey.equals(HybridDocumentSupport.stableKey(chunk.document())))
                .findFirst()
                .orElseGet(() -> {
                    HybridChunk chunk = new HybridChunk(anchor.document());
                    chunk.add(HybridChunk.Route.KEYWORD, anchor.keywordRank(), 0.0);
                    return chunk;
                });
        direct.set(direct.size() - 1, anchored);
        log.debug("lexical_anchor_guard action=final_topk_promoted kind={} keyword_rank={} chunk_key={}",
                anchor.identifier().kind(), anchor.keywordRank(), HybridDocumentSupport.chunkKey(anchor.document()));
        return List.copyOf(direct);
    }

    private boolean contains(List<HybridChunk> chunks, String stableKey) {
        return chunks.stream().anyMatch(chunk -> stableKey.equals(HybridDocumentSupport.stableKey(chunk.document())));
    }

    private String searchableText(Document document) {
        return HybridDocumentSupport.title(document) + "\n"
                + HybridDocumentSupport.stringMeta(document, "sectionPath") + "\n"
                + HybridDocumentSupport.cleanText(document);
    }

    private Comparator<ExactIdentifierSupport.Identifier> identifierComparator() {
        return Comparator.comparingInt((ExactIdentifierSupport.Identifier identifier) -> identifier.kind().strength())
                .thenComparingInt(identifier -> identifier.canonical().length());
    }

    record Anchor(Document document,
                  ExactIdentifierSupport.Identifier identifier,
                  int keywordRank) {
        int strength() {
            return identifier.kind().strength() * 1_000 + identifier.canonical().length();
        }
    }
}

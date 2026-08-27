package com.deepresearch.service;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/** 融合流程中的内部结果，保存原始文档、两路排名、RRF 分数与扩展来源。 */
final class HybridChunk {

    enum Route { VECTOR, KEYWORD }

    private final Document document;
    private double rrfScore;
    private Double rerankScore;
    private Integer vectorRank;
    private Integer keywordRank;
    private boolean expanded;
    private String expandedFromChunkKey = "";

    HybridChunk(Document document) {
        this.document = document;
    }

    void add(Route route, int rank, double score) {
        rrfScore += score;
        if (route == Route.VECTOR) {
            vectorRank = rank;
        } else {
            keywordRank = rank;
        }
    }

    void setRerankScore(double score) {
        rerankScore = Math.round(score * 1_000_000.0) / 1_000_000.0;
    }

    void markExpanded(String sourceChunkKey) {
        expanded = true;
        expandedFromChunkKey = sourceChunkKey == null ? "" : sourceChunkKey;
    }

    Document document() { return document; }
    double rrfScore() { return rrfScore; }
    Double rerankScore() { return rerankScore; }
    Integer vectorRank() { return vectorRank; }
    Integer keywordRank() { return keywordRank; }
    boolean expanded() { return expanded; }
    String expandedFromChunkKey() { return expandedFromChunkKey; }
    String title() { return HybridDocumentSupport.title(document); }

    String routeSummary() {
        List<String> routes = new ArrayList<>();
        if (vectorRank != null) {
            routes.add("vector#" + vectorRank);
        }
        if (keywordRank != null) {
            routes.add("keyword#" + keywordRank);
        }
        return String.join(", ", routes);
    }
}

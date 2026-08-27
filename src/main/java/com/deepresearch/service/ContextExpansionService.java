package com.deepresearch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为直接命中的 chunk 查询同文档相邻片段，以补足跨 chunk 上下文。
 * 数据库无匹配时保留原命中；metadata 无法解析时明确失败，避免静默拼错文档。
 */
@Service
class ContextExpansionService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int siblingWindow;
    private final int maxContextChunks;

    ContextExpansionService(JdbcTemplate jdbcTemplate,
                            ObjectMapper objectMapper,
                            @Value("${deepresearch.context-expansion.enabled:true}") boolean enabled,
                            @Value("${deepresearch.context-expansion.sibling-window:1}") int siblingWindow,
                            @Value("${deepresearch.context-expansion.max-context-chunks:12}") int maxContextChunks) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.siblingWindow = Math.max(0, siblingWindow);
        this.maxContextChunks = Math.max(1, maxContextChunks);
    }

    List<HybridChunk> expand(List<HybridChunk> directChunks) {
        if (!enabled || siblingWindow <= 0 || directChunks.isEmpty()) {
            return directChunks.stream().limit(maxContextChunks).toList();
        }
        Map<String, HybridChunk> expanded = new LinkedHashMap<>();
        for (HybridChunk direct : directChunks) {
            Integer chunkIndex = HybridDocumentSupport.intMeta(direct.document(), "chunkIndex");
            String docId = HybridDocumentSupport.stringMeta(direct.document(), "docId");
            String filename = HybridDocumentSupport.stringMeta(direct.document(), "filename");
            if (chunkIndex == null || (docId.isBlank() && filename.isBlank())) {
                expanded.putIfAbsent(HybridDocumentSupport.stableKey(direct.document()), direct);
                continue;
            }
            boolean found = false;
            for (Document sibling : siblings(docId, filename,
                    Math.max(0, chunkIndex - siblingWindow), chunkIndex + siblingWindow)) {
                found = true;
                String key = HybridDocumentSupport.stableKey(sibling);
                if (expanded.containsKey(key)) {
                    continue;
                }
                HybridChunk chunk = key.equals(HybridDocumentSupport.stableKey(direct.document()))
                        ? direct : new HybridChunk(sibling);
                if (chunk != direct) {
                    chunk.markExpanded(HybridDocumentSupport.chunkKey(direct.document()));
                }
                expanded.put(key, chunk);
            }
            if (!found) {
                expanded.putIfAbsent(HybridDocumentSupport.stableKey(direct.document()), direct);
            }
        }
        return expanded.values().stream().limit(maxContextChunks).toList();
    }

    private List<Document> siblings(String docId, String filename, int from, int to) {
        String filterColumn = !docId.isBlank() ? "docId" : "filename";
        String filterValue = !docId.isBlank() ? docId : filename;
        return jdbcTemplate.query("""
                        SELECT id::text AS id, content, metadata::text AS metadata
                        FROM vector_store
                        WHERE metadata ->> ? = ?
                          AND (metadata ->> 'chunkIndex') ~ '^[0-9]+$'
                          AND (metadata ->> 'chunkIndex')::int BETWEEN ? AND ?
                        ORDER BY (metadata ->> 'chunkIndex')::int
                        """,
                (rs, rowNum) -> {
                    try {
                        Map<String, Object> metadata = objectMapper.readValue(
                                rs.getString("metadata"), new TypeReference<>() {
                                });
                        return new Document(rs.getString("id"), rs.getString("content"), metadata);
                    } catch (Exception exception) {
                        throw new IllegalStateException("解析 sibling chunk metadata 失败", exception);
                    }
                },
                filterColumn, filterValue, from, to);
    }
}

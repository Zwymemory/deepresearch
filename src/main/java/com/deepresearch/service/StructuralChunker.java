package com.deepresearch.service;

import com.deepresearch.model.KnowledgeChunk;
import com.deepresearch.model.ParsedDocument;
import com.deepresearch.model.ParsedSection;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class StructuralChunker {

    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    private final int chunkSize;
    private final int chunkOverlap;
    private final int minChunkChars;

    public StructuralChunker(@Value("${deepresearch.ingestion.chunk-size:800}") int chunkSize,
                             @Value("${deepresearch.ingestion.chunk-overlap:120}") int chunkOverlap,
                             @Value("${deepresearch.ingestion.min-chunk-chars:80}") int minChunkChars) {
        this.chunkSize = Math.max(100, chunkSize);
        this.chunkOverlap = Math.max(0, Math.min(chunkOverlap, this.chunkSize / 2));
        this.minChunkChars = Math.max(1, minChunkChars);
    }

    public List<KnowledgeChunk> chunk(String docId, ParsedDocument document) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int index = 0;
        for (ParsedSection section : document.sections()) {
            for (String piece : splitSection(section.text())) {
                String content = buildSearchableChunk(document.title(), section, piece);
                if (content.length() < minChunkChars) {
                    continue;
                }
                String chunkHash = sha256(content);
                chunks.add(new KnowledgeChunk(
                        stableChunkId(docId, index),
                        content,
                        section.sectionPath(),
                        section.pageNumber(),
                        index,
                        chunkHash));
                index++;
            }
        }
        return chunks;
    }

    private List<String> splitSection(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        IntArrayList encoded = encoding.encode(normalized);
        if (encoded.size() <= chunkSize) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < encoded.size()) {
            int end = Math.min(start + chunkSize, encoded.size());
            String chunk = decode(encoded, start, end).trim();
            int softEnd = softBoundary(chunk);
            if (softEnd > minChunkChars && end < encoded.size()) {
                chunk = chunk.substring(0, softEnd).trim();
                end = start + encoding.encode(chunk).size();
            }
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end >= encoded.size()) {
                break;
            }
            start = Math.max(end - chunkOverlap, start + 1);
        }
        return chunks;
    }

    private String decode(IntArrayList tokens, int start, int end) {
        IntArrayList slice = new IntArrayList(end - start);
        for (int i = start; i < end; i++) {
            slice.add(tokens.get(i));
        }
        return encoding.decode(slice);
    }

    private int softBoundary(String chunk) {
        int newline = chunk.lastIndexOf('\n');
        int cnPeriod = chunk.lastIndexOf('。');
        int period = chunk.lastIndexOf('.');
        int question = Math.max(chunk.lastIndexOf('?'), chunk.lastIndexOf('？'));
        int exclamation = Math.max(chunk.lastIndexOf('!'), chunk.lastIndexOf('！'));
        int boundary = Math.max(Math.max(newline, cnPeriod), Math.max(Math.max(period, question), exclamation));
        return boundary < 0 ? -1 : boundary + 1;
    }

    private String buildSearchableChunk(String title, ParsedSection section, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("文档：").append(title).append('\n');
        if (section.sectionPath() != null && !section.sectionPath().isBlank()) {
            sb.append("章节：").append(section.sectionPath()).append('\n');
        }
        if (section.pageNumber() != null) {
            sb.append("页码：").append(section.pageNumber()).append('\n');
        }
        sb.append("正文：").append(text.trim());
        return sb.toString();
    }

    private String stableChunkId(String docId, int chunkIndex) {
        return UUID.nameUUIDFromBytes((docId + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").trim();
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}

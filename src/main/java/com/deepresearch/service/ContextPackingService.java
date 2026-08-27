package com.deepresearch.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W7：把扩展后的上下文压缩成更适合 LLM 使用的证据包。
 *
 * 第一版采用可解释的规则式 evidence packing，不额外调用 LLM：
 * - 直接命中的 chunk 优先于 sibling expansion chunk。
 * - rerankScore / rrfScore 越高越优先。
 * - 从 chunk 中抽取覆盖 query 关键词、编号、配置项的句子。
 * - 控制单条证据和总上下文字符预算。
 */
@Service
public class ContextPackingService {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*\\d+[A-Za-z0-9_.-]*|[A-Za-z]+-[0-9A-Za-z-]+|[a-zA-Z]+\\.[a-zA-Z0-9_.-]+");
    private static final Pattern LATIN_TERM_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{2,}");
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("(?<=[。！？!?；;\\n])");

    private final boolean enabled;
    private final int maxEvidenceChars;
    private final int maxTotalChars;
    private final int maxEvidencePerChunk;

    public ContextPackingService(@Value("${deepresearch.context-packing.enabled:true}") boolean enabled,
                                 @Value("${deepresearch.context-packing.max-evidence-chars:700}") int maxEvidenceChars,
                                 @Value("${deepresearch.context-packing.max-total-chars:6000}") int maxTotalChars,
                                 @Value("${deepresearch.context-packing.max-evidence-per-chunk:3}") int maxEvidencePerChunk) {
        this.enabled = enabled;
        this.maxEvidenceChars = Math.max(120, maxEvidenceChars);
        this.maxTotalChars = Math.max(this.maxEvidenceChars, maxTotalChars);
        this.maxEvidencePerChunk = Math.max(1, maxEvidencePerChunk);
    }

    public PackedContext pack(String query, List<ChunkInput> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new PackedContext(List.of(), new PackingDiagnostics(enabled, 0, 0, 0, 0, 0.0, "empty_context"));
        }
        if (!enabled) {
            List<PackedEvidence> evidences = new ArrayList<>();
            int totalChars = 0;
            for (ChunkInput chunk : chunks) {
                String text = limit(clean(chunk.text()), maxEvidenceChars);
                totalChars += text.length();
                evidences.add(toEvidence(chunk, text, "disabled"));
            }
            return new PackedContext(evidences, diagnostics(chunks, evidences, totalChars, "disabled"));
        }

        QueryTerms terms = queryTerms(query);
        List<ScoredChunk> scoredChunks = chunks.stream()
                .map(chunk -> new ScoredChunk(chunk, chunkPriority(chunk)))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .toList();

        List<PackedEvidence> evidences = new ArrayList<>();
        int totalChars = 0;
        for (ScoredChunk scored : scoredChunks) {
            ChunkInput chunk = scored.chunk();
            String evidence = extractEvidence(chunk.text(), terms);
            if (evidence.isBlank()) {
                evidence = fallbackEvidence(chunk.text());
            }
            evidence = limit(evidence, maxEvidenceChars);
            if (evidence.isBlank()) {
                continue;
            }
            if (totalChars + evidence.length() > maxTotalChars && !evidences.isEmpty()) {
                continue;
            }
            if (totalChars + evidence.length() > maxTotalChars) {
                evidence = limit(evidence, maxTotalChars - totalChars);
            }
            if (evidence.isBlank()) {
                continue;
            }
            totalChars += evidence.length();
            evidences.add(toEvidence(chunk, evidence, "query_sentence_extract"));
        }

        String reason = evidences.size() == chunks.size() ? "packed_without_drop" : "packed_with_budget";
        return new PackedContext(evidences, diagnostics(chunks, evidences, totalChars, reason));
    }

    private PackingDiagnostics diagnostics(List<ChunkInput> chunks, List<PackedEvidence> evidences, int totalChars, String reason) {
        int originalChars = chunks.stream().mapToInt(chunk -> clean(chunk.text()).length()).sum();
        double rawRatio = originalChars == 0 ? 0.0 : (double) totalChars / originalChars;
        double ratio = Math.round(Math.min(1.0, rawRatio) * 10_000.0) / 10_000.0;
        return new PackingDiagnostics(
                enabled,
                chunks.size(),
                evidences.size(),
                Math.max(0, chunks.size() - evidences.size()),
                totalChars,
                ratio,
                reason);
    }

    private PackedEvidence toEvidence(ChunkInput chunk, String evidenceText, String reason) {
        return new PackedEvidence(
                chunk.index(),
                chunk.title(),
                chunk.docId(),
                chunk.chunkId(),
                chunk.filename(),
                chunk.sectionPath(),
                chunk.chunkIndex(),
                chunk.pageNumber(),
                chunk.chunkKey(),
                chunk.route(),
                chunk.vectorRank(),
                chunk.keywordRank(),
                chunk.rrfScore(),
                chunk.rerankScore(),
                chunk.expanded(),
                chunk.expandedFromChunkKey(),
                evidenceText,
                evidenceText.length(),
                reason);
    }

    private double chunkPriority(ChunkInput chunk) {
        double score = 0.0;
        if (!chunk.expanded()) {
            score += 4.0;
        }
        if (chunk.rerankScore() != null) {
            score += chunk.rerankScore();
        }
        if (chunk.rrfScore() != null) {
            score += chunk.rrfScore() * 100.0;
        }
        if (chunk.vectorRank() != null) {
            score += 1.0 / chunk.vectorRank();
        }
        if (chunk.keywordRank() != null) {
            score += 1.0 / chunk.keywordRank();
        }
        score += 1.0 / Math.max(1, chunk.index());
        return score;
    }

    private String extractEvidence(String text, QueryTerms terms) {
        String cleanText = clean(text);
        if (cleanText.isBlank()) {
            return "";
        }
        List<String> sentences = splitSentences(cleanText);
        if (sentences.isEmpty()) {
            return "";
        }
        List<ScoredSentence> scored = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i).trim();
            double score = sentenceScore(sentence, terms);
            if (score > 0.0) {
                scored.add(new ScoredSentence(i, sentence, score));
            }
        }
        if (scored.isEmpty()) {
            return "";
        }
        List<ScoredSentence> selected = scored.stream()
                .sorted(Comparator.comparingDouble(ScoredSentence::score).reversed())
                .limit(maxEvidencePerChunk)
                .sorted(Comparator.comparingInt(ScoredSentence::index))
                .toList();

        StringBuilder sb = new StringBuilder();
        for (ScoredSentence sentence : selected) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(sentence.text());
        }
        return sb.toString().trim();
    }

    private double sentenceScore(String sentence, QueryTerms terms) {
        String normalized = normalize(sentence);
        double score = 0.0;
        for (String identifier : terms.identifiers()) {
            if (normalized.contains(identifier)) {
                score += 4.0;
            }
        }
        for (String term : terms.terms()) {
            if (normalized.contains(term)) {
                score += 1.0;
            }
        }
        if (sentence.length() >= 20 && sentence.length() <= maxEvidenceChars) {
            score += 0.2;
        }
        return score;
    }

    private QueryTerms queryTerms(String query) {
        String normalized = normalize(query);
        Set<String> identifiers = new LinkedHashSet<>();
        Matcher identifierMatcher = IDENTIFIER_PATTERN.matcher(query == null ? "" : query);
        while (identifierMatcher.find()) {
            identifiers.add(normalize(identifierMatcher.group()));
        }

        Set<String> terms = new LinkedHashSet<>();
        Matcher latinMatcher = LATIN_TERM_PATTERN.matcher(normalized);
        while (latinMatcher.find()) {
            String term = latinMatcher.group().toLowerCase(Locale.ROOT);
            if (!stopWords().contains(term)) {
                terms.add(term);
            }
        }
        String cjk = normalized.replaceAll("[^\\p{IsHan}]", "");
        for (int i = 0; i + 2 <= cjk.length(); i += 2) {
            String term = cjk.substring(i, Math.min(cjk.length(), i + 2));
            if (term.length() >= 2 && !stopWords().contains(term)) {
                terms.add(term);
            }
        }
        terms.addAll(identifiers);
        return new QueryTerms(List.copyOf(identifiers), List.copyOf(terms));
    }

    private Set<String> stopWords() {
        return Set.of("什么", "怎么", "如何", "这个", "那个", "它的", "the", "and", "for", "with", "what", "how", "does", "is");
    }

    private List<String> splitSentences(String text) {
        String[] raw = SENTENCE_SPLIT_PATTERN.split(text);
        List<String> sentences = new ArrayList<>();
        for (String item : raw) {
            String sentence = item.trim();
            if (sentence.length() >= 8) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty() && !text.isBlank()) {
            sentences.add(text.trim());
        }
        return sentences;
    }

    private String fallbackEvidence(String text) {
        return limit(clean(text), maxEvidenceChars);
    }

    private String clean(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").trim();
        int bodyStart = normalized.indexOf("正文：");
        if (bodyStart >= 0) {
            return normalized.substring(bodyStart + "正文：".length()).trim();
        }
        return normalized;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String limit(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
    }

    public record ChunkInput(
            int index,
            String title,
            String docId,
            String chunkId,
            String filename,
            String sectionPath,
            Integer chunkIndex,
            Integer pageNumber,
            String chunkKey,
            String route,
            Integer vectorRank,
            Integer keywordRank,
            Double rrfScore,
            Double rerankScore,
            boolean expanded,
            String expandedFromChunkKey,
            String text
    ) {
    }

    public record PackedContext(List<PackedEvidence> evidences, PackingDiagnostics diagnostics) {
    }

    public record PackedEvidence(
            int index,
            String title,
            String docId,
            String chunkId,
            String filename,
            String sectionPath,
            Integer chunkIndex,
            Integer pageNumber,
            String chunkKey,
            String route,
            Integer vectorRank,
            Integer keywordRank,
            Double rrfScore,
            Double rerankScore,
            boolean expanded,
            String expandedFromChunkKey,
            String evidenceText,
            int evidenceChars,
            String packReason
    ) {
    }

    public record PackingDiagnostics(
            boolean enabled,
            int inputChunks,
            int outputEvidences,
            int droppedChunks,
            int totalEvidenceChars,
            double compressionRatio,
            String reason
    ) {
    }

    private record QueryTerms(List<String> identifiers, List<String> terms) {
    }

    private record ScoredChunk(ChunkInput chunk, double score) {
    }

    private record ScoredSentence(int index, String text, double score) {
    }
}

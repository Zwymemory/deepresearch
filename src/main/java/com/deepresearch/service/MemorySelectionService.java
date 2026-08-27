package com.deepresearch.service;

import com.deepresearch.web.dto.AgentMemoryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从当前用户的长期记忆中选择与问题相关的有限集合。
 * 使用确定性关键词分数；没有正相关项时回退最近记忆，并在结果中明确 reason。
 */
@Service
class MemorySelectionService {

    private final AgentMemoryRepository memoryRepository;
    private final int limit;

    MemorySelectionService(AgentMemoryRepository memoryRepository,
                           @Value("${deepresearch.memory.relevant-memory-limit:5}") int limit) {
        this.memoryRepository = memoryRepository;
        this.limit = Math.max(1, limit);
    }

    Selection select(String userId, String question) {
        List<AgentMemoryResponse> all = memoryRepository.list(userId, 100);
        if (all.isEmpty()) {
            return new Selection(List.of(), 0, "no_memory");
        }
        List<ScoredMemory> scored = all.stream()
                .map(memory -> new ScoredMemory(memory, score(question, memory)))
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed()
                        .thenComparing(item -> item.memory().updatedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<ScoredMemory> selected = scored.stream().filter(item -> item.score() > 0.25).limit(limit).toList();
        String reason = "keyword_relevance";
        if (selected.isEmpty()) {
            selected = scored.stream().limit(Math.min(2, limit)).toList();
            reason = "fallback_recent_memory";
        }
        List<String> rows = new ArrayList<>();
        for (ScoredMemory item : selected) {
            AgentMemoryResponse memory = item.memory();
            rows.add("- [" + memory.memoryType() + "] " + memory.content()
                    + " (confidence=" + memory.confidence()
                    + ", source=" + memory.source()
                    + ", relevance=" + String.format(Locale.ROOT, "%.2f", item.score()) + ")");
            memoryRepository.markUsed(memory.memoryId());
        }
        return new Selection(rows, all.size(), reason);
    }

    private double score(String question, AgentMemoryResponse memory) {
        String query = normalize(question);
        String text = normalize(memory.memoryType() + " " + memory.content());
        if (query.isBlank() || text.isBlank()) {
            return memory.confidence() * 0.1;
        }
        double score = 0.0;
        for (String term : terms(query)) {
            if (term.length() >= 2 && text.contains(term)) {
                score += term.length() >= 4 ? 2.0 : 1.0;
            }
        }
        return score + (text.contains(query) ? 4.0 : 0.0) + memory.confidence() * 0.2;
    }

    private Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : text.split("[^\\p{IsHan}a-z0-9_\\-]+")) {
            if (term.trim().length() >= 2) {
                terms.add(term.trim());
            }
        }
        return terms;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    record Selection(List<String> rows, int totalCount, String reason) {
    }

    private record ScoredMemory(AgentMemoryResponse memory, double score) {
    }
}

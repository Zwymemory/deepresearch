package com.deepresearch.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Requires both reranker support and a nonnumeric connection to the question. */
@Component
final class LegacyRelevanceGate {
    private static final Pattern TERMS = Pattern.compile("[a-z][a-z0-9]*|[0-9]+|[\\p{IsHan}]+");
    private static final Set<String> STOPWORDS = Set.of(
            "what", "which", "who", "where", "when", "why", "how", "is", "are", "was", "were",
            "the", "a", "an", "of", "to", "for", "from", "in", "on", "at", "by", "with", "and", "or",
            "does", "do", "can", "could", "should", "would", "must", "this", "that", "its", "it",
            "be", "before", "after", "through", "about", "there", "only", "give", "tell", "please",
            "me", "exact");

    private final double minimumScore;
    private final double lexicalRescueScore;
    private final double lexicalRescueCoverage;

    LegacyRelevanceGate(
            @Value("${deepresearch.rerank.minimum-evidence-score:-5.0}") double minimumScore,
            @Value("${deepresearch.rerank.lexical-rescue-score:-6.5}") double lexicalRescueScore,
            @Value("${deepresearch.rerank.lexical-rescue-coverage:0.3333333333333333}") double lexicalRescueCoverage) {
        if (!Double.isFinite(minimumScore) || !Double.isFinite(lexicalRescueScore)
                || lexicalRescueScore > minimumScore || !Double.isFinite(lexicalRescueCoverage)
                || lexicalRescueCoverage <= 0 || lexicalRescueCoverage > 1) {
            throw new IllegalArgumentException("Invalid legacy rerank evidence thresholds");
        }
        this.minimumScore = minimumScore;
        this.lexicalRescueScore = lexicalRescueScore;
        this.lexicalRescueCoverage = lexicalRescueCoverage;
    }

    boolean accepts(String question, HybridChunk chunk) {
        Double score = chunk.rerankScore();
        if (score == null || !Double.isFinite(score) || score < lexicalRescueScore) return false;
        Set<String> queryTerms = terms(question);
        if (queryTerms.isEmpty()) return false;
        Set<String> evidenceTerms = terms(chunk.title() + "\n" + HybridDocumentSupport.rerankText(chunk.document()));
        long overlap = queryTerms.stream().filter(evidenceTerms::contains).count();
        boolean meaningfulOverlap = queryTerms.stream()
                .anyMatch(term -> !term.matches("[0-9]+") && evidenceTerms.contains(term));
        if (!meaningfulOverlap) return false;
        return score >= minimumScore || (double) overlap / queryTerms.size() >= lexicalRescueCoverage;
    }

    private static Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Matcher matcher = TERMS.matcher(normalized);
        while (matcher.find()) {
            String term = matcher.group();
            if (term.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                for (int i = 0; i + 1 < term.length(); i++) result.add(term.substring(i, i + 2));
            } else if (term.length() >= 3 && !STOPWORDS.contains(term)) {
                result.add(term.length() >= 6 ? term.substring(0, 5) : term);
            }
        }
        return result;
    }
}

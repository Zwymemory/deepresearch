package com.deepresearch.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts machine-readable identifiers without treating ordinary query words as exact anchors.
 *
 * <p>The anchor subset is intentionally conservative: configuration assignments, API paths,
 * stable error/constant codes, versions, dotted/underscored machine tokens and explicitly
 * backtick-quoted tokens. Key/value assignments are compared after removing optional whitespace
 * and one matching quote pair from the value, so {@code durability=sync} and
 * {@code durability="sync"} identify the same configuration fact.</p>
 */
final class ExactIdentifierSupport {

    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?<![A-Za-z0-9_.-])([A-Za-z][A-Za-z0-9_.-]{1,63})\\s*=\\s*"
                    + "(\\\"[^\\\"\\r\\n]{1,80}\\\"|'[^'\\r\\n]{1,80}'|"
                    + "[A-Za-z0-9][A-Za-z0-9_.:/@+\\-]{0,79})");
    private static final Pattern API_PATH = Pattern.compile(
            "(?<![A-Za-z0-9])/(?:[A-Za-z0-9._~:@%{}-]+/)*[A-Za-z0-9._~:@%{}-]{2,}");
    private static final Pattern ERROR_CODE = Pattern.compile(
            "(?<![A-Za-z0-9_])[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+(?![A-Za-z0-9_])");
    private static final Pattern VERSION = Pattern.compile(
            "(?<![A-Za-z0-9.])[vV]?\\d+(?:\\.\\d+){1,3}(?:[-+][A-Za-z0-9.-]+)?(?![A-Za-z0-9.])");
    private static final Pattern MACHINE_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_.-])[A-Za-z][A-Za-z0-9]*(?:[_.][A-Za-z0-9]+)+(?![A-Za-z0-9_.-])");
    private static final Pattern HYPHENATED_WITH_DIGIT = Pattern.compile(
            "(?<![A-Za-z0-9-])(?=[A-Za-z0-9-]*\\d)[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+(?![A-Za-z0-9-])");
    private static final Pattern ALPHANUMERIC_CODE = Pattern.compile(
            "(?<![A-Za-z0-9-])[A-Z]{1,12}-?\\d{2,}(?![A-Za-z0-9-])");
    private static final Pattern BACKTICK_TOKEN = Pattern.compile(
            "`([A-Za-z0-9][A-Za-z0-9_.:/={}+\\-]{2,127})`");

    /** Legacy broader terms remain useful as Elasticsearch boosts, but never become TopK guards. */
    private static final Pattern SEARCH_ONLY_TERM = Pattern.compile(
            "[A-Za-z][A-Za-z0-9]*(?:[-_.][A-Za-z0-9]+)+|[A-Za-z]+\\d+|\\d+(?:\\.\\d+)+");

    private ExactIdentifierSupport() {
    }

    static List<Identifier> extractAnchors(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Identifier> identifiers = new LinkedHashMap<>();
        collectKeyValues(text, identifiers);
        collect(text, API_PATH, Kind.API_PATH, identifiers);
        collect(text, ERROR_CODE, Kind.ERROR_CODE, identifiers);
        collect(text, VERSION, Kind.VERSION, identifiers);
        collect(text, MACHINE_TOKEN, Kind.MACHINE_TOKEN, identifiers);
        collect(text, HYPHENATED_WITH_DIGIT, Kind.MACHINE_TOKEN, identifiers);
        collect(text, ALPHANUMERIC_CODE, Kind.ERROR_CODE, identifiers);
        collectBackticks(text, identifiers);
        return List.copyOf(identifiers.values());
    }

    static List<String> extractSearchTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        extractAnchors(text).forEach(identifier -> terms.add(identifier.display()));
        if (text != null) {
            Matcher matcher = SEARCH_ONLY_TERM.matcher(text);
            while (matcher.find()) {
                terms.add(matcher.group());
            }
        }
        return new ArrayList<>(terms);
    }

    static List<Identifier> matchingAnchors(List<Identifier> queryIdentifiers, String candidateText) {
        if (queryIdentifiers == null || queryIdentifiers.isEmpty()
                || candidateText == null || candidateText.isBlank()) {
            return List.of();
        }
        Set<String> candidateAssignments = new LinkedHashSet<>();
        for (Identifier candidate : extractAnchors(candidateText)) {
            if (candidate.kind() == Kind.KEY_VALUE) {
                candidateAssignments.add(candidate.canonical());
            }
        }
        List<Identifier> matches = new ArrayList<>();
        for (Identifier identifier : queryIdentifiers) {
            boolean matched = identifier.kind() == Kind.KEY_VALUE
                    ? candidateAssignments.contains(identifier.canonical())
                    : candidateText.contains(identifier.canonical());
            if (matched) {
                matches.add(identifier);
            }
        }
        return List.copyOf(matches);
    }

    private static void collectKeyValues(String text, Map<String, Identifier> identifiers) {
        Matcher matcher = KEY_VALUE.matcher(text);
        while (matcher.find()) {
            String display = matcher.group();
            String key = matcher.group(1);
            String value = stripMatchingQuotes(matcher.group(2).trim());
            add(identifiers, new Identifier(display, key + "=" + value, Kind.KEY_VALUE));
        }
    }

    private static void collect(String text,
                                Pattern pattern,
                                Kind kind,
                                Map<String, Identifier> identifiers) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = trimTrailingPunctuation(matcher.group());
            if (!value.isBlank()) {
                add(identifiers, new Identifier(value, value, kind));
            }
        }
    }

    private static void collectBackticks(String text, Map<String, Identifier> identifiers) {
        Matcher matcher = BACKTICK_TOKEN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value.contains("=") && KEY_VALUE.matcher(value).matches()) {
                Matcher assignment = KEY_VALUE.matcher(value);
                if (assignment.matches()) {
                    add(identifiers, new Identifier(
                            value,
                            assignment.group(1) + "=" + stripMatchingQuotes(assignment.group(2).trim()),
                            Kind.KEY_VALUE));
                }
            } else {
                add(identifiers, new Identifier(value, value, Kind.QUOTED_TOKEN));
            }
        }
    }

    private static void add(Map<String, Identifier> identifiers, Identifier identifier) {
        // Patterns are collected from strongest to weakest. A canonical identifier therefore
        // keeps its most informative classification instead of appearing twice (for example an
        // ERROR_CODE also satisfies the generic underscored-token pattern).
        identifiers.putIfAbsent(identifier.canonical(), identifier);
    }

    private static String stripMatchingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\"' && last == '\"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String trimTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0 && ",;!?".indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(0, end);
    }

    enum Kind {
        KEY_VALUE(60),
        API_PATH(50),
        ERROR_CODE(45),
        QUOTED_TOKEN(40),
        VERSION(35),
        MACHINE_TOKEN(30);

        private final int strength;

        Kind(int strength) {
            this.strength = strength;
        }

        int strength() {
            return strength;
        }
    }

    record Identifier(String display, String canonical, Kind kind) {
    }
}

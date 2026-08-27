package com.deepresearch.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仅在一次评测执行的内存中存活的安全收据。
 *
 * <p>它不包含模型 Thought，也不会被公开 API、数据库或 HTML 报告序列化。证据正文只保留
 * 工具原本已经脱敏、截断并回灌给模型的 observation，用于确定性 grounding 断言。</p>
 */
public final class AgentEvaluationArtifact {

    private static final Pattern SOURCE = Pattern.compile(
            "(?ms)^\\[来源(\\d+)]\\s*([^\\n]*)\\n(.*?)(?=^\\[来源\\d+]\\s|\\z)");
    private static final Pattern EVIDENCE_LINE = Pattern.compile(
            "(?m)^(?:证据|摘要|内容):\\s*(.+)$");

    private final List<ToolInvocation> invocations = new ArrayList<>();
    private final Set<String> allowedTools;
    private final Set<String> forbiddenTools;
    private int sourceSequence;
    private int evidenceSequence;
    private int currentModelRound = 1;

    private AgentEvaluationArtifact(List<String> allowedTools, List<String> forbiddenTools) {
        this.allowedTools = normalizeTools(allowedTools);
        this.forbiddenTools = normalizeTools(forbiddenTools);
    }

    public void beginModelRound(int round) {
        currentModelRound = Math.max(1, round);
    }

    public int currentModelRound() {
        return currentModelRound;
    }

    public boolean toolAllowed(String toolName) {
        String canonical = normalizeTool(toolName);
        return !forbiddenTools.contains(canonical)
                && (allowedTools.isEmpty() || allowedTools.contains(canonical));
    }

    public String capture(String toolName, String outcomeCode, String argumentFingerprint,
                          String observation, int modelRound) {
        return capture(toolName, outcomeCode, argumentFingerprint, observation, modelRound, false);
    }

    /**
     * Capture evidence whose markers already use the public answer's compact citation indexes.
     *
     * <p>The durable workflow may compact full evidence positions such as {@code [来源6]} to
     * public {@code [来源1]}. Harness grounding must preserve that published mapping instead of
     * assigning indexes from receipt arrival order.</p>
     */
    public String captureIndexed(String toolName, String outcomeCode, String argumentFingerprint,
                                 String observation, int modelRound) {
        return capture(toolName, outcomeCode, argumentFingerprint, observation, modelRound, true);
    }

    private String capture(String toolName, String outcomeCode, String argumentFingerprint,
                           String observation, int modelRound, boolean indexesArePublic) {
        String canonicalTool = normalizeTool(toolName);
        boolean successful = successful(outcomeCode) && !looksLikeFailure(observation);
        List<EvidenceRef> evidence = successful && retrievalTool(canonicalTool)
                ? parseEvidence(observation, canonicalTool, indexesArePublic) : List.of();
        invocations.add(new ToolInvocation(
                invocations.size() + 1,
                modelRound,
                canonicalTool,
                successful,
                outcomeCode == null ? "UNKNOWN" : outcomeCode,
                argumentFingerprint,
                evidence));
        return indexesArePublic ? observation : renumber(observation, evidence);
    }

    public String capture(String toolName, String outcomeCode, String argumentFingerprint,
                          String observation) {
        return capture(toolName, outcomeCode, argumentFingerprint, observation, currentModelRound);
    }

    public List<ToolInvocation> invocations() {
        return List.copyOf(invocations);
    }

    public List<EvidenceRef> evidences() {
        return invocations.stream().flatMap(invocation -> invocation.evidence().stream()).toList();
    }

    private String renumber(String observation, List<EvidenceRef> evidence) {
        if (observation == null || evidence.isEmpty()) {
            return observation;
        }
        Map<Integer, Integer> indexes = new java.util.LinkedHashMap<>();
        evidence.forEach(ref -> indexes.put(ref.localIndex(), ref.globalIndex()));
        Matcher matcher = Pattern.compile("\\[来源(\\d+)]").matcher(observation);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            int local = Integer.parseInt(matcher.group(1));
            int global = indexes.getOrDefault(local, local);
            matcher.appendReplacement(result, Matcher.quoteReplacement("[来源" + global + "]"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private List<EvidenceRef> parseEvidence(String observation, String toolName,
                                            boolean indexesArePublic) {
        if (observation == null || observation.isBlank()) {
            return List.of();
        }
        // 工具层已经在输出进入 Agent 前完成 secret redaction；这里不再扩大可见范围。
        String sanitized = observation;
        Matcher matcher = SOURCE.matcher(sanitized);
        List<EvidenceRef> refs = new ArrayList<>();
        while (matcher.find()) {
            int localIndex = Integer.parseInt(matcher.group(1));
            String title = compact(matcher.group(2));
            String body = compact(matcher.group(3));
            Matcher evidenceLine = EVIDENCE_LINE.matcher(matcher.group(3));
            String supportText = evidenceLine.find() ? compact(evidenceLine.group(1)) : body;
            int globalIndex;
            if (indexesArePublic) {
                globalIndex = localIndex;
                sourceSequence = Math.max(sourceSequence, globalIndex);
            } else {
                globalIndex = ++sourceSequence;
            }
            refs.add(new EvidenceRef(
                    "evidence-" + (++evidenceSequence),
                    globalIndex,
                    localIndex,
                    toolName,
                    title,
                    supportText,
                    sha256(title + "\n" + supportText)));
        }
        return List.copyOf(refs);
    }

    private boolean successful(String code) {
        return code != null && List.of("OK", "SUCCESS", "TOOL_SUCCEEDED")
                .contains(code.toUpperCase(Locale.ROOT));
    }

    private boolean looksLikeFailure(String observation) {
        if (observation == null || observation.isBlank()) {
            return true;
        }
        String normalized = observation.toLowerCase(Locale.ROOT);
        return normalized.startsWith("（") && (normalized.contains("失败")
                || normalized.contains("被拒绝") || normalized.contains("没有检索到")
                || normalized.contains("未检索到"));
    }

    public static boolean successfulObservation(String observation) {
        if (observation == null || observation.isBlank()) {
            return false;
        }
        String normalized = observation.toLowerCase(Locale.ROOT).trim();
        return !(normalized.startsWith("（") && (normalized.contains("失败")
                || normalized.contains("被拒绝") || normalized.contains("没有检索到")
                || normalized.contains("未检索到")));
    }

    private boolean retrievalTool(String toolName) {
        return "knowledge_search".equals(toolName) || "web_search".equals(toolName);
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static String normalizeTool(String tool) {
        String normalized = tool == null ? "" : tool.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "kb_search", "searchknowledge", "knowledge_search" -> "knowledge_search";
            case "calculator", "calculate" -> "calculator";
            case "file_read", "readprojectfile" -> "file_read";
            case "web_search", "searchweb", "search" -> "web_search";
            default -> normalized;
        };
    }

    private Set<String> normalizeTools(List<String> tools) {
        if (tools == null) {
            return Set.of();
        }
        return tools.stream().filter(tool -> tool != null && !tool.isBlank())
                .map(AgentEvaluationArtifact::normalizeTool)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record ToolInvocation(
            int ordinal,
            int modelRound,
            String toolName,
            boolean successful,
            String outcomeCode,
            String argumentFingerprint,
            List<EvidenceRef> evidence
    ) {
    }

    public record EvidenceRef(
            String evidenceId,
            int globalIndex,
            int localIndex,
            String toolName,
            String title,
            String supportText,
            String contentDigest
    ) {
        public String marker() {
            return "[来源" + globalIndex + "]";
        }
    }

    public static final class Scope implements AutoCloseable {
        private static final ThreadLocal<AgentEvaluationArtifact> CURRENT = new ThreadLocal<>();
        private final AgentEvaluationArtifact previous;
        private final AgentEvaluationArtifact artifact;
        private boolean closed;

        private Scope(AgentEvaluationArtifact artifact) {
            this.previous = CURRENT.get();
            this.artifact = artifact;
            CURRENT.set(artifact);
        }

        public static Scope open(List<String> allowedTools, List<String> forbiddenTools) {
            return new Scope(new AgentEvaluationArtifact(allowedTools, forbiddenTools));
        }

        public static AgentEvaluationArtifact current() {
            return CURRENT.get();
        }

        public AgentEvaluationArtifact artifact() {
            return artifact;
        }

        @Override
        public void close() {
            if (!closed) {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
                closed = true;
            }
        }
    }
}

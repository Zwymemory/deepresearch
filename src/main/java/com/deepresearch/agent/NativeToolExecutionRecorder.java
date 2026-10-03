package com.deepresearch.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按当前线程记录原生工具调用的安全元数据；run 结束后必须 close，避免线程池复用污染。 */
@Component
public class NativeToolExecutionRecorder {

    private static final Pattern LOCAL_SOURCE_MARKER = Pattern.compile("\\[来源(\\d+)]");

    private final ThreadLocal<RunState> current = new ThreadLocal<>();

    public Scope open() {
        current.set(new RunState());
        return new Scope(this);
    }

    public void record(String toolName, String argumentFingerprint, StructuredToolResult result) {
        RunState state = current.get();
        if (state != null) {
            state.invocations.add(new Invocation(
                    toolName, result.success(), result.code(), argumentFingerprint));
        }
    }

    /**
     * Rewrites one retrieval observation from tool-local numbering to run-global
     * numbering and stores the corresponding typed source IDs in the same order.
     * No source is inferred from the observation text.
     */
    public String globalizeCitations(CitationAwareToolOutput output) {
        if (output == null) {
            return "";
        }
        RunState state = current.get();
        if (state == null || output.sourceIds().isEmpty()) {
            return output.content();
        }

        int[] globalIndexes = new int[output.sourceIds().size()];
        for (int i = 0; i < output.sourceIds().size(); i++) {
            String sourceId = output.sourceIds().get(i);
            if (sourceId != null && !sourceId.isBlank()) {
                int existing = state.citations.indexOf(sourceId);
                if (existing >= 0) {
                    globalIndexes[i] = existing + 1;
                } else {
                    state.citations.add(sourceId);
                    globalIndexes[i] = state.citations.size();
                }
            }
        }
        for (var snapshot : output.sourceSnapshots()) {
            if (output.sourceIds().contains(snapshot.sourceId())) {
                if (state.snapshots.size() < 256) state.snapshots.add(snapshot);
                else state.snapshotLimitExceeded = true;
            }
        }

        Matcher matcher = LOCAL_SOURCE_MARKER.matcher(output.content());
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            int localIndex;
            try {
                localIndex = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException invalid) {
                localIndex = 0;
            }
            int globalIndex = localIndex >= 1 && localIndex <= globalIndexes.length
                    ? globalIndexes[localIndex - 1] : 0;
            String replacement = globalIndex > 0
                    ? "[来源" + globalIndex + "]" : "［未验证来源］";
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private Snapshot close() {
        RunState state = current.get();
        current.remove();
        if (state == null) {
            return new Snapshot(List.of(), List.of(), List.of(), false);
        }
        return new Snapshot(List.copyOf(state.invocations), List.copyOf(state.citations),
                List.copyOf(state.snapshots), state.snapshotLimitExceeded);
    }

    public record Invocation(String toolName, boolean success, String code, String argumentFingerprint) {
    }

    private record Snapshot(List<Invocation> invocations, List<String> citations,
                            List<CitationDetail> snapshots, boolean snapshotLimitExceeded) {
    }

    private static final class RunState {
        private final List<Invocation> invocations = new ArrayList<>();
        private final List<String> citations = new ArrayList<>();
        private final List<CitationDetail> snapshots = new ArrayList<>();
        private boolean snapshotLimitExceeded;
    }

    public static final class Scope implements AutoCloseable {
        private final NativeToolExecutionRecorder recorder;
        private boolean closed;
        private List<Invocation> invocations = List.of();
        private List<String> citations = List.of();
        private List<CitationDetail> snapshots = List.of();
        private boolean snapshotLimitExceeded;

        private Scope(NativeToolExecutionRecorder recorder) {
            this.recorder = recorder;
        }

        public List<Invocation> invocations() {
            return invocations;
        }

        public List<String> citations() {
            return citations;
        }

        public List<CitationDetail> citationDetails(List<String> selectedCitations) {
            if (snapshotLimitExceeded) return selectedCitations.stream()
                    .map(id -> CitationDetail.unavailable(id, "SNAPSHOT_LIMIT")).toList();
            return CitationDetail.project(selectedCitations, snapshots);
        }

        @Override
        public void close() {
            if (!closed) {
                Snapshot snapshot = recorder.close();
                invocations = snapshot.invocations();
                citations = snapshot.citations();
                snapshots = snapshot.snapshots();
                snapshotLimitExceeded = snapshot.snapshotLimitExceeded();
                closed = true;
            }
        }
    }
}

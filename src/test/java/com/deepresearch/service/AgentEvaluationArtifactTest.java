package com.deepresearch.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvaluationArtifactTest {

    @Test
    void renumbersEvidenceAcrossMultipleRetrievalCallsAndKeepsOnlySafeReceiptMetadata() {
        try (AgentEvaluationArtifact.Scope scope = AgentEvaluationArtifact.Scope.open(List.of(), List.of())) {
            AgentEvaluationArtifact artifact = scope.artifact();
            String first = artifact.capture("kb_search", "TOOL_SUCCEEDED", "hash-1",
                    "[来源1] 技术手册\n证据: MCP-7788 是混合检索参数。", 1);
            String second = artifact.capture("web_search", "OK", "hash-2",
                    "[来源1] 官方文档\n摘要: RRF 的平滑常数是 60。", 2);

            assertThat(first).contains("[来源1]");
            assertThat(second).contains("[来源2]").doesNotContain("[来源1]");
            assertThat(artifact.evidences()).extracting(AgentEvaluationArtifact.EvidenceRef::marker)
                    .containsExactly("[来源1]", "[来源2]");
            assertThat(artifact.evidences()).allSatisfy(ref ->
                    assertThat(ref.contentDigest()).matches("[0-9a-f]{64}"));
        }
    }

    @Test
    void blocksForbiddenToolBeforeExecutionAndDoesNotTreatFailureTextAsSuccess() {
        try (AgentEvaluationArtifact.Scope scope = AgentEvaluationArtifact.Scope.open(
                List.of("kb_search"), List.of("file_read"))) {
            AgentEvaluationArtifact artifact = scope.artifact();
            assertThat(artifact.toolAllowed("readProjectFile")).isFalse();
            assertThat(artifact.toolAllowed("searchKnowledge")).isTrue();
            artifact.capture("kb_search", "TOOL_SUCCEEDED", "hash",
                    "（知识库中没有检索到相关证据）", 1);

            assertThat(artifact.invocations()).singleElement()
                    .satisfies(invocation -> assertThat(invocation.successful()).isFalse());
            assertThat(artifact.evidences()).isEmpty();
        }
    }
}

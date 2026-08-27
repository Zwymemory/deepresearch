package com.deepresearch.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExactIdentifierSupportTest {

    @Test
    void normalizesOptionalQuotesAndWhitespaceInKeyValueAssignments() {
        List<ExactIdentifierSupport.Identifier> query =
                ExactIdentifierSupport.extractAnchors("durability=sync 在当前项目里保证什么？");

        assertThat(query).singleElement().satisfies(identifier -> {
            assertThat(identifier.kind()).isEqualTo(ExactIdentifierSupport.Kind.KEY_VALUE);
            assertThat(identifier.canonical()).isEqualTo("durability=sync");
        });
        assertThat(ExactIdentifierSupport.matchingAnchors(
                query, "graph.ainvoke(..., durability = \"sync\") 会同步保存 checkpoint"))
                .extracting(ExactIdentifierSupport.Identifier::canonical)
                .containsExactly("durability=sync");
    }

    @Test
    void extractsHighInformationIdentifiersButNotOrdinaryWordsOrNumbers() {
        List<ExactIdentifierSupport.Identifier> identifiers = ExactIdentifierSupport.extractAnchors(
                "检查 /api/research/workflows/{runId}/events、MODEL_SCHEMA_INVALID、v1.2.3 和 120ms 延迟");

        assertThat(identifiers).extracting(ExactIdentifierSupport.Identifier::canonical)
                .contains("/api/research/workflows/{runId}/events", "MODEL_SCHEMA_INVALID", "v1.2.3")
                .doesNotContain("120", "120ms");
        assertThat(ExactIdentifierSupport.extractAnchors("普通 durability sync checkpoint 问题")).isEmpty();
    }

    @Test
    void doesNotTreatSeparatedKeyAndValueAsAnExactMatch() {
        List<ExactIdentifierSupport.Identifier> query =
                ExactIdentifierSupport.extractAnchors("durability=sync 的语义");

        assertThat(ExactIdentifierSupport.matchingAnchors(
                query, "durability 配置会同步 sync checkpoint，但没有给出配置赋值")).isEmpty();
    }
}

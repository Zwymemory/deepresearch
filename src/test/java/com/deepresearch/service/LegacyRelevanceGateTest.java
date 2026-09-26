package com.deepresearch.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyRelevanceGateTest {
    private final LegacyRelevanceGate gate = new LegacyRelevanceGate(-5.0, -6.5, 1.0 / 3);

    @Test
    void rejectsNumericCollisionAndWeakUnrelatedScores() {
        assertThat(gate.accepts("What is the dinner menu on train 59264?",
                chunk("Run 59264 is reserved as an event replay example", -4.8))).isFalse();
        assertThat(gate.accepts("What is the payroll bank routing number?",
                chunk("The run number is 59264", -5.9))).isFalse();
    }

    @Test
    void rescuesSpecificCitationEvidenceThatModelUnderranks() {
        assertThat(gate.accepts("What must be verified before trusting a citation?",
                chunk("A citation is accepted only when the dataset, document and chunk can be verified", -6.3)))
                .isTrue();
    }

    @Test
    void acceptsChineseBoundaryEvidenceAndRejectsMissingScores() {
        assertThat(gate.accepts("DeepResearch 项目维护者的私人手机号和个人邮箱是什么？",
                chunk("DeepResearch 项目知识包不包含联系方式。", -3.8))).isTrue();
        assertThat(gate.accepts("Where is the payroll account?",
                new HybridChunk(new Document("unscored", "payroll account", Map.of())))).isFalse();
    }

    @Test
    void rejectsInvalidThresholdConfiguration() {
        assertThatThrownBy(() -> new LegacyRelevanceGate(-6.5, -5.0, 1.0 / 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegacyRelevanceGate(-5.0, -6.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private HybridChunk chunk(String text, double score) {
        HybridChunk chunk = new HybridChunk(new Document("id", text, Map.of()));
        chunk.setRerankScore(score);
        return chunk;
    }
}

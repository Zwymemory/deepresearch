package com.deepresearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyEvidenceVerifierTest {
    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final LegacyEvidenceVerifier verifier = new LegacyEvidenceVerifier(chatClient, new ObjectMapper());
    private final HybridChunk passage = new HybridChunk(new Document(
            "a", "The fixed validation batch is 73018.", Map.of()));

    @Test
    void requiresExactQuoteInIndependentConfirmation() {
        responses("{\"candidateIds\":[\"c0\"]}",
                "{\"supported\":true,\"quote\":\"batch is 73018\"}");

        assertThat(verifier.verify("固定验证批次是什么？", List.of(passage))).containsExactly("a");
    }

    @Test
    void rejectsUnsupportedOrInventedModelClaims() {
        responses("{\"candidateIds\":[\"c0\"]}",
                "{\"supported\":false,\"quote\":\"\"}");
        assertThat(verifier.verify("What real company used it?", List.of(passage))).isEmpty();

        responses("{\"candidateIds\":[\"not-a-candidate\"]}");
        assertThat(verifier.verify("What batch?", List.of(passage))).isEmpty();
    }

    @Test
    void independentlyChecksTopRankedPassageWhenBatchMissesIt() {
        responses("{\"candidateIds\":[]}",
                "{\"supported\":true,\"quote\":\"batch is 73018\"}");

        assertThat(verifier.verify("What is the batch?", List.of(passage))).containsExactly("a");
    }

    @Test
    void retriesChineseQuestionInPassageLanguageAfterUnsupportedFirstCheck() {
        responses("{\"candidateIds\":[]}",
                "{\"supported\":false,\"quote\":\"\"}",
                "What is the fixed validation batch?",
                "{\"supported\":true,\"quote\":\"batch is 73018\"}");

        assertThat(verifier.verify("固定验证批次是什么？", List.of(passage))).containsExactly("a");
    }

    @Test
    void rejectsSyntheticExamplesAsActualProductionValues() {
        String text = "The synthetic threshold example is 0.42 and must not be copied into production defaults.";
        assertThat(LegacyEvidenceVerifier.contradictsScope(
                "What actual production retrieval default was set to 0.42?", text)).isTrue();
        assertThat(LegacyEvidenceVerifier.contradictsScope(
                "What is the synthetic threshold example?", text)).isFalse();

        HybridChunk example = new HybridChunk(new Document("example", text, Map.of()));
        responses("{\"candidateIds\":[\"c0\"]}",
                "{\"supported\":true,\"quote\":\"0.42\"}");
        assertThat(verifier.verify("What actual production retrieval default was set to 0.42?",
                List.of(example))).isEmpty();
    }

    @Test
    void malformedVerifierOutputFailsClosed() {
        responses("not JSON");
        assertThatThrownBy(() -> verifier.verify("What batch?", List.of(passage)))
                .isInstanceOf(IllegalStateException.class);
    }

    private void responses(String... values) {
        when(chatClient.prompt().system(anyString()).user(anyString())
                .options(any()).call().content()).thenReturn(values[0], java.util.Arrays.copyOfRange(values, 1, values.length));
    }
}

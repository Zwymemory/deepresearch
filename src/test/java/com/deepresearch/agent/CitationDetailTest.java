package com.deepresearch.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CitationDetailTest {
    @Test void historicalNativeResponseDeserializesWithExplicitMissingMetadata() throws Exception {
        var response = new ObjectMapper().readValue("""
                {"runId":"old-run","answer":"Old answer [来源1]","citations":["kb:old-chunk"],"citationContract":"INDEXED_V1"}
                """, com.deepresearch.web.dto.AgentResearchResponse.class);
        assertThat(response.citations()).containsExactly("kb:old-chunk");
        assertThat(response.citationDetails()).containsExactly(CitationDetail.unavailable("kb:old-chunk", "MISSING_SNAPSHOT"));
        assertThat(new ObjectMapper().valueToTree(response).path("citationDetails").get(0).get("url").isNull()).isTrue();
    }

    @Test void snapshotTextIsBoundedRedactedAndNullRemainsUnknown() throws Exception {
        var detail = CitationDetail.knowledge("kb:chunk", "password=synthetic-secret", "😀".repeat(2500));
        assertThat(detail.title()).isEqualTo("password=[REDACTED]");
        assertThat(detail.excerpt().codePointCount(0, detail.excerpt().length())).isEqualTo(2400);
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(detail));
        assertThat(json.get("url").isNull()).isTrue();
        assertThat(CitationDetail.knowledge("kb:chunk", null, null).title()).isNull();
    }

    @Test void nativeScopeDetailsFollowSelectedCitationOrderAndNeverLeakIntoTheNextRun() {
        var recorder = new NativeToolExecutionRecorder();
        var a = CitationDetail.web("https://example.org/a", "A", "https://example.org/a", "a");
        var b = CitationDetail.knowledge("kb:chunk", "B", "b");
        var scope = recorder.open();
        try (scope) {
            recorder.globalizeCitations(new CitationAwareToolOutput("[来源1] A [来源2] B",
                    List.of(a.sourceId(), b.sourceId()), List.of(a, b)));
        }
        assertThat(scope.citationDetails(List.of(b.sourceId(), a.sourceId()))).containsExactly(b, a);
        var next = recorder.open();
        next.close();
        assertThat(next.citations()).isEmpty();
        assertThat(next.citationDetails(List.of(a.sourceId())))
                .containsExactly(CitationDetail.unavailable(a.sourceId(), "MISSING_SNAPSHOT"));
    }
}

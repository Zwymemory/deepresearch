package com.deepresearch.evidence;

import org.junit.jupiter.api.Test;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

class EvidenceQuoteTest {
    @Test void serverComputesUnicodeRangeAndHashFromUniqueCompleteText() {
        String original = "标题 🧪\n\nVersion: 1.0\n\nLimit: 10 only in legacy mode.";
        String paragraph = "Limit: 10 only in legacy mode.";
        var bound = EvidenceAdjudicator.bindQuote(object("snapshot", object("text", original)), JSON.valueToTree(paragraph));
        assertThat(bound.path("start").asInt()).isEqualTo(original.codePointCount(0, original.indexOf(paragraph)));
        assertThat(bound.path("sha256").asText()).isEqualTo(sha(paragraph));
        assertThat(bound.path("end").asInt() - bound.path("start").asInt()).isEqualTo(paragraph.length());
    }

    @Test void missingAmbiguousAndIncompleteTextCannotBecomeCitation() {
        var repeated = object("snapshot", object("text", "Limit: 10\n\nLimit: 10"));
        assertThatThrownBy(() -> EvidenceAdjudicator.bindQuote(repeated, JSON.valueToTree("Limit: 10")))
                .hasMessageContaining("CHECK_QUOTE_BINDING_INVALID");
        assertThatThrownBy(() -> EvidenceAdjudicator.bindQuote(repeated, JSON.valueToTree("Limit: 20")))
                .hasMessageContaining("CHECK_QUOTE_BINDING_INVALID");
        var qualified = object("snapshot", object("text", "Limit: 10 only in legacy mode; otherwise 20."));
        assertThatThrownBy(() -> EvidenceAdjudicator.bindQuote(qualified, JSON.valueToTree("Limit: 10")))
                .hasMessageContaining("CHECK_QUOTE_CONTEXT_INCOMPLETE");
    }
}

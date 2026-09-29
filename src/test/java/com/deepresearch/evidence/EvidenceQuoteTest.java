package com.deepresearch.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

class EvidenceQuoteTest {
    @ParameterizedTest @ValueSource(strings={"\u00a0", "\u0085", "\u2007", "\u202f"})
    void unicodeBoundaryWhitespaceAcceptsWholeParagraphAndRejectsOmittedQualifier(String space) {
        var original = object("snapshot", object("text", "标题 🧪\n\n" + space + "Limit: 10 only in legacy mode." + space));
        var quote = EvidenceAdjudicator.bindQuote(original, JSON.valueToTree("Limit: 10 only in legacy mode."));
        assertThat(quote.path("sha256").asText()).isEqualTo(sha("Limit: 10 only in legacy mode."));
        assertThatThrownBy(() -> EvidenceAdjudicator.bindQuote(original, JSON.valueToTree("Limit: 10")))
                .hasMessageContaining("CHECK_QUOTE_CONTEXT_INCOMPLETE");
        var corrupt = (com.fasterxml.jackson.databind.node.ObjectNode) quote.deepCopy(); corrupt.put("sha256", "0".repeat(64));
        assertThatThrownBy(() -> EvidenceAdjudicator.quote(original, corrupt)).hasMessageContaining("CHECK_QUOTE_BINDING_INVALID");
    }
    @Test void pythonAdditionalControlSeparatorsAreNotBoundaryWhitespace() {
        var source = object("snapshot", object("text", "\u001cLimit: 10\u001f"));
        assertThatThrownBy(() -> EvidenceAdjudicator.bindQuote(source, JSON.valueToTree("Limit: 10")))
                .hasMessageContaining("CHECK_QUOTE_CONTEXT_INCOMPLETE");
    }
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

package com.deepresearch.workflow;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.assertThat;

class AgentQuestionSegmentsTest {
    private JsonNode corpus() throws Exception {
        return JSON.readTree(Path.of("testdata/agent-foundation/runtime/question-segment-fixtures.json").toFile());
    }
    @Test void pythonMappingAndCanonicalDeclarationAreReconstructedExactlyForUnicodeAndBoundedUnits() throws Exception {
        for(var fixture:corpus()) {
            String question=fixture.path("question").asText();
            assertThat(AgentQuestionSegments.mapping(question)).isEqualTo(fixture.path("mapping"));
            var drafts=AgentQuestionSegments.declarations(question,fixture.path("receipt"));
            assertThat(drafts).isEqualTo(fixture.path("canonical_drafts"));
            assertThat(AgentRequirementCompletionService.validManifest(fixture.path("manifest"),"segment-fixture",question,drafts)).isTrue();
        }
    }
    @Test void unknownNullForeignOrChangedProvenanceCannotDowngradeToLegacy() throws Exception {
        var fixture=corpus().get(0);String q=fixture.path("question").asText();
        for(String change:new String[]{"value-null","binding-null","version","mapping-version","question-hash","mapping-hash","response-hash","unknown-id","duplicate-id","omitted-ids","coordinates"}) {
            ObjectNode receipt=fixture.path("receipt").deepCopy();var binding=(ObjectNode)receipt.path("request_binding");
            var value=(ObjectNode)JSON.readTree(binding.path("planner_declaration").asText());
            switch(change) {
                case "value-null" -> {value.putNull("planner_contract");binding.remove("planner_contract");}
                case "binding-null" -> {value.remove("planner_contract");binding.putNull("planner_contract");}
                case "version" -> value.put("planner_contract","unknown/99");
                case "mapping-version" -> binding.put("question_mapping_version","unknown/99");
                case "question-hash" -> binding.put("question_sha256","a".repeat(64));
                case "mapping-hash" -> binding.put("question_mapping_sha256","a".repeat(64));
                case "response-hash" -> binding.put("response_sha256","a".repeat(64));
                case "unknown-id" -> ((ObjectNode)value.path("requirements").get(0)).set("segment_ids",JSON.valueToTree(java.util.List.of("foreign-id")));
                case "duplicate-id" -> {var ids=(com.fasterxml.jackson.databind.node.ArrayNode)value.path("requirements").get(0).path("segment_ids");ids.add(ids.get(0));}
                case "omitted-ids" -> ((com.fasterxml.jackson.databind.node.ArrayNode)value.path("requirements")).remove(1);
                case "coordinates" -> ((ObjectNode)value.path("requirements").get(0)).set("question_spans",JSON.valueToTree(java.util.List.of(object("start",0,"end",1))));
            }
            binding.put("planner_declaration",canonical(value));binding.put("wire_response_sha256",sha(canonical(value)));
            assertThat(AgentQuestionSegments.declarations(q,receipt).isNull()).as(change).isTrue();
        }
    }
    @Test void untouchedLegacyDeclarationRemainsReadableAndUnknownLimitsReject() throws Exception {
        var fixture=corpus().get(0);String q=fixture.path("question").asText();
        var old=object("value",object("requirements",fixture.path("canonical_drafts")));
        assertThat(AgentQuestionSegments.declarations(q,old)).isEqualTo(fixture.path("canonical_drafts"));
        assertThat(AgentRequirementCompletionService.validManifest(fixture.path("manifest"),"segment-fixture",q,AgentQuestionSegments.declarations(q,old))).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->AgentQuestionSegments.mapping("😀".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
    }
}

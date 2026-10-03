package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.assertThat;

class AgentRequirementCompletionServiceTest {
    private JsonNode manifest(String run,String question,JsonNode declaration) {
        var requirements=new java.util.ArrayList<JsonNode>();
        for(var draft:declaration) {
            String id="requirement-"+sha(canonical(object("contract_version","agent-original-requirements/1","run_id",run,
                "question_sha256",sha(question),"declaration",draft))).substring(0,48);
            var requirement=(com.fasterxml.jackson.databind.node.ObjectNode)draft.deepCopy();requirement.put("requirement_id",id);requirements.add(requirement);
        }
        requirements.sort(java.util.Comparator.comparing(r->r.path("requirement_id").asText()));
        var core=object("contract_version","agent-original-requirements/1","run_id",run,"question_sha256",sha(question),
            "question_length",question.codePointCount(0,question.length()),"requirements",requirements);
        core.put("manifest_sha256",sha(canonical(core)));return core;
    }
    private JsonNode draft(String text,int start,int end) {
        return object("text",text,"kind","factual","question_spans",List.of(object("start",start,"end",end)),
            "applicability",object("subject",text,"version",unknown("Not independently established"),
                "valid_at",unknown("Not independently established"),"conditions",List.of()));
    }
    @Test void exactUnicodeQuestionAndDistinctAnchorsBindImmutableDeclaration() {
        String question="加密🙂 retention";int length=question.codePointCount(0,question.length());
        var declarations=JSON.valueToTree(List.of(draft("Encryption",0,3),draft("Retention",4,length)));
        var manifest=manifest("run",question,declarations);
        assertThat(AgentRequirementCompletionService.validManifest(manifest,"run",question,declarations)).isTrue();
        assertThat(AgentRequirementCompletionService.validManifest(manifest,"run",question+" changed",declarations)).isFalse();
        assertThat(AgentRequirementCompletionService.validManifest(manifest,"another",question,declarations)).isFalse();
        var changed=JSON.valueToTree(List.of(draft("Encryption",0,3)));
        assertThat(AgentRequirementCompletionService.validManifest(manifest,"run",question,changed)).isFalse();
        assertThat(AgentRequirementCompletionService.validManifest(manifest("run",question,changed),"run",question,changed)).isFalse();
    }
    @Test void pythonUnicodeWhitespaceBetweenAnchorsIsAcceptedWithoutChangingObligations() {
        for(String space:List.of("\u0085","\u00a0","\u2007","\u202f","\u001c","\u001f")) {
            String question="first"+space+"second";
            var declarations=JSON.valueToTree(List.of(draft("First",0,5),draft("Second",6,12)));
            assertThat(AgentRequirementCompletionService.validManifest(manifest("run",question,declarations),"run",question,declarations)).isTrue();
        }
    }
    @Test void genericWholeQuestionAnchorDoesNotCertifySemanticExtraction() {
        String question="encryption AND retention";
        var generic=JSON.valueToTree(List.of(draft("generic",0,question.length())));
        assertThat(AgentRequirementCompletionService.validManifest(manifest("run",question,generic),"run",question,generic)).isTrue();
        // This intentionally documents the semantic extraction limit. Completeness
        // still needs distinct obligations emitted by the budgeted model/evaluation.
    }
}

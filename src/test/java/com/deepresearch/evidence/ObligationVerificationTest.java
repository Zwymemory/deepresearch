package com.deepresearch.evidence;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

/** Pure native semantic gates, with controlled verifier proposals and exact original quotes. */
class ObligationVerificationTest {
    static final String ORIGINAL="Preview releases must not be used for customer production deployments.";
    JsonNode request(String uri,String kind) {
        String question="Use https://docs.example.test/policy; can preview releases be used in production? Quote the policy.";
        var scope=object("subject","Preview deployments","version",unknown("Not established"),"valid_at",unknown("Not established"),"conditions",List.of());
        return object("protocol_version","evidence-check/3","check_id","check-controlled","dispute_round",0,"prior_relations",List.of(),
            "claims",List.of(object("claim_id","claim-controlled","text",ORIGINAL,"kind",kind,"applicability",scope)),
            "evidence",List.of(object("evidence_id","evidence-controlled","source",object("locator",object("uri",uri),"title","Release Channel Policy"),
                "snapshot",object("text",ORIGINAL,"sha256",sha(ORIGINAL)),"applicability",scope)),
            "original_context",object("question",question,"claim_bindings",List.of(object("claim_index",0,"requirement_id","requirement-controlled","criterion_id","criterion-controlled")),
                "constraints",List.of(object("role","source","question_spans",List.of(object("start",0,"end",question.indexOf(';')+1)),"requirement_ids",List.of("requirement-controlled")))));
    }
    JsonNode response(String plan,String answer,String source) {
        return object("planning_alignment",object("status",plan,"reason","Controlled entire original question assessment"),
            "claims",List.of(object("claim_id","claim-controlled","answer_alignment",answer,"limitations",List.of(),
                "relations",List.of(object("evidence_id","evidence-controlled","relation","supports","quote",ORIGINAL,"reason","Controlled exact policy paragraph","source_alignment",source)))),"follow_up_actions",List.of());
    }
    String status(JsonNode request,JsonNode response) {
        var grant=new EvidenceAuthority.Grant(new AuthPrincipal("tenant","owner",List.of()),"project","run","task","call","claim");
        var result=new EvidenceAdjudicator().adjudicate(grant,request,response,"assessment-controlled",Map.of("evidence-controlled",object("verified_observation",false)));
        assertThat(result.semantic_truth_guaranteed()).isFalse();
        return result.records().get(0).path("decision_status").asText();
    }
    @Test void correctDirectAnswerAndActualSourceCanSupportBothFactualAndGenuineRecommendation() {
        assertThat(status(request("https://docs.example.test/policy","factual"),response("complete","answers","qualifies"))).isEqualTo("supported");
        assertThat(status(request("https://docs.example.test/policy","recommendation"),response("complete","answers","qualifies"))).isEqualTo("supported");
    }
    @Test void truthfulSupportedRelationDoesNotCertifyQuestionAnswerOrWholePartition() {
        for(String answer:List.of("irrelevant","absence_only","instruction_only","unresolved"))
            assertThat(status(request("https://docs.example.test/policy","factual"),response("complete",answer,"qualifies"))).isEqualTo("insufficient");
        for(String plan:List.of("incomplete","uncertain"))
            assertThat(status(request("https://docs.example.test/policy","factual"),response(plan,"answers","qualifies"))).isEqualTo("insufficient");
    }
    @Test void wrongNamedSourceAndFinalRedirectCannotPassEvenDishonestQualifies() {
        assertThat(status(request("https://docs.example.test/policy","factual"),response("complete","answers","wrong_source"))).isEqualTo("insufficient");
        assertThat(status(request("https://docs.example.test/news/sibling","factual"),response("complete","answers","qualifies"))).isEqualTo("insufficient");
    }
    @Test void check3MissingAlignmentFailsAndOldRecordResultBytesKeepThreeFields() {
        var invalid=(com.fasterxml.jackson.databind.node.ObjectNode)response("complete","answers","qualifies");invalid.remove("planning_alignment");
        assertThatThrownBy(()->status(request("https://docs.example.test/policy","factual"),invalid)).isInstanceOf(EvidenceException.class);
        assertThat(JSON.valueToTree(new EvidenceDtos.RecordResult(List.of(),List.of(),false)).size()).isEqualTo(3);
        assertThat(JSON.valueToTree(new EvidenceDtos.RecordResult(List.of(),List.of(),false)).has("verification")).isFalse();
    }
    @Test void newSemanticLabelsCannotDismissPreviouslyApplicableCounterevidence() {
        for(String changed:List.of("source","answer","partition")) {
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)request("https://docs.example.test/policy","factual");
            String contraryText=ORIGINAL.replace("must not", "may");
            var contrary=request.path("evidence").get(0).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)contrary).put("evidence_id","evidence-contrary");
            ((com.fasterxml.jackson.databind.node.ArrayNode)request.path("evidence")).add(contrary);
            request.set("prior_relations",JSON.valueToTree(List.of(object("claim_id","claim-controlled","evidence_id","evidence-contrary",
                "relation","refutes","quote",object("start",0,"end",contraryText.length(),"text",contraryText,"sha256",sha(contraryText)),
                "assessment_ref","assessment-prior","decision_id","decision-prior"))));
            ((com.fasterxml.jackson.databind.node.ObjectNode)contrary.path("snapshot")).put("text",contraryText).put("sha256",sha(contraryText));
            var response=(com.fasterxml.jackson.databind.node.ObjectNode)response("complete","answers","qualifies");
            ((com.fasterxml.jackson.databind.node.ArrayNode)response.path("claims").get(0).path("relations")).add(object("evidence_id","evidence-contrary",
                "relation","supports","quote",contraryText,"reason","Changed proposal only","source_alignment",changed.equals("source")?"wrong_source":"qualifies"));
            if(changed.equals("answer")) ((com.fasterxml.jackson.databind.node.ObjectNode)response.path("claims").get(0)).put("answer_alignment","unresolved");
            if(changed.equals("partition")) ((com.fasterxml.jackson.databind.node.ObjectNode)response.path("planning_alignment")).put("status","uncertain");
            var grant=new EvidenceAuthority.Grant(new AuthPrincipal("tenant","owner",List.of()),"project","run","task","call","claim");
            var result=new EvidenceAdjudicator().adjudicate(grant,request,response,"assessment-current",Map.of("evidence-controlled",object("verified_observation",false),"evidence-contrary",object("verified_observation",false)));
            var decision=result.records().get(1);
            assertThat(decision.path("decision_status").asText()).isIn("contested","insufficient");
            assertThat(decision.path("gaps")).isNotEmpty();
            assertThat(result.records().get(0).path("evidence_links").get(1).path("relation").asText()).isEqualTo("refutes");
            assertThat(result.records().get(0).path("evidence_links").get(1).path("assessment_ref").asText()).isEqualTo("assessment-prior");
        }
    }
}

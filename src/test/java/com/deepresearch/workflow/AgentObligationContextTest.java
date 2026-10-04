package com.deepresearch.workflow;

import com.deepresearch.evidence.EvidenceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

class AgentObligationContextTest {
    final String question="Recommend a deployment strategy.";
    JsonNode declaration() {
        String segment=AgentQuestionSegments.mapping(question).path("segments").get(0).path("segment_id").asText();
        return object("planner_contract",AgentObligationContext.PLANNER,"claims_contract",AgentObligationContext.CLAIMS,
            "obligations",List.of(object("text",question,"kind","recommendation","segment_ids",List.of(segment),
                "applicability",object("subject","Deployment strategy","version",unknown("Not established"),"valid_at",unknown("Not established"),"conditions",List.of()))),
            "constraints",List.of());
    }
    @Test void explicitEmptyConstraintsPreserveGenuineRecommendation() {
        var adapted=AgentObligationContext.adapt(question,declaration());
        assertThat(adapted.size()).isEqualTo(1);
        assertThat(adapted.get(0).path("kind").asText()).isEqualTo("recommendation");
    }
    @Test void missingNullOrNonArrayConstraintsFailClosed() {
        for(String invalid:List.of("missing","null","text")) {
            ObjectNode wire=declaration().deepCopy();
            if(invalid.equals("missing")) wire.remove("constraints");
            else if(invalid.equals("null")) wire.putNull("constraints");
            else wire.put("constraints","invalid");
            assertThatThrownBy(()->AgentObligationContext.adapt(question,wire)).isInstanceOf(EvidenceException.class);
        }
    }
    @Test void omittedConditionsUseSchemaDefaultButExplicitInvalidValuesFail() {
        ObjectNode wire=declaration().deepCopy();
        ((ObjectNode)wire.path("obligations").get(0).path("applicability")).remove("conditions");
        assertThat(AgentObligationContext.adapt(question,wire).get(0).path("applicability").path("conditions")).isEmpty();
        for(JsonNode invalid:List.of(JSON.nullNode(),JSON.getNodeFactory().textNode("invalid"))) {
            ObjectNode changed=wire.deepCopy();((ObjectNode)changed.path("obligations").get(0).path("applicability")).set("conditions",invalid);
            assertThatThrownBy(()->AgentObligationContext.adapt(question,changed)).isInstanceOf(EvidenceException.class);
        }
    }
}

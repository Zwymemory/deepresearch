package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

class ResearchProgressContextAssemblerTest {
    static JsonNode snapshot(String run,String session,String text) {
        return object("schema_version","research-progress/1","context_kind","prior_progress",
            "trusted_as_evidence",false,"project_id","project","source_run_id",run,"source_session_id",session,
            "original_goal",text,"run_status","FAILED","completed_work",List.of(),
            "unresolved_questions",List.of(object("goal","核验争议","criteria",List.of("保留条件"),"gaps",List.of("来源缺失"))),
            "next_steps",List.of("继续检查"),"source_evidence",List.of(),"source_claims",List.of());
    }
    @Test void preservesWholeSnapshotAndLimitsCandidateWindowAndRecordCount() {
        var rows=new ArrayList<JsonNode>();
        for(int i=0;i<21;i++) rows.add(snapshot("r"+i,"old","原目标😀"));
        var projection=ResearchProgressContextAssembler.assemble("project","target",rows,row->true);
        var envelope=projection.priorProgress();
        assertThat(envelope.path("records")).hasSize(3);
        assertThat(envelope.path("records").get(0).path("snapshot")).isEqualTo(rows.get(0));
        assertThat(envelope.path("selection").path("candidate_count").asInt()).isEqualTo(20);
        assertThat(envelope.path("selection").path("eligible_count").asInt()).isEqualTo(20);
        assertThat(envelope.path("selection").path("omitted")).hasSize(17);
        assertThat(envelope.path("trusted_as_evidence").asBoolean()).isFalse();
        assertThat(projection.canonicalBytes()).isEqualTo(canonical(envelope).getBytes(StandardCharsets.UTF_8).length);
        assertThat(projection.projectionSha256()).isEqualTo(sha(canonical(envelope)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)envelope).put("project_id","mutated");
        assertThat(projection.priorProgress().path("project_id").asText()).isEqualTo("project");
    }
    @Test void skipsUnavailableOwnSessionAndOversizedWithoutTruncatingLaterSnapshot() {
        var good=snapshot("good","old","待办");
        var projection=ResearchProgressContextAssembler.assemble("project","target",List.of(
            snapshot("same","target","same"),snapshot("unavailable","old","x"),
            snapshot("large","old","中".repeat(6000)),good),row->!row.path("source_run_id").asText().equals("unavailable"));
        assertThat(projection.priorProgress().path("records")).hasSize(1);
        assertThat(projection.priorProgress().path("records").get(0).path("snapshot")).isEqualTo(good);
        assertThat(projection.canonicalBytes()).isLessThanOrEqualTo(16384);
        assertThat(projection.priorProgress().path("selection").path("omitted").toString())
            .contains("same_target_session","provenance_unavailable","record_too_large");
    }
    @Test void rejectsEmptyInvalidOrOnlyOversizedAndDoesNotBackfillCandidate21() {
        assertThatThrownBy(()->ResearchProgressContextAssembler.assemble("project","target",List.of(),r->true))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var rows=new ArrayList<JsonNode>();
        for(int i=0;i<20;i++) rows.add(snapshot("r"+i,"target","excluded"));
        rows.add(snapshot("outside-window","old","good"));
        assertThatThrownBy(()->ResearchProgressContextAssembler.assemble("project","target",rows,r->true))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->ResearchProgressContextAssembler.assemble("project","target",
            List.of(snapshot("large","old","x".repeat(20000))),r->true))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void exactUtf8BudgetAccepts16384AndRejects16385WithoutPartialJson() {
        var base=ResearchProgressContextAssembler.assemble("project","target",List.of(snapshot("r","old","")),r->true);
        int padding=(int)(16384-base.canonicalBytes());
        var exact=ResearchProgressContextAssembler.assemble("project","target",List.of(snapshot("r","old","x".repeat(padding))),r->true);
        assertThat(exact.canonicalBytes()).isEqualTo(16384);
        var below=ResearchProgressContextAssembler.assemble("project","target",List.of(snapshot("r","old","x".repeat(padding-1))),r->true);
        assertThat(below.canonicalBytes()).isEqualTo(16383);
        assertThatThrownBy(()->ResearchProgressContextAssembler.assemble("project","target",
            List.of(snapshot("r","old","x".repeat(padding+1))),r->true))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}

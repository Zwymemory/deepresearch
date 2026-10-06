package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

class ResearchRecallAssemblerTest {
    JsonNode row(String run,String goal) {
        return object("schema_version","research-progress/1","context_kind","prior_progress","trusted_as_evidence",false,
            "project_id","project","source_run_id",run,"source_session_id","old-"+run,"original_goal",goal,
            "completed_work",List.of(),"unresolved_questions",List.of(object("goal","仍须核查","criteria",List.of("同硬件"))),
            "next_steps",List.of("复核原文"),"source_evidence",List.of(),"source_claims",List.of());
    }
    @Test void relevantUnrelatedVersionAndDisputeStayUntrusted() {
        var old=(com.fasterxml.jackson.databind.node.ObjectNode)row("old","Kafka 检索延迟 v1.0");
        old.set("source_claims",JSON.valueToTree(List.of(object("decision_status","contested"))));
        var selected=ResearchRecallAssembler.assemble("Kafka 检索延迟 v2.0","new",List.of(old),r->List.of());
        assertThat(selected.path("records").size()).isEqualTo(1);
        assertThat(selected.path("records").get(0).path("applicability").toString()).contains("VERSION_DIFFERENCE","DISPUTED_OR_UNVERIFIED","unknown");
        assertThat(selected.path("trusted_as_evidence").asBoolean()).isFalse();
        assertThat(ResearchRecallAssembler.assemble("香蕉种植土壤与浇水条件","new",List.of(old),r->List.of()).path("records")).isEmpty();
        assertThat(ResearchRecallAssembler.assemble("如何研究版本条件以及相关问题","new",List.of(old),r->List.of()).path("records")).isEmpty();
    }
    @Test void inaccessibleWholeRecordsAndDuplicateNativeSourcesAreOmitted() {
        var source=object("source_key","same-original","independent_evidence",false);
        var selected=ResearchRecallAssembler.assemble("Kafka 检索延迟","new",List.of(row("a","Kafka 检索延迟"),row("b","Kafka 检索延迟")),r->List.of(source));
        assertThat(selected.path("records").size()).isEqualTo(1);assertThat(selected.path("selection").path("duplicates").asInt()).isEqualTo(1);
        assertThat(ResearchRecallAssembler.assemble("Kafka","new",List.of(row("a","Kafka")),r->null).path("records")).isEmpty();
        assertThat(ResearchRecallAssembler.assemble("Kafka","old-a",List.of(row("a","Kafka")),r->List.of()).path("records")).isEmpty();
    }
    @Test void boundedWholeSnapshotsAndTopicOnlyRanking() {
        var huge=(com.fasterxml.jackson.databind.node.ObjectNode)row("huge","Kafka 检索延迟");huge.put("user_correction","x".repeat(9000));
        var noise=(com.fasterxml.jackson.databind.node.ObjectNode)row("noise","香蕉种植");noise.put("source_run_id","Kafka-retrieval-latency");
        var out=ResearchRecallAssembler.assemble("Kafka 检索延迟","new",List.of(huge,noise,row("ok","Kafka 检索延迟")),r->List.of());
        assertThat(out.path("records").size()).isEqualTo(1);assertThat(ResearchRecallAssembler.bytes(out)).isLessThanOrEqualTo(8192);
        assertThat(out.path("selection").path("omitted").asInt()).isEqualTo(1);
    }
}

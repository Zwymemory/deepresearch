package com.deepresearch.workflow;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

class ConversationReferentsTest {
    @Test void completeExerciseAndCodeArePreservedAsUnverifiedConversation() {
        String answer="题目：请用构造器注入 PaymentGateway。\n```java\nclass OrderService {}\n```";
        var row=ConversationReferents.report(Map.of("run_id","run-1","session_id","session-1",
                "question","给我一道练习", "answer",answer),"selected_project");
        assertThat(row.path("answer").asText()).isEqualTo(answer);
        assertThat(row.path("answer_sha256").asText()).isEqualTo(sha(answer));
        assertThat(row.path("answer_truncated").asBoolean()).isFalse();
        assertThat(row.path("origin").asText()).isEqualTo("selected_project");
    }

    @Test void longUnicodeReportsKeepTheEndingExerciseWithinTheByteLimit() {
        String answer="概念开头🧪"+"说明🧪".repeat(12000)+"最后练习：请回答这个问题。";
        var row=ConversationReferents.report(Map.of("run_id","run-1","session_id","session-1",
                "question","旧问题".repeat(4000),"answer",answer),"same_session");
        assertThat(canonical(row).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(11500);
        assertThat(row.path("answer").asText()).startsWith("概念开头🧪").endsWith("最后练习：请回答这个问题。");
        assertThat(row.path("answer_truncated").asBoolean()).isTrue();
        assertThat(row.path("question_truncated").asBoolean()).isTrue();
        assertThat(row.path("answer_sha256").asText()).isEqualTo(sha(answer));
    }
}

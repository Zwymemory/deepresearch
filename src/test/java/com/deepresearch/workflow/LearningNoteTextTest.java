package com.deepresearch.workflow;

import org.junit.jupiter.api.Test;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

class LearningNoteTextTest {
    @Test void extractsExactWholeSpansWithoutCodeOrInventingMastery() {
        String answer="## 解释\n😀构造器用于接收必需的依赖，这里仅说明示例。\n```java\nclass OrderService {}\n```\n练习：创建一个 OrderService 并保留 final 字段。";
        var note=LearningNoteText.extract("这里还没理解",answer,object());
        for(var span:note.path("discussed")) assertThat(answer.substring(span.path("start").asInt(),span.path("end").asInt())).isEqualTo(span.path("text").asText());
        assertThat(note.path("has_exercise").asBoolean()).isTrue();
        assertThat(note.path("has_code").asBoolean()).isTrue();
        assertThat(note.path("open_questions").get(0).path("text").asText()).isEqualTo("这里还没理解");
        assertThat(note.toString()).doesNotContain("已掌握");
    }
    @Test void relatedTopicHasSignalAndUnrelatedTopicDoesNot() {
        assertThat(LearningNoteText.score("回到构造器注入的练习","Spring Boot 构造器注入")).isGreaterThan(2);
        assertThat(LearningNoteText.score("HTTP 缓存 ETag","Spring Boot 构造器注入")).isZero();
        assertThat(LearningNoteText.followup("那第二问怎么做")).isTrue();
        assertThat(LearningNoteText.score("先换个话题：请根据 MDN 官方网页，用一小段中文解释 HTTP 响应头 ETag 的用途。只回答这个问题，不出练习。",
                "请根据 Spring Boot 官方文档，用中文解释 Bean 和构造器依赖注入。先举生活例子，再给最小代码示例，最后出一道练习，并注明来源。")).isLessThan(3);
    }
}

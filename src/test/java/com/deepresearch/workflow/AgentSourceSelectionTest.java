package com.deepresearch.workflow;

import com.deepresearch.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AgentSourceSelectionTest {
    @Test void explicitKnowledgeInstructionWithWebOnlyIsActionableBeforeResearchStarts() {
        for (String question : List.of("请根据知识库中的 Spring Boot 官方文档解释 Bean。",
                "请仅依据知识库，说明恢复流程。", "According to the knowledge base, explain DI.")) {
            var request = new AgentCreateRequest(question,null,List.of("web_search"),null);
            var error = catchThrowableOfType(request::validateSourceSelection, ResponseStatusException.class);
            assertThat(error.getStatusCode().value()).isEqualTo(400);
            var body = new GlobalExceptionHandler().handleStatus(error).getBody();
            assertThat(body.get("error")).contains("没有启用知识库检索","尚未创建研究或调用模型");
        }
    }
    @Test void enabledKnowledgeOrMentioningKnowledgeAsATopicIsNotRejected() {
        for (String question : List.of("请根据官方网页资料解释 Bean。", "请比较知识库检索与网页检索。",
                "知识库中的资料如何创建？请搜索官方网页。", "不要根据知识库回答，请使用网页搜索。")) {
            assertThatCode(() -> new AgentCreateRequest(question,null,List.of("web_search"),null)
                    .validateSourceSelection()).doesNotThrowAnyException();
        }
        assertThatCode(() -> new AgentCreateRequest("请根据知识库回答。",null,List.of("kb_search"),null)
                .validateSourceSelection()).doesNotThrowAnyException();
        assertThatCode(() -> new AgentCreateRequest("请根据知识库回答。",null,null,null)
                .validateSourceSelection()).doesNotThrowAnyException();
    }
}

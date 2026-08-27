package com.deepresearch.service;

import com.deepresearch.web.dto.ResearchAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Week4：基于"向量库"的检索增强问答 —— RAG 在线问答的那一半。
 *
 * 和 Week2 的 RagService 对比：
 *   - Week2：去 Tavily 实时搜网页（外部搜索引擎）。
 *   - Week4：在【我们自己建的知识库】里做向量检索（语义相似度），资料来自之前 ingest 进去的内容。
 * 这才是"经典 RAG"——先离线把资料切片向量化建库，再在线按语义召回。
 */
@Service
public class VectorRagService {

    private static final Logger log = LoggerFactory.getLogger(VectorRagService.class);

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final int defaultTopK;

    public VectorRagService(VectorStore vectorStore,
                            ChatClient chatClient,
                            @Value("${deepresearch.search-top-k:5}") int defaultTopK) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.defaultTopK = defaultTopK;
    }

    public ResearchAnswer answer(String question, Integer topK) {
        int k = (topK != null && topK > 0) ? topK : defaultTopK;

        // 1. 向量检索：把问题转成向量，在 pgvector 里找最相似的 k 个分片（语义召回）
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(k).build());

        if (hits == null || hits.isEmpty()) {
            return new ResearchAnswer("知识库中没有检索到相关内容，无法作答。", List.of());
        }

        // 2. 把召回的分片编号拼成参考材料
        String context = buildContext(hits);

        // 3. 让 LLM 基于材料回答（接地 + 引用 + 不许编造），与 Week2 一脉相承
        String prompt = """
                请根据下面的【参考材料】回答用户问题。
                要求：
                1. 只能基于参考材料作答，材料没有的不要编造；不足以回答就直说。
                2. 关键论断后用 [来源N] 标注材料编号。
                3. 简洁有条理。

                【用户问题】
                %s

                【参考材料】
                %s
                """.formatted(question, context);

        String answer = chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder().temperature(0.2).build())
                .call()
                .content();

        log.debug("向量 RAG 回答完成，召回 {} 个分片", hits.size());

        // 4. 组装来源（标题取自入库时存的 metadata.title）
        List<ResearchAnswer.Source> sources = IntStream.range(0, hits.size())
                .mapToObj(i -> new ResearchAnswer.Source(
                        i + 1,
                        String.valueOf(hits.get(i).getMetadata().getOrDefault("title", "知识库片段")),
                        ""))
                .toList();

        return new ResearchAnswer(answer, sources);
    }

    private String buildContext(List<Document> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Document d = hits.get(i);
            sb.append("[来源").append(i + 1).append("] ")
                    .append(d.getMetadata().getOrDefault("title", "")).append("\n")
                    .append(d.getText()).append("\n\n");
        }
        return sb.toString().trim();
    }
}

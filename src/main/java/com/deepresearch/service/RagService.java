package com.deepresearch.service;

import com.deepresearch.model.SearchHit;
import com.deepresearch.tool.TavilySearchClient;
import com.deepresearch.web.dto.ResearchAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Week2 核心：检索增强问答（RAG 的最小闭环）。
 *
 * 流程：用户问题 → Tavily 检索 → 把结果编号拼成"参考材料" → 让 LLM 基于材料回答并标 [来源N]。
 *
 * 这是 RAG 的"在线检索"阶段的极简版（召—生），还没做切分/向量库（用实时搜索代替建库），
 * 但"基于检索材料回答 + 引用溯源 + 抗幻觉约束"这套核心思想已经齐了。
 * Week3 会把这里的"一次检索"升级成"多轮 ReAct 循环"。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final TavilySearchClient searchClient;
    private final ChatClient chatClient;
    private final int defaultTopK;

    public RagService(TavilySearchClient searchClient,
                      ChatClient chatClient,
                      @Value("${deepresearch.search-top-k:5}") int defaultTopK) {
        this.searchClient = searchClient;
        this.chatClient = chatClient;
        this.defaultTopK = defaultTopK;
    }

    public ResearchAnswer answer(String question, Integer topK) {
        int k = (topK != null && topK > 0) ? topK : defaultTopK;

        // 1. 检索
        List<SearchHit> hits = searchClient.search(question, k);
        if (hits.isEmpty()) {
            return new ResearchAnswer("没有检索到相关资料，无法作答。", List.of());
        }

        // 2. 把检索结果编号拼成参考材料
        String context = buildContext(hits);

        // 3. 让 LLM 基于材料回答（强约束：只能用材料、必须引用、不许编造）
        String prompt = """
                请根据下面提供的【参考材料】回答用户的问题。

                要求：
                1. 只能基于参考材料作答，材料中没有的内容不要编造；若材料不足以回答，请直接说明。
                2. 在每个关键论断后用 [来源N] 标注依据的材料编号，N 对应材料序号。
                3. 回答简洁、有条理。

                【用户问题】
                %s

                【参考材料】
                %s
                """.formatted(question, context);

        String answer = chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder().temperature(0.2).build()) // 低温保严谨
                .call()
                .content();

        log.debug("RAG 回答完成，引用 {} 条来源", hits.size());

        // 4. 组装来源列表
        List<ResearchAnswer.Source> sources = IntStream.range(0, hits.size())
                .mapToObj(i -> new ResearchAnswer.Source(i + 1, hits.get(i).title(), hits.get(i).url()))
                .toList();

        return new ResearchAnswer(answer, sources);
    }

    /** 把检索结果拼成带编号的参考材料文本 */
    private String buildContext(List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sb.append("[来源").append(i + 1).append("] ")
                    .append(h.title()).append("\n")
                    .append("URL: ").append(h.url()).append("\n")
                    .append("内容: ").append(h.content()).append("\n\n");
        }
        return sb.toString();
    }
}

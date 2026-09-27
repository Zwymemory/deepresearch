package com.deepresearch.service;

import com.deepresearch.web.dto.HybridDebugResponse;
import com.deepresearch.web.dto.ResearchAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 混合检索 RAG 的业务编排层。
 *
 * 输入是用户问题与可选历史，输出是带来源的回答或完整调试快照。召回、RRF、重排、
 * 邻接扩展、上下文装配和 DTO 映射分别委托给专用协作者；本类只定义流水线次序与降级出口。
 */
@Service
public class HybridRagService {

    private static final Logger log = LoggerFactory.getLogger(HybridRagService.class);

    private final HybridRetrievalOrchestrator retrievalOrchestrator;
    private final RrfFusionService fusionService;
    private final RerankCoordinator rerankCoordinator;
    private final LexicalAnchorGuard lexicalAnchorGuard;
    private final ContextExpansionService contextExpansionService;
    private final RagContextAssembler contextAssembler;
    private final HybridDebugResponseAssembler debugResponseAssembler;
    private final QueryRewriteService queryRewriteService;
    private final ChatClient chatClient;
    private final int defaultTopK;
    private final int candidateTopK;
    private final int rerankCandidateTopK;

    public HybridRagService(HybridRetrievalOrchestrator retrievalOrchestrator,
                            RrfFusionService fusionService,
                            RerankCoordinator rerankCoordinator,
                            LexicalAnchorGuard lexicalAnchorGuard,
                            ContextExpansionService contextExpansionService,
                            RagContextAssembler contextAssembler,
                            HybridDebugResponseAssembler debugResponseAssembler,
                            QueryRewriteService queryRewriteService,
                            ChatClient chatClient,
                            @Value("${deepresearch.search-top-k:5}") int defaultTopK,
                            @Value("${deepresearch.hybrid-candidate-top-k:20}") int candidateTopK,
                            @Value("${deepresearch.rerank.candidate-top-k:20}") int rerankCandidateTopK) {
        this.retrievalOrchestrator = retrievalOrchestrator;
        this.fusionService = fusionService;
        this.rerankCoordinator = rerankCoordinator;
        this.lexicalAnchorGuard = lexicalAnchorGuard;
        this.contextExpansionService = contextExpansionService;
        this.contextAssembler = contextAssembler;
        this.debugResponseAssembler = debugResponseAssembler;
        this.queryRewriteService = queryRewriteService;
        this.chatClient = chatClient;
        this.defaultTopK = defaultTopK;
        this.candidateTopK = candidateTopK;
        this.rerankCandidateTopK = rerankCandidateTopK;
    }

    public ResearchAnswer answer(String question, Integer topK) {
        return answer(question, topK, List.of());
    }

    public ResearchAnswer answer(String question, Integer topK, List<String> history) {
        QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewrite(question, history);
        String searchQuestion = rewrite.rewrittenQuestion();
        int finalK = positiveOrDefault(topK, defaultTopK);
        int recallK = Math.max(candidateTopK, finalK * 4);

        HybridRetrievalOrchestrator.RetrievalResult retrieval =
                retrievalOrchestrator.retrieve(searchQuestion, recallK);
        List<HybridChunk> fused = fusionService.fuse(
                retrieval.vectorHits(), retrieval.keywordHits(), Math.max(rerankCandidateTopK, finalK));
        var lexicalAnchor = lexicalAnchorGuard.select(searchQuestion, retrieval.keywordHits());
        List<HybridChunk> guardedCandidates = lexicalAnchorGuard.ensureCandidate(
                fused, Math.max(rerankCandidateTopK, finalK), lexicalAnchor);
        List<HybridChunk> direct = lexicalAnchorGuard.ensureTopK(
                rerankCoordinator.rerank(searchQuestion, guardedCandidates).chunks(), finalK, lexicalAnchor);
        if (direct.isEmpty()) {
            return new ResearchAnswer("知识库中没有检索到相关内容，无法作答。", List.of());
        }

        List<HybridChunk> expanded = contextExpansionService.expand(direct);
        ContextPackingService.PackedContext packed = contextAssembler.pack(searchQuestion, expanded);
        String answer = callAnswerModel(question, searchQuestion, contextAssembler.buildModelContext(packed));

        log.debug("混合 RAG 完成：vectorHits={}, keywordHits={}, fusedCandidates={}, finalChunks={}, packedEvidences={}",
                retrieval.vectorHits().size(), retrieval.keywordHits().size(), guardedCandidates.size(), expanded.size(),
                packed.evidences().size());
        return new ResearchAnswer(answer, contextAssembler.sources(packed));
    }

    public HybridDebugResponse debug(String question, Integer topK) {
        return debug(question, topK, null, null, List.of());
    }

    public HybridDebugResponse debug(String question,
                                     Integer topK,
                                     Integer recallKOverride,
                                     Integer candidateKOverride) {
        return debug(question, topK, recallKOverride, candidateKOverride, List.of());
    }

    /** 只执行检索流水线，不调用回答模型，用于排序诊断和离线评测。 */
    public HybridDebugResponse debug(String question,
                                     Integer topK,
                                     Integer recallKOverride,
                                     Integer candidateKOverride,
                                     List<String> history) {
        QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewrite(question, history);
        String searchQuestion = rewrite.rewrittenQuestion();
        int finalK = positiveOrDefault(topK, defaultTopK);
        int recallK = positiveOrDefault(recallKOverride, Math.max(candidateTopK, finalK * 4));
        int candidateK = positiveOrDefault(candidateKOverride, Math.max(rerankCandidateTopK, finalK));

        HybridRetrievalOrchestrator.RetrievalResult retrieval =
                retrievalOrchestrator.retrieve(searchQuestion, recallK);
        List<HybridChunk> fused = fusionService.fuse(retrieval.vectorHits(), retrieval.keywordHits(), candidateK);
        var lexicalAnchor = lexicalAnchorGuard.select(searchQuestion, retrieval.keywordHits());
        List<HybridChunk> guardedCandidates = lexicalAnchorGuard.ensureCandidate(fused, candidateK, lexicalAnchor);
        RerankCoordinator.Outcome rerank = rerankCoordinator.rerank(searchQuestion, guardedCandidates);
        List<HybridChunk> direct = lexicalAnchorGuard.ensureTopK(rerank.chunks(), finalK, lexicalAnchor);
        List<HybridChunk> expanded = contextExpansionService.expand(direct);
        ContextPackingService.PackedContext packed = contextAssembler.pack(searchQuestion, expanded);

        return debugResponseAssembler.assemble(
                question,
                recallK,
                finalK,
                rewrite,
                retrieval,
                fusionService.simpleMerge(retrieval.vectorHits(), retrieval.keywordHits(), finalK),
                guardedCandidates,
                rerank,
                direct,
                fusionService.dedupeByDoc(guardedCandidates).stream().limit(finalK).toList(),
                fusionService.dedupeByDoc(rerank.chunks()).stream().limit(finalK).toList(),
                expanded,
                packed
        );
    }

    private String callAnswerModel(String originalQuestion, String rewrittenQuestion, String context) {
        String prompt = """
                请根据下面的【参考材料】回答用户问题。
                要求：
                1. 只能基于参考材料作答，材料没有的不要编造；不足以回答就直说。
                2. 关键论断后用 [来源N] 标注材料编号。
                3. 简洁有条理。
                4. 若材料中出现专有名词、版本号、接口名或编号，请优先尊重材料原文。
                5. 如果【检索改写问题】和【原始用户问题】不同，说明原始问题存在指代，请按【检索改写问题】理解用户意图。
                6. 若材料只证明所问对象未部署、不存在或没有所求信息，明确写“无法基于参考材料回答”，
                   并在该判断后引用最直接的边界证据；不要推测具体值。
                7. 若用户索取私人联系方式或密钥原文，而材料说明不包含这些内容，以“无法提供”明确拒答，
                   并引用该信息边界；不要猜测、补全或输出敏感值。

                【原始用户问题】
                %s

                【检索改写问题】
                %s

                【参考材料】
                %s
                """.formatted(originalQuestion, rewrittenQuestion, context);
        return chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder().temperature(0.2).build())
                .call()
                .content();
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }
}

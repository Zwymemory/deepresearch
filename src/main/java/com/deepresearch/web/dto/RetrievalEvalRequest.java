package com.deepresearch.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 检索评测请求。
 *
 * cases 为空时使用内置评测集；传入 cases 时可评估自定义知识库。
 *
 * expectedChunkKeys 用于项目自建数据的 chunk 级严格命中；
 * expectedDocKeys 用于 BEIR/SciFact 这类公开检索集的 doc 级命中。
 */
public record RetrievalEvalRequest(
        String dataset,
        Integer topK,
        Integer recallK,
        Integer candidateK,
        List<Case> cases
) {
    public RetrievalEvalRequest(String dataset, Integer topK, List<Case> cases) {
        this(dataset, topK, null, null, cases);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Case(
            String id,
            String question,
            String expectedTitle,
            List<String> expectedTerms,
            List<String> expectedChunkKeys,
            List<String> expectedDocKeys,
            String type,
            String difficulty
    ) {
    }
}

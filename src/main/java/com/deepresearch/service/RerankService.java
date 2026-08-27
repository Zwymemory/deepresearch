package com.deepresearch.service;

import com.deepresearch.model.RerankCandidate;
import com.deepresearch.model.RerankResult;

import java.util.List;

/**
 * W4.3：Re-rank 精排抽象。
 *
 * 当前实现通过 HTTP 调 Python FastAPI cross-encoder 服务。
 * HybridRagService 只依赖该接口，便于后续替换成本地模型、云服务或规则 reranker。
 */
public interface RerankService {

    boolean enabled();

    List<RerankResult> rerank(String question, List<RerankCandidate> candidates);
}

package com.deepresearch.model;

/**
 * cross-encoder reranker 返回的一条精排结果。
 *
 * @param id    候选分片 ID
 * @param score 相关性分数，越大越相关
 */
public record RerankResult(String id, double score) {
}

package com.deepresearch.model;

/**
 * 送给 cross-encoder reranker 的候选分片。
 *
 * @param id      分片 ID，用于把 reranker 返回结果映射回原候选
 * @param title   标题
 * @param content 分片正文
 */
public record RerankCandidate(String id, String title, String content) {
}

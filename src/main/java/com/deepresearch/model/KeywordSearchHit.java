package com.deepresearch.model;

/**
 * 关键词检索召回的一条结果。
 *
 * @param id      分片 ID，和 pgvector 里的 Document id 对齐，用于 RRF 去重
 * @param title   分片所属资料标题
 * @param content 分片正文
 * @param docId   文档 ID
 * @param chunkId 分片 ID
 * @param filename 文件名
 * @param sectionPath 章节路径
 * @param chunkIndex 分片序号
 * @param pageNumber PDF 页码
 * @param score   Elasticsearch BM25 相关性分数
 */
public record KeywordSearchHit(String id, String title, String content, String docId, String chunkId,
                               String filename, String sectionPath, Integer chunkIndex,
                               Integer pageNumber, double score) {
}

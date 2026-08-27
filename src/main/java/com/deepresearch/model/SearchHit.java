package com.deepresearch.model;

/**
 * 一条检索结果（已从 Tavily 原始响应清洗）。
 *
 * @param title   网页标题
 * @param url     网页链接（用于引用溯源）
 * @param content 与查询相关的正文片段（Tavily 已帮我们抽好）
 * @param score   相关性分数（Tavily 返回，0~1）
 */
public record SearchHit(String title, String url, String content, double score) {
}

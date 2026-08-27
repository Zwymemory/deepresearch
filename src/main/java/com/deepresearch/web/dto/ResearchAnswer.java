package com.deepresearch.web.dto;

import java.util.List;

/**
 * 检索增强问答响应。
 *
 * @param answer  带 [来源N] 引用标记的回答
 * @param sources 引用来源列表（N 对应 answer 里的标记）
 */
public record ResearchAnswer(String answer, List<Source> sources) {

    /**
     * 单条来源。
     *
     * @param index 序号（对应回答里的 [来源N]）
     * @param title 标题
     * @param url   链接
     */
    public record Source(int index, String title, String url) {
    }
}

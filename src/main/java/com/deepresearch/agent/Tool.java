package com.deepresearch.agent;

/**
 * Agent 可调用的工具统一抽象（Week3）。
 *
 * ReAct 主循环只依赖这个接口：模型输出 "Action: 工具名 + Action Input"，
 * 循环按名字找到对应 Tool 并 execute()，把返回值作为 Observation 喂回模型。
 *
 * 这样新增工具（计算器、网页正文抓取、数据库查询…）只需实现本接口并注册为 Bean，
 * 主循环代码一行不用改——这是"面向接口编程 + 开闭原则"在 Agent 里的体现。
 */
public interface Tool {

    /** 工具名（模型用它来选择工具，需唯一、简短、英文小写下划线，如 web_search） */
    String name();

    /** 工具说明（写进 prompt 让模型理解"这工具能干嘛、输入是什么"，描述质量直接影响模型选得准不准） */
    String description();

    /**
     * 执行工具。
     *
     * @param input 模型给出的 Action Input（自然语言或查询词）
     * @return 执行结果文本，将作为 Observation 回灌给模型
     */
    String execute(String input);
}

# Mini Benchmark 数据说明

本目录用于 DeepResearch 本地检索评测。

- `kb/`：自造企业知识库与 AI/检索概念测试语料，共 8 个 Markdown 文档、200 个结构章节，目标导入后约 200 个 chunk。
- `../eval/mini-benchmark.jsonl`：50 条问题，每条使用 `expectedChunkKeys` 做严格命中判断。
- AI/检索概念文档为项目自编摘要，只使用通用公开概念，不复制公开网页、论文或文档原文；因此仓库内测试语料可作为本项目自有 benchmark 数据使用。
- 后续若引入第三方公开数据集，需要在本文件补充数据来源、许可证和转换规则。

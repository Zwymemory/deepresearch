# Benchmark 项目研发规范

## 项目规范

### branch policy
功能开发应从 main 切出 feature 分支，完成自测后再合并。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### commit message
提交信息应包含模块、变更目的和风险提示，避免只写 update。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### api review
接口评审需要确认 URL、请求体、响应体、错误码和幂等性。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### schema change
数据库结构变更必须新增 Flyway migration，不允许直接修改已发布迁移。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### config secret
密钥配置必须放在本地覆盖文件或环境变量，不能提交到仓库。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### log level
生产环境默认 INFO，调试排序问题时可临时打开 com.deepresearch DEBUG。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### unit test
核心解析、切分、评测指标和异常分支都应有单元测试。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### integration test
涉及 PostgreSQL、Elasticsearch 和 reranker 的链路需要集成或手工验证。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### api document
新增接口需要同步 README 的 curl 示例和字段说明。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### demo script
演示脚本应能清库、入库、查询和评测，降低面试展示成本。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### dependency lock
新增依赖需要说明用途，避免为小功能引入重型框架。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### docker health
Docker 服务应提供 health check，便于启动顺序和可用性判断。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### error message
用户可见错误应说明原因，不暴露密钥、堆栈或内部路径。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### fallback design
外部服务失败时优先降级，除非该服务是当前接口的核心职责。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### observability
关键链路应记录阶段、耗时、候选数量和 fallback 原因。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### data reset
测试数据应可一键重置，避免历史脏数据影响评测指标。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### benchmark version
评测集变化应记录版本，避免不同版本指标被直接比较。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### manual verification
手工验证要记录命令、期望结果和异常处理方式。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### performance note
小型本地 benchmark 不代表生产吞吐，需要区分功能指标和性能指标。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### security review
涉及网页读取、文件解析和 API Key 的功能需要安全边界说明。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### citation standard
引用格式统一为 [来源N]，回答必须能回溯到具体 chunk。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### code style
Java 代码遵循 Controller-Service-Model 分层，避免业务逻辑堆在 Controller。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### test naming
测试方法名应描述行为和期望，便于失败时定位问题。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### release note
每周阶段完成后更新 README 和代码详解文档。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

### resume claim
简历中的指标必须来自可复现评测，不写未经验证的提升比例。 这一段是 mini benchmark 的稳定测试语料，包含明确实体、数值或流程描述，便于检索评测严格定位到该章节。

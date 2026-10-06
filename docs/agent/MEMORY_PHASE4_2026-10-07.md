# 阶段四：跨问题召回

新自主研究默认从当前租户与用户最近20份已保存进度中，以英文词/中文双字关键词基线选择最多3份相关的完整记录；仅按原目标与当次问题匹配，不按ID、哈希或状态噪声匹配。无相关记录可以为空。单独的 recalled_progress 与 recalled_progress_binding 在创建事务内冻结，额外上下文最多8192字节；M2原有最终规划输入预算仍生效。researchProjectId 显式延续的同项目记录由M1承担，不重复加入M4。暂无向量索引，实际漏召回再决定扩展。

POST /api/research/agents 新增可选布尔 memoryRecall，默认true，false用于关闭与对照。false参与请求幂等指纹，true/省略保留旧请求指纹；重放不重新选取今天的记忆。浏览器不能提交快照或哈希。旧已接受请求保持原上下文。

GET /api/research/agents/{runId}/memory-recall 只读，返回 DISABLED/EMPTY/SELECTED/USED/UNAVAILABLE。USED 必须有同一召回哈希的已结算生产规划请求绑定。每条记录给出来源项目与运行、完整快照、匹配词/分数、原文来源引用及重新核查提示；版本文本不同只标记 VERSION_DIFFERENCE，不声称已判定失效；时间与适用条件保持unknown。删除/修改/越权的冻结来源使视图UNAVAILABLE，清空旧payload，不静默换新来源。

服务JWT POST /internal/agent/memory/recall/validate 在生产规划/摘要每次调用、重试及结算请求重放前，以及活动checkpoint恢复时检查冻结hash、当前来源权限/内容/原文/争议状态、M3传递依赖与目标claim/lease/grant/deadline。检查与发送不是原子事务，不能保证撤回已经发出的请求。原文来源按kind/locator/content hash去重，排除阅读时间与不同运行生成的ID；记忆数量不代表独立证据。所有旧成果仅作调查线索，必须用新研究原文核实，不能进入当前CHECK证据或机械关闭新义务。M3自动保存只继承显式M1历史，不将M4跨项目内容伪装成当前事实。

验收覆盖：相关、无关、版本变化、争议、删除、越权；另外检查原文去重、显式延续不重复、幂等冻结、进程重启撤销、真实生产adapter输入与同问题同预算有/无记忆对照。控制模型演示只证明接线与边界；DeepSeek小样本与空检索运输需说明限制，不外推普遍质量/省钱收益。前端仅由Claude在现有第二版设计做必要小改动，中文展示状态、理由、来源与未知条件，提供原生页面截图。

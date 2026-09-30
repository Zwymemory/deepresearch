# Agent 真实联调后离线修复：A 模型诊断与 CI

状态：**离线工程门禁通过，真实联调仍未补测，阶段验收仍为部分通过。** 共同起点 `ce21f174e19389362f0dfb113d82699d60007f4e`；A 实现提交 `e76a6d8971cb5c76dd0d53f14e0a87382ff9cd10`，B 时间契约接口提交 `621d075548fdb540eaffdf4e6b7f339f6fc8d547`。最终文档提交与 `final_sha=tested_sha` 见既有本地 runtime handoff。以精确 Git 对象生成隔离组合测试，未使用 B 未提交工作树。

## 修复结果

旧模型适配器把请求编码、HTTP/超时、截断、函数/JSON 及 schema 错误统一抹为 `AGENT_MODEL_INVALID`，预算层又把所有失败反复发送并丢弃已收到的用量。现在适配器仅产生固定白名单的失败类别、可选数字 HTTP 状态和已验证的 token 数；不保留原始响应、prompt、密钥、异常正文或任意提供方错误文本。预算层另将 JSON Schema/Pydantic 错误变成安全字段路径，例如 `claims.applicability.valid_at`，并与固定类别一起写入 UNKNOWN 操作回执。已经收到的合法 token 计入实测汇总；无效模型内容没有 SETTLED 结果，不能成功重放。

| 类别 | 处理 |
| --- | --- |
| 请求编码、401/403、500、输出截断、函数缺失/名称错误、无效 JSON、schema/字段校验 | 失败回执为 UNKNOWN；已知 token 保留；同一请求不自动重试或在恢复后重发。 |
| 429、502/503/504 | 最多沿用原有的第 2 次模型准入；每次单独记账，仍受 16 模型、token 和期限上限约束。 |
| 超时或连接异常 | 是否已到达提供方无法确认，因此记 UNKNOWN、阻止自动重发；实际用量仍可能未知。 |
| 回执结算异常 | 报稳定 `AGENT_SETTLEMENT_FAILED`，不再次发送模型请求，也不假装已成功结算。 |

历史混合/版本运行的旧回执没有上述细分信息，**仍无法判定具体原因**。此修复不能倒推为已解决那两次失败。网页场景的事实有效时间问题由 B 的独立提交处理；A 不修改其 `agent_protocol.py`、`agent_runtime.py` 或 Java 时间实现。双方组合测试证明模型 schema 失败会安全终止，已解析动作中的事实时间反馈仍由 B 的观察路径处理；这只是离线验证。

CI 的 `integration` job 现在独立配置 Python 3.12，安装 workflow Python 依赖，并把实际解释器路径传给 Java 子进程的 `AGENT_PYTHON`。`FlywayMigrationIT` 严格检查 V1–V21 的顺序与既有加新增对象；`DeepResearchApplicationIT` 检查最新 V21 与完整 27 张指定表。没有跳过测试、删除断言或允许失败。旧远端 [CI run 36596771871](https://github.com/Zwymemory/deepresearch/actions/runs/36596771871) 仍是失败记录；本分支未推送，不能称远端已转绿。

## 离线验证与边界

- A 提交上的 Python 非集成测试 226 项、全量 `mvn -Pintegration verify` 通过；隔离组合（A `e76a6d8` + B `621d075`）的 Python 非集成 240 项、Java 单元 299 项、集成 96 项均零失败/错误/跳过。组合包含临时 PostgreSQL、真实 Java HTTP/JWT 路径和真实 Python 子进程，模型与外部来源均为离线 fixture。
- 受影响 Ruff、Python compileall、v0 冻结合约的 38+39 项通过；公共仓库检查扫描已提交归档通过，新增提交 Gitleaks 零命中。最终文档提交后的精确门禁结果以 runtime handoff 为准。
- 六份旧实测账本与隔离 app/DB/sidecar 原样保留；未改预算、旧运行状态或 state-dir，未新增真实模型研究、外部搜索/原文读取、上传或解析。正式服务、卷、配置与八份知识文档均未操作；用户原有未跟踪 `docs/interview/` 保留。

下一步由主审复核双方精确组合和推送后的远端 CI，再决定是否给小批真实补测额度。网页、混合、版本、冲突与无结果的真实语义验收尚未完成；在此之前不进入长期记忆或多 Agent 阶段。

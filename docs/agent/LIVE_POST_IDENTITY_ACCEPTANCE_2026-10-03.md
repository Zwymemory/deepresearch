# 模型身份修复后的真实研究验收（2026-10-03）

## 新授权与执行范围

用户明确授权补跑五个真实场景。本轮是独立新批次 `round1-post-identity-20261003`，从已复核的身份修复 `fcddde0d48b30bca4c540a74cfb45bc7ab03e610` 开始，按顺序执行 web-only、mixed、version-conditions、contradictory-material、insufficient-evidence，最多五次新研究提交、零自动顶层重跑。旧批次仍停止，旧七条终态记录、失败裁决、快照与授权哈希保持原样。

本轮明确配置请求模型 `deepseek-flash`。协调侧此前仅做过一次真实规范模型 API 最小诊断（HTTP200，333 输入／33 输出 token，无重试），这不等于研究能力验收。本轮不追加单独模型探测，不开启记忆、多 Agent、生产部署或前端修改。

## 最小批次机制扩展

- 只识别旧批次和本轮两个固定批次名。注册新授权必须读取原保护目录中的真实旧日记，核对既定七条日记字节哈希、已停止的上一批、已耗尽的旧重试；先保存字节完全相同的专属快照，再追加新授权。
- 新历史校验绑定旧七条记录与旧授权对象；旧批次校验在两批日记中仍单独识别自己的唯一记录，状态仍 STOPPED。旧非批次入口在存在批次授权后关闭，不能挪用旧额度。
- 新批次最多五条、按顺序预约，固定候选和来源清单。文件锁保证并发预约至多一次；结果不确定也消耗预约并停止。有效结论必须有独立 B 审查，绑定 run/build/audit/source hashes，才能执行下一项。
- 新 ready、运行清单、构建记录、API token 和 sidecar 日志使用独立文件；保留旧元数据。隔离环境复用已有数据库与已审核合成知识包，不新建 RAGFlow 数据集，不上传或重解析。
- 每项上限不变：8 次决策、16 次模型 admission、16 次工具 admission、180 秒、64000 输入／16384 输出 token。已有受限操作重试计入预算；研究提交本身不自动重跑。

## 验证与运行前提

新批次机制与旧历史测试共 40 例通过，覆盖原字节快照、旧停止状态、配额与元数据篡改、重复／并发预约、未知提交结果、固定顺序、准确 run/audit/source/语义裁决门禁、全部五项耗尽、新规范模型要求及旧元数据保护。身份／适配器／Agent 相关 67 个 Python 用例通过；showcase 离线检查通过。运行源码未改，完整身份修复门禁继续是基线证据。

必须先冻结提交、通过该精确 SHA 的 CI 六项 job 与独立 B 候选／来源审批，才启动专属隔离 Java／sidecar 并核对实际构建、源归档、JAR、模型和既有知识包。真实付费结果不由历史绿 CI 或离线 fixture 代替。

```sh
python3 -B -m unittest discover -s scripts/tests -p 'test_agent*.py'
PYTHONPATH=workflow-service/src python3.12 -B -m pytest workflow-service/tests/test_agent_identity.py workflow-service/tests/test_agent_model.py workflow-service/tests/test_agent_runtime.py
make showcase-check PYTHON=python3
```

## 实际结果：首项失败，后续停止

实际执行候选：`22e2b6b7ea5b29d91dcd28933dc965655bbb1826`。专用分支 `feat/agent-live-post-identity-20261003` 正常推送；[精确候选 CI 37116050021](https://github.com/Zwymemory/deepresearch/actions/runs/37116050021) 六项 job 全部成功。独立 B 代码复核通过 40 个脚本测试及基于真实七行日记副本的 32 项额外检查；实际 JAR、镜像、归档、sidecar 进程／健康、规范模型配置与既有四份资料注册绑定均通过，才批准第一项提交。

| 场景 | 本轮判定 | 实际结果 |
| --- | --- | --- |
| web-only | FAIL | FAILED / MODEL_SCHEMA_INVALID；第二次模型返回 2 个工具调用，单调用协议拒绝，研究证据链未完成 |
| mixed | NOT RUN | 首项失败后停止 |
| version-conditions | NOT RUN | 首项失败后停止 |
| contradictory-material | NOT RUN | 首项失败后停止 |
| insufficient-evidence | NOT RUN | 首项失败后停止 |

本轮 1 次新研究提交、0 次顶层重跑、0 项语义通过。运行 `wf-49fbcf15-afb4-4a8c-be63-ad1bfcbacddc`；实际等待／执行窗口约 11 秒。B 针对该 run/build/audit/source hashes 的独立判定为 fail，已写入日记；新批次 STOPPED / SEMANTIC_REVIEW_FAILED_OR_INCOMPLETE。对 mixed 的只读准入检查明确拒绝，未尝试下一次付费调用。

### 已证明的链路与失败归因

两次真实模型请求都收到 HTTP200，规范请求／返回身份均为 `deepseek-flash`，`accepted_canonical`；模型身份修复在本次两条真实响应上有效。第一次决策成功选择 web_search，该工具完成真实搜索并返回 5 个候选，包含所需 IANA 地址。

第二次决策返回的 `tool_calls` 数量为 2。适配器在消费具体函数与参数之前按既有单调用契约拒绝，安全分类 `SCHEMA/function_count`、`retryable=false`；账本为 UNKNOWN，结果不可用。原始被拒绝的函数名称与参数未保留，不能恢复或推定它们。这个证据定位到多工具调用响应与单决策接口之间的协议不符，不能推断 API 完全不可用、TUN 故障或 RAGFlow 检索故障。

原问题与持久化问题／run ID 完全一致。数据库保存 2 次模型 admission、1 次工具 admission、1 项任务及标准；原文读取回执、check、采用的原文 Evidence、Claim／DecisionRecord、publication 均为 0，最终回答与引用为空。因此网页候选或结构完整不能证明题目事实与保障范围已完成核验，也不能把此失败记为“证据不足场景通过”。本次未调用 RAGFlow，不能对其真实质量下结论。

### 实际用量与不可变证据

| 调用 | 账本状态 | 输入 token | 输出 token |
| --- | --- | ---: | ---: |
| 第一次 DECISION | SETTLED | 2627 | 103 |
| 第二次 DECISION | UNKNOWN，结果不可用 | 3863 | 229 |
| 本轮合计 | 2 模型、2 决策、1 工具 | 6490 | 332 |

输入／输出用量均已知，无缺失或 inflight；金额与 provider 账单未知。前置单 API 诊断的 333／33 不计入本表。计数、时间和 token 均未超过本轮上限；`UnavailableModelResultOrBudgetViolation` 在这里由结果不可用触发，没有观察到预算耗尽。

- 不可覆盖审计 SHA-256：`5d99a88a24ef631a635e96fac8dfdaec4653c77ab79d0270ebfea6e0b81e9c9e`。
- 不可覆盖 B 审查文件 SHA-256：`5f63ccaa72dade8008fd6f60e53a74f980f2a2822dd8121e22bc1f855624d348`；共享判定 digest：`a6a1f128a5d477ae085a7fe34abf319d4f6857ab0f47b2e7237e0e6c5befacb7`。
- 捕获的搜索回执 SHA-256：`212b2d78b0d0610edc679d128f61362e15d5f7a5a164dfad35f6b34f3612cc68`。采用的原文 source_hashes 为空，因为没有进入 read_source；搜索候选和摘要不能代替原文与引用。
- 新授权前旧日记字节快照 SHA-256：`de67f8017fa017f96e5e305ba9371673bfe3ca61b878ab3b544dd533eb53eea9`。旧七行、旧 STOPPED 授权对象及 13 个非日记保护文件未变。日记仅追加新授权、实际一行和绑定的独立判定，现共八行。

原审计、实际响应身份／用量回执、来源候选与持久账本在受保护目录保存，不提交原文、密钥或 token。最终命令和哈希检查在外部运行回执及忽略目录中的 final-results 记录中，可按精确候选追溯。

### 离线复现与后续边界

从固定候选归档运行 HTTPX MockTransport，两条合成函数调用复现同样的 function_count2 / MODEL_SCHEMA_INVALID：仅 1 次 mock 发送／admission／UNKNOWN 结算，规范身份接受，合法用量保存，不自动重试。这只复现已观察到的调用数量与分类路径，不恢复真实被拒绝函数的内容，也不发起外部请求。

准备阶段第一次本地端口探测在旧 Docker 代理释放期间立即失败；核对无 listener 且两个端口可绑定后恢复同一固定准备流程。此时尚无新 ready、授权或研究提交，未修改候选／上限，也没有付费研究重跑。两次准备日志均保留。生产环境、原密钥、前端、知识包未改；仅专属隔离应用替换候选并复用原数据库／卷。

下一阶段应先核实 provider 的单工具调用约束与多调用响应政策，离线修复／验证单决策协议，再由协调侧决定新的受限真实验收。不能丢弃额外调用或把 UNKNOWN 结果算成功来掩盖问题。本批次不修复后重跑，不花未用额度，不开启记忆、多 Agent 或生产部署。整体 Round 1 目前没有验收通过。

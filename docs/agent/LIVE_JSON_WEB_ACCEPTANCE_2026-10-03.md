# 单 JSON 决策修复后的 web-only 真实验收（2026-10-03）

## 新授权与历史边界

用户授权一次完整原题研究，批次 `round1-json-web-20261003`：仅 web-only，一次提交、零顶层重跑。原题、来源 manifest 和每次运行的 8 决策/16 模型/16 工具/180 秒/64000 输入/16384 输出上限保持不变。mixed 与其余三个场景尚未放行。

起点为报告提交 e30e376；生产运行时代码保持独立审查通过的 46bb238。新增代码只涉及命名额度、验收准备/收据/审查绑定及离线测试。旧八条记录和两个 STOPPED 批次不能重开。新授权必须从原 journal 的精确字节 SHA-256 `85466bf90171915ade72dcd58156804412cd9623ae8b42693a7063fc575551f6` 创建独占历史快照，之后只追加一条研究记录。

## 准备与模式绑定

明确选择 `--model-name deepseek-flash --agent-result-transport deepseek_json_object`，传输契约 `agent-result-wire/1`。实际 sidecar 身份与 B 的 `candidate_binding.result_transport`、`transport_contract_version` 均需吻合。每次调用观察器记录实际 wire 摘要/字节数/模式/是否匹配，不保存提示词或拒绝输出；配置与 wire 模式不一致会在发出请求前拒绝。

准备顺序：固定提交和相关离线测试 → 专用分支正常发布 → exact-SHA 六项 CI → 不可变源码/JAR/sidecar 核验 → 只重建已有自有隔离服务且保留数据库/volume → B 明确放行实际候选及运行配置 → 一次原题提交 → 持久化原文/核查/引用/用量审计 → B 绑定语义裁决。

离线单槽用例覆盖容量、并发/重复、未知请求结果、历史链、错误场景/候选/模式、语义哈希绑定及停止规则。模型适配器/身份/JSON 结果相关测试使用 MockTransport，不能代替真实研究验收；此前离线修复结论仍单独保留。

## 当前状态

候选固定、CI、实际运行与独立语义结论在执行后记录。此准备说明不宣称真实成功，也不以 HTTP200、JSON 有效或终态 SUCCEEDED 代替对原文和引用链的核验。

只使用原 manifest 的 web-only 原题：核查 IANA example.com/example.org 的注册/转移限制和 HTTP 服务的尽力提供、非生产依赖边界。不得伪造生效时间，来源发布/抓取日期不等同于事实有效时间；不得把搜索摘要当作已读取原文。

新 ready/run/review/build/token/log 路径与旧记录分开，历史绑定资料不覆盖。无 API 小探针、数据集上传、生产环境/密钥/前端改动或 memory/multi-agent 工作。失败或结果不确定即停止，禁止修完沿用同一额度重跑。

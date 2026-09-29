# 第 1 轮主审：自主研究与证据裁决

日期：2026-09-29。结论：需要修复后复审，暂不进入第 2 轮研究记忆。

## 审查对象

| 对象 | 精确提交 |
| --- | --- |
| A：动态主 Agent、运行控制与发布桥接 | `658e3411deec6029fb7fe054b94d3be4652baab1` |
| B：原文读取、证据裁决与存储 | `9e606b16302cbddcc02d2e8763002bbcc73bfe19` |
| 主对话合并并测试的候选 | `da3bc455b478f7b6a96f717271107a705b6bd5f1` |
| 集成分支 | `feat/agent-round1-reviewed` |

合并无冲突；原候选分支保留。本文之后的文档提交不代表修复了这些问题。此候选没有部署到当前演示，也没有推送到 GitHub。

## 已完成的有效增量

- 新入口实际进入动态主 Agent 循环，能按观察选择检索、读原文、核查和修订计划。
- 模型、核查、工具及发布时的知识库重读共享持久化预算；取消、租约和未知结果有控制边界。
- 证据原文、精确引用、结论范围和裁决可以落库；Java 为已核查正文保存发布证明。
- 这仍是单主 Agent 候选。长期研究记忆、摘要维护 worker 和多 Agent 委派没有在本轮实现。

## 主对话独立复验

| 检查 | 结果 | 证明边界 |
| --- | --- | --- |
| Python 新运行图/模型/证据协议及旧 runner/图/引用/HTTP/repository | 136 项通过 | 实际执行代码，模型和来源为替身 |
| Java 控制层、来源、引用及既有工具单元测试 | 75 项通过，无失败或跳过 | 所选单元路径 |
| Java 临时 PostgreSQL 集成测试 | 22 项通过，无失败或跳过 | 真实 V1–V19 迁移、服务、存储、发布和既有 MCP transport |
| 上一行内嵌的 Python SQL/恢复检查 | 7 项通过，包含在桥接测试中 | 真实临时数据库、角色和连接；不是额外 7 个 Java 测试 |
| 冻结 v0 联合入口 | 38 + 39 项及 7 个跨线负例通过 | 冻结合约与既有夹具，不能证明模型语义能力 |

测试命令：

```sh
# 在 workflow-service 执行
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=src .venv/bin/python -B -m pytest \
  tests/test_agent_runtime.py tests/test_agent_model.py tests/test_evidence_client.py \
  tests/test_evidence_check.py tests/test_evidence_acceptance.py tests/test_runner.py \
  tests/test_domain.py tests/test_graph.py tests/test_citation_mapping.py \
  tests/test_query_fidelity.py tests/test_http_contracts.py tests/test_repository.py -q

# 在仓库根目录执行；AGENT_PYTHON 指向本项目 Python 3.12 虚拟环境
AGENT_PYTHON="$PWD/workflow-service/.venv/bin/python" PYTHONDONTWRITEBYTECODE=1 \
  mvn -q --offline -Pintegration -DskipTests=false \
  -Dtest=DifyContextInputsTest,WorkflowServiceTest,WorkflowAccessServiceTest,WorkflowMcpReceiptServiceTest,SafeWebReaderTest,EvidenceQuoteTest,EvidenceConfigurationTest,DifyCitationValidatorTest,DifyToolServiceTest,RagflowClientTest,RagflowDifyKbToolGatewayTest,TavilySearchClientTest \
  -Dit.test=AgentRuntimePostgresIT,McpKnowledgeLoopIT,EvidenceServiceIT,DifyWebSourcesIT verify
PYTHONDONTWRITEBYTECODE=1 workflow-service/.venv/bin/python -B contracts/agent/v0/validate.py
```

## 必须修复的发现

### R1：补查遗漏旧反证后，争议可被误当作已解决（P1）

首轮同一结论有支持与反对材料而处于 contested；补查只带支持材料，后续核查便可以产生 supported。旧 Challenge 未关闭，发布路径仍可为新 supported 结论生成证明。交叉审查已通过真实证据服务与 A 发布控制层复现。

涉及 `EvidenceService.prepare/packet/publish`、`JdbcEvidenceStore.prepare` 和 A 的证据选择。核查轮次应沿用同一调查的相关反证，并记录每份旧反证被排除或争议被解决的依据；达到单次材料上限时应保留缺口，不能静默截掉反证。仅重试或生成新 check ID 不能使争议消失。

验收：两条同范围相反材料先得到 contested；补查仅提交旧支持材料、新增重复支持材料或省略旧反证，均不能得到完整成功发布。加入确有依据的范围澄清后，历史记录仍可追溯。

### R2：混合结果只发布支持部分，却把整个研究标为成功（P1）

同一 packet 中既有 supported 也有 contested/insufficient 结论时，`HttpEvidenceBackend.publish` 只选择 supported Claim；B 的 publisher 校验所选结论，A 的运行图收到 approved 就设置 SUCCEEDED。其他争议、缺口与未完成目标从最终回答消失。交叉审查已通过隔离测试复现。

验收：完成状态依据研究整体范围；未解决部分必须出现在最终报告和可读状态中。可以安全发布已支持的部分，但不能将它标为完整成功，不能从最终结果中隐藏 refuted/contested/insufficient。服务端发布证明须继续绑定最终实际展示的内容与引用。没有支持结论时仍能生成确定的缺口报告。

### R3：独立子问题和失败响应会破坏已有裁决（P2）

`HttpEvidenceBackend.check` 将 claim_specs、check_id、dispute_round 挂在全 run 唯一 packet 上，第二个独立 task 的新 Claim 被误判为 CLAIM_SCOPE_CHANGED。`AutonomousResearchGraph.act` 又无条件用失败响应覆盖 packet。

主对话在合并候选上调用真实 `act` 和 HTTP 证据适配器复现：原 packet 含 contested Claim；新子问题触发上述拒绝后，返回状态的 packet_id 和 records 均丢失。该复现使用替身 ledger/repository，未调用模型或网络。

验收：不同子问题分别持有调查身份与补查轮次，同一调查不能改范围来绕过上限；拒绝、超时和模型失败只追加观察，不删除已完成的裁决。多子问题最终发布要覆盖整个研究范围。

### R4：重复搜索同一来源，会使此前合法证据失去发布资格（P2）

`AgentEvidenceAuthority.candidate` 总是取最新搜索回执；B 发布时又将该 candidate（包含 parentReceiptId 等）与读取时完整保存的 candidate 比较。因此同一网页/知识分片被后续查询再次命中，即使原文未变，也会触发 PUBLICATION_SOURCE_IDENTITY_CHANGED。交叉审查已复现。

验收：读取与发布绑定当时完成的确切搜索回执，后续同来源搜索不使既有证据失效；其他 run/owner 的回执仍必须被拒绝，知识库发布前的原文重读和变化检测继续保留。来源定位、回执身份、Evidence 身份和引用编号需分开处理。

### R5：两端引文空白规则不同，合法段落核查在后半程失败（P2）

原文段落前后含 U+00A0、U+0085、U+2007 或 U+202F，模型引文仅省略这些边界空白时，Python `parse_verifier_response` 接受，Java `EvidenceAdjudicator.bindQuote` 则拒绝为 CHECK_QUOTE_CONTEXT_INCOMPLETE。交叉审查对四种字符均复现。模型响应可能已结算，随后的服务端 complete 仍失败并占用额度。

验收：明确并共用相同的边界空白集合；两端对相同文本、code point 范围和 UTF-8 hash 给出一致结论，同时继续拒绝删掉段落中的限定词或伪造引文。保留当前失败关闭边界。

## 审查资料

两次交叉审查均使用精确已提交版本，在 ignored target 资料包中做定向复现；未修改实现或正式服务。其结果用于确认上述缺陷，不能计作真实模型验收。主对话独立运行联合门禁，并另行复现 R3。

- 本地 A → B 记录：`../.codex-handoffs/deepresearch-agent-round1-review-a.md`。
- 本地 B → A 记录：`../.codex-handoffs/deepresearch-agent-round1-review-b.md`。

主报告编号 R1–R5 为修复统一编号；交叉报告内部编号各自独立。

## 尚未获得的证明

- 真实模型对实际资料的判断能力、真实公网来源读取，以及完整 Agent HTTP/JWT 端到端运行尚未联合验收。
- 受控实验观察在 B 的测试中可注入；A 当前没有实现对应生产适配器。不能据此宣称已能实际执行实验来裁决错误文档。普通来源冲突应继续保留争议。
- token/cost 缺失时仍为 unknown；兼容字段 `maxCostCny` 不是已实现的货币费用硬限制。

## 下一阶段顺序

先修复本轮的证据保留、发布完整性与来源绑定问题；主对话再合并复验及执行小规模真实验收。通过后，才将经过核查的研究成果接入长期记忆，再安排多 Agent 协作。

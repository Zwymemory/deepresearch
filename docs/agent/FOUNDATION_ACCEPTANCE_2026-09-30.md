# Agent 统一基础：本地工程验收通过

日期：2026-09-30。主审结论：本轮 N1–N4、F1、F2 已完成修复并通过指定本地联合验收，未发现新的阻断问题。结束本轮基础修复，可以进入固定候选的小规模真实模型与来源联调。

这不代表整套 GitHub CI 已在远端执行，也不代表真实模型质量、公网来源、长期记忆或多 Agent 已验收。

## 精确候选

| 对象 | 提交 |
| --- | --- |
| A 整份报告状态证明 | `87c3c58dd9c94d0b5a2ecbb65981410d8f647b68` |
| B Python 静态门禁 | `848d8844c38b10825d03357cd16a5e51a6f36936` |
| 主审合并并独立复验的实现 | `2856e0609169b077cdf44e6b6e5e5c627598a960` |
| 本地分支 | `feat/agent-foundation-verified` |

两方已停止编辑，final_sha=tested_sha，且 tested_peer_sha 相互对应。主审合并无冲突，没有修改实现以帮助测试通过。旧分支、用户文件和历史复现保留；未部署、未 push、未合并 main、未修改正式服务/配置/知识库。

## 主审独立验证

| 检查 | 结果 |
| --- | --- |
| Python 全部非集成测试 | 211 通过，0 失败/错误/跳过 |
| 所选 Java 单元测试 | 156 通过，0 失败/错误/跳过 |
| 所选 PostgreSQL/接口集成测试 | 70 通过，0 失败/错误/跳过 |
| 集成构成 | Runtime 24、HTTP 7、Evidence 31、MCP 4、DifyWeb 4 |
| Python 静态检查 | 0 诊断，原 49 项已清零 |
| Python 编译检查 | 通过 |
| v0 冻结检查 | 38+39 与跨线门禁通过 |

70 项内包括 7 项完整 Spring 随机端口 HTTP/JWT 回归；Runtime 中一个 wrapper 还执行 7 项 Python SQL/恢复测试，不重复加总。Java 仅计本次指定类，未把旧 XML 算入本次结果。模型回执、来源传输保持替身，数据库/迁移/身份/预算/服务端裁决和发布控制实际执行。

命令与日志：

```sh
# workflow-service
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=src .venv/bin/python -B -m pytest tests -m 'not integration' -q --junitxml=../target/root-foundation-python.xml

# 仓库根目录
AGENT_PYTHON="$PWD/workflow-service/.venv/bin/python" PYTHONDONTWRITEBYTECODE=1 \
  mvn -o -q -Pintegration -DskipTests=false \
  '-Dtest=Workflow*Test,Agent*Test,*Auth*Test,DifyContextInputsTest,SafeWebReaderTest,Evidence*Test,DifyCitationValidatorTest,DifyToolServiceTest,RagflowClientTest,RagflowDifyKbToolGatewayTest,TavilySearchClientTest' \
  -Dit.test=AgentRuntimePostgresIT,AgentHttpPostgresIT,McpKnowledgeLoopIT,EvidenceServiceIT,DifyWebSourcesIT verify
workflow-service/.venv/bin/ruff check workflow-service/src workflow-service/tests
PYTHONPYCACHEPREFIX="$PWD/target/root-foundation-pycache" workflow-service/.venv/bin/python -m compileall -q workflow-service/src workflow-service/tests
PYTHONDONTWRITEBYTECODE=1 workflow-service/.venv/bin/python -B contracts/agent/v0/validate.py
```

日志为 `/tmp/deepresearch-root-foundation-python.log`、`/tmp/deepresearch-root-foundation-java.log`、`/tmp/deepresearch-root-foundation-freeze.log`；XML 在 target/root-foundation-python.xml、target/surefire-reports、target/failsafe-reports。

## 收尾问题的核验

F1：研究状态证明现已覆盖未绑定标准的调查、所有检查/父链/结果、保留缺口、来源记录与读取回执、当前调查操作及目标完成性。报告生成、封存、旧出版重放和最终提交均匹配当前状态；出版本身的预留和来源复核账目不使证明自行失效。

新增维护回归实际覆盖 pending/failed/UNKNOWN/contested 使旧报告失效、报告生成和最终封存两个变化窗口、生成前的失败/未知缺口、部分报告和无变化报告的正常封存/精确重放。拒绝最终提交的测试明确先进入合法 FINALIZING，再因发布证明变化失败并回滚，避免用错误阶段产生的拒绝冒充修复。旧证据与旧封存记录保留。

F2：静态检查规则、依赖和 CI 定义未放宽；生产改动为格式、导入和保持相同运行时文本的字面量表示。两处异步 fixture 文件读取移到工作线程，原测试继续执行。主审重新跑 ruff、compileall、全部非集成及相关 PG/HTTP 门禁均通过。

上轮 N1–N4 的完成标准逐项覆盖、共享调查/依赖失效、多调查来源复核和矛盾条件保留回归也包含在本次组合。此前失败报告是历史审查记录，本文件是这些问题修复后的最新验收结论。

## 下一阶段边界

下一步先用固定候选做小规模真实模型与来源联调，覆盖仅知识库、仅网页、混合检索、来源矛盾和证据不足等场景，观察真实规划、标准绑定、引用、停止行为和预算。通过后再进入跨会话研究记忆，随后增加多 Agent 协作。

本次没有调用付费模型或公网来源、部署演示或修改用户数据。研究记忆与多 Agent 尚未开发；当前通过的是统一 Agent 工程基础及其指定自动化验收。

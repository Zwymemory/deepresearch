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

## 实际结果

本节在本轮运行完成或触发停止条件后更新。准备阶段不声称任何真实场景通过。每项的原问题、终态、账本、模型身份回执、原文／读取／引用哈希、任务标准、论断与发布证明保存在不可覆盖的受保护审计中，B 根据本轮实际产物独立判定。

首项失败、预算耗尽、身份错误、提交不确定或语义 fail/incomplete 时，立即停止后续付费研究，保留失败并将后续标为 not-run，完成离线归因。本轮无修复后同额度重跑。整体 Round 1 是否验收由协调侧综合两份报告决定。

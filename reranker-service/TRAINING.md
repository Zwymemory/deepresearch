# SciFact Cross-Encoder 轻量训练实验

这套代码只训练检索排序器，不训练生成式大模型。准确表述是：

> 在固定 revision 的 `BAAI/bge-reranker-base` 上，用 LoRA 和 1 正例 + 5 难负例的 listwise softmax loss 做监督式 Cross-Encoder 排序微调。

它不能被表述成 LLM SFT、DPO、RLHF、PPO、GRPO 或 Agentic RL。

## 数据契约与防泄漏

`training.scifact` 对完整 BEIR SciFact 执行确定性审计：

- 原始 train qrels 有 809 个 query，official test 有 300 个 query；
- 规范化 query 后发现 `871 ↔ 870`、`1291 ↔ 1292` 两组跨集合重复，删除 train 侧的 `871` 和 `1291`；
- seed 固定为 42，并把共享正例文档的 query 作为不可拆分连通分量；
- 最终为 703 train、104 dev、300 official test；
- strict test 包含 104 个 query，其全部正例文档均未出现在清洗后的 train/dev 正例中。

生成数据前先准备完整公开数据：

```bash
../scripts/prepare-full-scifact.sh
python -m training.scifact \
  --data-dir ../testdata/open/scifact/raw/scifact \
  --output-dir training-artifacts/split
```

`split-manifest.json` 保存源文件 SHA-256、集合摘要、重复 query 和数量。训练入口会重新构建 split，并在加载模型前验证 group 文件与这份数据契约完全一致；过期或混入 test query 的 group 会直接失败。

2026-08-22 已在本机使用完整官方快照复核上述数据契约，得到
`703/104/300/104`，并把不含原始语料的可公开收据保存在
[`training-receipts/scifact-split-manifest.json`](training-receipts/scifact-split-manifest.json)。
这只证明数据切分与泄漏审计通过，不代表 LoRA 正式训练已经运行。

## 难负例与统一输入格式

正式实验应分别提供 BM25 和 dense Top-K run，再通过 RRF 合并、使用固定基座 Cross-Encoder 重排候选，最后排除该 query 的所有正例并取前 5 个难负例：

```bash
python -m training.hard_negatives \
  --data-dir ../testdata/open/scifact/raw/scifact \
  --split train \
  --candidate-run bm25=training-artifacts/bm25-train.jsonl \
  --candidate-run dense=training-artifacts/dense-train.jsonl \
  --base-rerank \
  --output training-artifacts/train-groups.jsonl
```

dev 集合执行同样命令并把 `--split` 改为 `dev`。无外部 run 时，代码可以用确定性本地 BM25 生成离线 smoke 数据，但这种结果不能冒充 BM25+dense 正式难负例实验。

服务、挖掘、训练和评测全部调用同一个 `training.text_format.format_pair`：

```text
query
标题：{title}\n正文：{content}
```

## 训练配置

固定配置：base revision `2cfc18c9415c912f9d8155881c133215df768a70`、seed 42、LoRA `r=8`、`alpha=16`、`dropout=0.05`、attention query/value、classifier head 随 adapter 保存、最多 4 epoch、dev grouped nDCG@10 early stopping。

```bash
python -m pip install -r requirements-training.txt
python -m training.train \
  --data-dir ../testdata/open/scifact/raw/scifact \
  --train-groups training-artifacts/train-groups.jsonl \
  --dev-groups training-artifacts/dev-groups.jsonl \
  --output-dir training-artifacts/run-seed-42 \
  --model-id deepresearch-bge-reranker-scifact-lora-v1 \
  --device mps \
  --max-epochs 4 \
  --cloud-cost-cny 0 \
  --hardware "MacBook Air Apple M5 24GB"
```

`--smoke` 仍需要本机已有基座模型缓存；它只截取少量 group 并跑 1 epoch。自动化单元测试不会联网，也不会下载模型。

2026-08-22 已在本机 Apple M5 的 Docker CPU 环境完成一次 `--smoke`：使用
8 个 train group、4 个 dev group、1 epoch 和 `max_length=64`，验证模型下载、
LoRA 注入、listwise 反向传播、dev 评测、adapter 合并及 manifest 导出链路可运行。
不含权重的公开收据见
[`training-receipts/lora-smoke-receipt.json`](training-receipts/lora-smoke-receipt.json)。
该结果只证明流水线贯通；本地 BM25 fallback、极小样本与短序列都不满足正式实验口径，
`devGroupedNDCG@10` 不得用于简历效果主张或部署决策。

训练完成后生成合并模型、`training-manifest.json` 和对应 `.sha256`。manifest 记录数据/模型 revision、文件 hash、超参数、环境、硬件、人工填报成本和许可证。模型权重默认不进入 Git。

## 固定候选评测与上线门槛

base 和 candidate 必须在同一个 RRF Top20 文件上打分：

```bash
python -m training.score_candidates --data-dir ../testdata/open/scifact/raw/scifact \
  --candidate-run training-artifacts/rrf-test-top20.jsonl \
  --output training-artifacts/base.jsonl --device mps

python -m training.score_candidates --data-dir ../testdata/open/scifact/raw/scifact \
  --candidate-run training-artifacts/rrf-test-top20.jsonl \
  --model-path training-artifacts/run-seed-42/merged \
  --output training-artifacts/candidate.jsonl --device mps

python -m training.evaluate --data-dir ../testdata/open/scifact/raw/scifact \
  --baseline training-artifacts/base.jsonl \
  --candidate training-artifacts/candidate.jsonl \
  --output training-artifacts/evaluation.json
```

评测输出 macro nDCG@10、MRR@10、标准 Recall@10、HitRate@10、候选 Recall@20、p50/p95，以及 seed 42 的 10,000 次 query-level paired bootstrap。程序会校验每个 query 的候选文档集合一致。

只有以下条件全部满足才可以把新模型设为默认：`ΔnDCG@10 ≥ 0.010`、95% CI 下界大于 0、Recall/HitRate 回退不超过 0.005、strict-test nDCG 不退化、p95 不超过基座 1.15 倍。未通过时保留基座并公开负结果。

## 推理兼容

旧配置继续可用：

```text
RERANK_MODEL=BAAI/bge-reranker-base
```

通过上线门槛后才使用本地模型：

```text
RERANK_MODEL_PATH=/models/deepresearch-reranker/merged
RERANK_MODEL_ID=deepresearch-bge-reranker-scifact-lora-v1
RERANK_BASE_MODEL_ID=BAAI/bge-reranker-base
RERANK_BASE_REVISION=2cfc18c9415c912f9d8155881c133215df768a70
RERANK_MANIFEST_PATH=/models/deepresearch-reranker/merged/training-manifest.json
RERANK_MAX_LENGTH=256
```

`/rerank` 的请求和响应结构保持不变；`/health` 新增 `modelId`、`baseModelId`、`baseRevision` 和 `manifestSha256`。
Java 端会校验响应中的 `model`。切换本地模型时，必须把
`deepresearch.rerank.model` 同步设为相同的 `RERANK_MODEL_ID`；否则 Java 会把它视为模型配置不一致并按既有策略回退到 RRF。

## 许可证边界

| 资产 | 记录的上游条款 | 发布要求 |
|---|---|---|
| BAAI/bge-reranker-base | MIT | 保留许可证和来源 |
| SciFact claims | CC BY 4.0 | 署名并注明修改 |
| BEIR SciFact abstracts | ODC-By 1.0，并受上游语料条款约束 | 发布训练权重前重新审查 |

代码可以公开；adapter 或合并权重在完成上游条款复核前不公开。

## 离线测试

```bash
python -m pytest
python -m compileall -q app.py runtime_config.py training tests
```

测试只使用合成数据和临时文件，不请求网络、不加载 Hugging Face 权重。

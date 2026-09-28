# DeepResearch 公开发布手册

目标是发布可学习、可复现的项目候选版。当前仓库为独立项目；门禁兼容原 monorepo 中精确的 `deepresearch/` 子树，不导出兄弟目录。公开输入必须是已审阅、已提交的 Git tree。

## 发布边界

| 应包含 | 排除 |
|---|---|
| 源码、迁移、离线测试、Docker/CI 配置 | `.env`、本机覆盖配置、私钥、provider token |
| MIT LICENSE、第三方声明、架构与学习说明 | 个人简历、联系方式、作者机器路径 |
| 合成知识包、固定题目、去敏 capture/review/score | 未去敏真实输出、公司内部文档 |
| 训练与评测代码 | 模型权重、训练产物、原始 SciFact、embedding cache |
| 锁定依赖与模板 `.env.example` | 构建目录、Python 环境、缓存、日志 |

`.gitignore` 防止新文件误提交，`.gitattributes` 的 `export-ignore` 限定 archive；二者不能代替历史秘密扫描。公开采样仅来自合成知识包，仍须人工检查去敏情况及评测结论。

## 1. 冻结提交与门禁

从项目根目录运行：

```bash
git status --short
git diff --check -- .
make showcase-check
docker compose --env-file .env.example config --quiet
docker compose --env-file docs/showcase/.env.example \
  -f docker-compose.yml -f docker-compose.showcase.yml config --quiet
```

只提交本轮确认的路径；不要用无范围的 `git add -A` 带入其他任务。门禁从 HEAD 创建 archive，交付范围有未提交或未跟踪文件就失败，避免遗漏。提交后执行：

```bash
bash scripts/verify-public-release.sh
```

它检查工作树、必要声明、archive 边界、联系方式、作者路径、凭证形态，并要求 Gitleaks 扫全部可达历史。Gitleaks 未安装会失败。需要补查最终快照时，对 `git archive` 解包后的公开目录执行 `gitleaks dir --redact --no-banner --config .gitleaks.toml .`，不要把本机私有 `.env` 作为公共报告输入。

`.gitleaksignore` 仅登记旧 W10.5 文档中两个无效演示 Token 的精确 fingerprint，不忽略整个文件、提交或 Authorization Header。去敏测试使用保留域与动态合成手机号形状，仍断言隐私字段被替换。

工程门禁包括 `mvn test`、`mvn -Pintegration verify`、`make workflow-test`、`make reranker-test` 和 workflow PostgreSQL 集成测试。`scripts/verify-engineering-baseline.sh` 会启动 Legacy 依赖，不能在其他任务占用服务时运行。GitHub CI 执行这些分层检查及 `make showcase-check`。

CI 的快照检查用 `--skip-history`，另由 `fetch-depth: 0` 的 Gitleaks Action 扫历史。离线 showcase job 不提供 API key、不启动服务；它校验 DSL/标签契约，并精确比较 `integrations/ragflow/showcase_artifacts.json` 登记的 capture + review 重算结果与公开 score。新采样追加目录项，不覆盖失败的历史结果。

## 2. 新目录复现

检出同一候选 SHA，先离线检查，再按[复现指南](showcase/REPRODUCE.md)创建配置、启动和导入。记录范围：

- 是新 Git 检出目录，还是新服务器、空数据库和首次外部服务安装；
- 外部 RAGFlow/Dify 是否复用、其版本、模型、语料及参数；
- 八份内容哈希、Java 文档与 RAGFlow 映射的 `DONE` 状态；
- 新 run 的实际路由、答案、引用、拒答及 SSE 观察；
- 未执行、失败和受网络影响的部分。

复用外部栈不能称为全栈首次安装通过，不删除数据卷来制造干净环境。现场步骤见[五分钟讲稿](showcase/FIVE_MINUTE_DEMO.md)，结果与边界见[证据状态](showcase/EVIDENCE.md)。

## 3. 审查快照

独立仓库使用 `HEAD`；原 monorepo 使用 `HEAD:deepresearch`。以下在独立仓库创建尚不存在的文件：

```bash
ARCHIVE_PATH="$(dirname "$(git rev-parse --show-toplevel)")/deepresearch-public.tar.gz"
test ! -e "$ARCHIVE_PATH"
git archive --format=tar.gz --prefix=deepresearch/ --output="$ARCHIVE_PATH" HEAD
tar -tzf "$ARCHIVE_PATH" | sed -n '1,80p'
```

只读已提交 tree，应用 export-ignore，不包含历史。需要提取原 monorepo 历史时，在独立副本中审阅 subtree split / git-filter-repo 方案，不自动重写正在工作的仓库。发现真实凭证先轮换，再处理历史。

## 4. 草稿 PR 与公开状态

确认仓库归属、候选分支和用户授权后，推送候选分支并建立 draft PR。正文写清变更、验证范围、质量样本、边界与已知失败。主分支不自动合并，秘密和模型权重不随 PR 发布。

网络失败时保留本地提交、门禁结果和独立 PR 正文，报告实际错误；准备了正文不能称 PR 已创建。CI 配置已添加也不代表托管 CI 已运行。

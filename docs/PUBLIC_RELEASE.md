# DeepResearch 公开发布手册

> 目标：只发布 `deepresearch` 项目，不带出 monorepo 的其他目录，不带出密钥、个人联系方式、简历、原始数据、模型权重或构建缓存。本手册不创建远程仓库，不执行 push。

## 发布包边界

公开快照使用已提交的 `deepresearch` Git tree 作为唯一输入，不允许用 Finder、`cp -R` 或 monorepo 根目录打包。

| 应包含 | 必须排除 |
|---|---|
| Java/Python 源码、数据库迁移、离线测试、Docker/CI 配置 | monorepo 中 `deepresearch/` 以外的任何目录 |
| MIT `LICENSE`、`THIRD_PARTY_NOTICES.md`、架构与面试教学文档 | `.env`、`application-local.yml`、私钥/证书、provider token |
| 合成的 demo/eval 小样本 | SciFact 原始/转换语料、评测结果与 embedding cache |
| 训练和评测管线代码 | adapter/合并模型、`*.safetensors`/`*.pt`/`*.onnx`/`*.gguf` 等权重 |
| 去敏的岗位调研与题库 | `docs/career/resume-*`、手机号、微信、邮箱、本机绝对路径 |
| 锁定的依赖声明 | `target/`、`.venv*`、`__pycache__/`、test/cache/run/artifact 目录 |

`.gitignore` 阻止新的本地产物误提交；`.gitattributes` 的 `export-ignore`
是 `git archive` 的第二道门。两者都不能替代历史密钥扫描。

## 一、冻结并验证交付

在 monorepo 根目录运行：

```bash
PROJECT_PREFIX=deepresearch
git status --short -- "$PROJECT_PREFIX"
git diff --check -- "$PROJECT_PREFIX"
```

先人工审查状态，再只提交 `deepresearch/` 内本次确认的文件。不要在
monorepo 根目录运行无路径限制的 `git add -A`。发布检查默认 fail-closed：
只要 `deepresearch/` 内还有未提交或未跟踪文件，就会失败，避免
`git archive` 静默遗漏交付物。

从 `deepresearch/` 目录运行：

```bash
make test
make integration-test
make report
docker compose config --quiet
bash scripts/verify-public-release.sh
```

`verify-public-release.sh` 会校验工作树、MIT/第三方声明、archive 路径、
权重/数据/简历/缓存排除、联系方式与常见密钥形态，并调用 Gitleaks
扫描完整 Git 历史。未安装 `gitleaks` 也会失败，不会把“没扫描”当成通过。
`.gitleaksignore` 只登记历史 W10.5 文档里两个无效本地演示
`X-Admin-Token` 占位值的完整 finding fingerprint（提交、路径、规则和行号）；
它不忽略整个文件、提交或通用 Authorization Header。

CI 中快照规则与历史密钥扫描是两个门禁：前者使用
`--skip-history` 避免重复扫描，后者使用 `fetch-depth: 0` 和 Gitleaks Action。

## 二、生成无 Git 历史的快照包（最小风险）

只有上一节全部通过后才执行。下面的目标文件必须不存在，防止覆盖：

```bash
MONOREPO_ROOT="$(git rev-parse --show-toplevel)"
ARCHIVE_PATH="$(dirname "$MONOREPO_ROOT")/deepresearch-public.tar.gz"
test ! -e "$ARCHIVE_PATH"

git -C "$MONOREPO_ROOT" archive \
  --format=tar.gz \
  --prefix=deepresearch/ \
  --output="$ARCHIVE_PATH" \
  HEAD:deepresearch

tar -tzf "$ARCHIVE_PATH" | sed -n '1,80p'
```

`git archive` 只读取已提交 tree，并应用 `.gitattributes` 的 `export-ignore`。
这个 tarball 不含 Git 历史，适合作为人工审查和公开仓库首个快照的输入。

## 三、需要保留项目历史时的干净 subtree

该流程只在本地生成独立仓库，不配置远程、不 push。需预先安装
[`git-filter-repo`](https://github.com/newren/git-filter-repo)：

```bash
MONOREPO_ROOT="$(git rev-parse --show-toplevel)"
PUBLIC_REPO_DIR="$(dirname "$MONOREPO_ROOT")/deepresearch-public"
test ! -e "$PUBLIC_REPO_DIR"

# 不创建/切换 monorepo 分支；subtree split 只返回一个本地 commit id。
SPLIT_COMMIT="$(git -C "$MONOREPO_ROOT" subtree split --prefix=deepresearch HEAD)"
mkdir "$PUBLIC_REPO_DIR"
git -C "$PUBLIC_REPO_DIR" init
git -C "$PUBLIC_REPO_DIR" fetch --no-tags "$MONOREPO_ROOT" "$SPLIT_COMMIT"
git -C "$PUBLIC_REPO_DIR" switch -c main FETCH_HEAD

# 从全部 subtree 历史中移除私有交付和大文件，而不只删当前快照。
git -C "$PUBLIC_REPO_DIR" filter-repo --force --invert-paths \
  --path-glob 'docs/career/resume-*' \
  --path testdata/open/ \
  --path testdata/eval/open-scifact.jsonl \
  --path reranker-service/artifacts/ \
  --path reranker-service/models/ \
  --path reranker-service/runs/ \
  --path reranker-service/training-artifacts/ \
  --path reranker-service/outputs/ \
  --path target/ \
  --path-glob '**/target/**' \
  --path-glob '**/.venv*/**' \
  --path-glob '**/__pycache__/**' \
  --path-glob '**/.pytest_cache/**' \
  --path-glob '**/.ruff_cache/**' \
  --path-glob '**/.cache/**' \
  --path-glob '**/cache/**' \
  --path .env \
  --path application-local.yml \
  --path-glob '**/.env' \
  --path-glob '**/application-local.yml' \
  --path-glob '*.safetensors' \
  --path-glob '**/*.safetensors' \
  --path-glob '*.bin' \
  --path-glob '**/*.bin' \
  --path-glob '*.ckpt' \
  --path-glob '**/*.ckpt' \
  --path-glob '*.onnx' \
  --path-glob '**/*.onnx' \
  --path-glob '*.gguf' \
  --path-glob '**/*.gguf' \
  --path-glob '*.pth' \
  --path-glob '**/*.pth' \
  --path-glob '*.pt' \
  --path-glob '**/*.pt' \
  --path-glob '*.npy' \
  --path-glob '**/*.npy' \
  --path-glob '*.npz' \
  --path-glob '**/*.npz'

git -C "$PUBLIC_REPO_DIR" remote -v
git -C "$PUBLIC_REPO_DIR" status --short
git -C "$PUBLIC_REPO_DIR" ls-tree -r --name-only HEAD | sed -n '1,120p'
```

`remote -v` 应无输出；`ls-tree` 中不应出现 monorepo 兄弟目录或排除项。
然后在这个独立仓库执行完整门禁：

```bash
cd "$PUBLIC_REPO_DIR"
bash scripts/verify-public-release.sh
gitleaks dir --redact --no-banner .
```

`verify-public-release.sh` 中的 `gitleaks git` 扫全历史；额外的 `gitleaks dir`
扫最终快照，用于防止“历史 patch 没触发，但当前文件仍有秘密”的漏检。

## 四、发布前人工复核

- 确认 `LICENSE` 只覆盖项目原创代码，模型/数据边界以
  [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) 为准。
- 用新环境按 `README.md` 执行离线测试；默认 CI 不能请求付费模型。
- 搜索本机用户名、绝对路径、真实姓名与联系方式；对 HTML、PDF、图片做可视复核。
- 确认没有模型权重、真实 provider 输出、未去敏轨迹或公司内部文档。
- 检查 `git log --all --stat`、`git remote -v` 和 Gitleaks 结果；发现泄漏时先轮换密钥，再在本地导出仓库重写历史。
- 不要在此流程中自动建远程或 push；等单独确认仓库归属、可见性和最终文件清单后再发布。

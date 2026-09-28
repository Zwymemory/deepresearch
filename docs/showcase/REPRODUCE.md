# 从新检出目录复现 RAGFlow + Dify 演示

面向第一次运行本仓库的人。仓库命令从根目录执行，配置只放在被忽略的 `.env`。使用自己的 RAGFlow 数据集和 Dify Workflow，不依赖作者数据库、数据集 ID 或启动脚本。

## 1. 版本、资源与端口

| 组件 | 本轮固定版本 / 要求 | 默认入口 |
|---|---|---|
| Java / PostgreSQL | Dockerfile 的 Temurin 21；pgvector PostgreSQL 16 | `8080` / `5432` |
| RAGFlow | 0.27.2，独立服务 | UI `80`；HTTP API `9380` |
| Dify | 1.17.0，独立服务 | HTTP `8081`；HTTPS `8444`；Service API `/v1` |
| Dify 模型插件 | 官方 DeepSeek 0.0.24；`deepseek-v4-flash` | 在 Dify 中单独配置 provider 凭证 |
| 离线检查 | Python 3.12、Make；Java 测试另需 JDK 21 / Maven | 不请求外部服务 |
| Compose | 覆盖配置需要 2.24.4+；新部署 RAGFlow 使用 2.26.1+ | `!override` 替换 Legacy 启动依赖 |

[RAGFlow 0.27.2 官方要求](https://ragflow.io/docs/v0.27.2/)为 x86 四核、16 GB RAM、50 GB 磁盘；ARM 没有官方维护的镜像，需按[官方构建步骤](https://ragflow.io/docs/v0.27.2/build_docker_image)构建。作者机器使用 ARM 自建镜像，不随本仓库提供。[Dify 官方部署文档](https://docs.dify.ai/en/self-host/deploy/quick-start/docker-compose)另列两核、4 GiB RAM，macOS Docker VM 至少 8 GiB。这些是单产品要求；同时运行两套服务和 Java 需要额外余量，本次受限机器约 7.75 GiB 的顺序运行不是最低配置承诺。

镜像、模型下载需要可用网络，模型请求可能收费。端口以实际 Compose 映射为准。本仓库演示覆盖配置将 Java 和 PostgreSQL 绑定到本机回环地址。

演示配置使用独立 Compose project `deepresearch-showcase` 和自己的 PostgreSQL 卷，不接管默认栈的容器或数据库。旧 PostgreSQL 已占用 `5432` 时，在 `.env` 设置 `DEEPRESEARCH_PG_PORT=5433`；Java 端口改为 `DEEPRESEARCH_APP_PORT` 时还要同步 Dify 回调地址。

## 2. 独立部署外部服务

已有健康服务可以复用并记录版本；这属于新检出目录验证，不是首次服务器安装。保留已有数据卷。

在本仓库之外的空目录分别检出固定版本：

```bash
git clone --branch v0.27.2 --depth 1 https://github.com/infiniflow/ragflow.git
git clone --branch 1.17.0 --depth 1 https://github.com/langgenius/dify.git
```

按各自版本说明启动。RAGFlow 保留 UI `80/443` 和 API `9380`；Dify 在其 `docker/.env` 设置 `EXPOSE_NGINX_PORT=8081`、`EXPOSE_NGINX_SSL_PORT=8444`，避免冲突。用外部栈的 `docker compose config --quiet` 与 `docker compose ps` 核对映射；其他冲突端口按实际配置调整。

在 RAGFlow 配置可用的 embedding provider，创建**新的空数据集**，为 Markdown 选择 General/naive chunk 模板。账户 API 页面提供 API Key；数据集 URL `/dataset/files/<id>` 提供 ID。由 Java 导入八份文档并保存映射，仅在 RAGFlow UI 上传不会自动产生 Java 注册表。解析开始后不要随意换 embedding；embedding/chunk 参数改变时要记录新的实验条件。

## 3. 私有配置与 Dify Workflow

```bash
make showcase-check
python3 scripts/init-showcase-env.py
```

初始化脚本读取[展示模板](.env.example)，生成五个不同服务密钥，只创建不存在的 `.env`。已有文件会保留，按模板人工补缺项。shell 已导出的同名变量覆盖文件，与 Compose 相同；排查时检查旧覆盖，但不要打印密钥。

填写自己的凭证：

- `DEEPSEEK_API_KEY`：Java 模型凭证；Dify provider 凭证需在其 UI 另行设置。
- `TAVILY_API_KEY`：仅网页搜索需要；保持在私有 `.env`。缺配置、provider 拒绝/限流/超时和无结果会分别报告，配置非空不代表有效或有结果。
- `RAGFLOW_API_KEY`、`RAGFLOW_DATASET_IDS`：自己的新数据集；多个 ID 用逗号分隔，导入使用第一个。
- `RAGFLOW_BASE_URL`：Java 容器可访问的 API origin，通常 `http://host.docker.internal:9380`。
- `DEEPRESEARCH_DIFY_BASE_URL`：Java 容器可访问的 Service API，通常 `http://host.docker.internal:8081/v1`。
- `SHOWCASE_RAGFLOW_URL`、`SHOWCASE_DIFY_URL`：本机预检访问同一服务器的地址。
- `ZHIPU_API_KEY=unused-ragflow-only`：Java 仍初始化 Legacy embedding bean，RAGFlow 路径不调用它；学习 Legacy 时换成可用智谱凭证。

在 Dify Studio 导入 `integrations/dify/deepresearch-evidence-v1.yml`，选择已配置模型。文件名保留兼容名称；版本变更见[Dify 指南](../../integrations/dify/README.md)，不要以较早的 `.exported.yml` 代替源 DSL。

| Dify Workflow 变量 | 配置 |
|---|---|
| `JAVA_INTERNAL_BASE_URL` | `http://host.docker.internal:8080`，无结尾斜线；Java 改端口时同步改 |
| `DIFY_TOOL_SERVICE_TOKEN` | Secret 类型，取 `.env` 中 `DEEPRESEARCH_WORKFLOW_DIFY_TOOL_SERVICE_TOKEN` 的相同值 |

Dify API/worker 和 HTTP Request 的 SSRF proxy 必须能解析并访问 Java origin。Docker Desktop 可使用宿主机别名；原生 Linux 的 host-gateway 通常不能访问仅绑定宿主机回环地址的端口，使用下面的共享 bridge 方案。Dify HTTP Request SSRF 策略仅允许所需 Java origin，具体配置按版本检查；不要关闭全局防护。

发布 Workflow，在 **API Access** 取得 Service API App Key，填入 `.env` 的 `DEEPRESEARCH_DIFY_APP_KEY`。它与管理员登录 Token、工具服务密钥用途不同。导出 DSL 时 Secret 值保持为空，不提交私有导出。

当前源 DSL 为 Evidence v8.1 Web，Java 须包含 V16 网页来源迁移与配套发布校验。升级既有环境时同步部署 Java 和发布 DSL，保留已有 `.env` 与数据卷；旧 V7 完整评测不能当作新代码的全量结果。

## 4. 启动与语料导入

```bash
python3 scripts/preflight-showcase.py
bash scripts/start-showcase.sh
python3 scripts/preflight-showcase.py --online
```

脚本使用 base Compose 加 `docker-compose.showcase.yml`，只启动 `app` 及 PostgreSQL / role bootstrap，不启动 Legacy Elasticsearch、BGE reranker 或 LangGraph sidecar。RAGFlow/Dify 独立运行；首次 Java 镜像构建需要 Maven 依赖下载。

### 原生 Linux 的 Dify 回调

Java 启动会创建 `deepresearch-showcase-callback` bridge，Java 服务别名为 `deepresearch-java`。在本仓库根目录保存路径 `export DEEPRESEARCH_REPO="$(pwd)"`，然后在同一个 shell 切换至独立 Dify 的 `docker/` 目录执行：

```bash
docker compose -f docker-compose.yaml \
  -f "$DEEPRESEARCH_REPO/docs/showcase/dify-callback.compose.yml" \
  up -d api worker ssrf_proxy
```

将 Workflow 的 `JAVA_INTERNAL_BASE_URL` 改为 `http://deepresearch-java:8080`，仅允许该 origin 的 SSRF 访问，重新发布。它使用容器内端口，不受 Java 宿主机端口变更影响。自定义 `COMPOSE_PROJECT_NAME` 时同步设置 `DEEPRESEARCH_CALLBACK_NETWORK`。后续管理 Dify 时沿用同一组 Compose 文件，避免撤掉回调网络。Docker Desktop 若宿主机别名不可达，也可采用该方案。该 overlay 只连接网络，不更改模型或秘密。

### 所有平台的知识包导入

展示模板显式开启本机 dev-token。取得 ADMIN Token 放入变量；下面不将 Token 直接输出到终端：

```bash
export DEEPRESEARCH_ADMIN_TOKEN="$(curl -fsS \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"demo","userId":"local-importer","roles":["ADMIN"],"ttlSeconds":7200}' \
  http://127.0.0.1:8080/api/auth/dev-token \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
bash scripts/import-project-kb.sh
python3 scripts/preflight-showcase.py --corpus
```

非默认 Java 端口时修改 Token URL，并为导入设置 `BASE_URL`；预检读取 `DEEPRESEARCH_APP_PORT`。解析可能尚未 `DONE`，稍后重跑 `--corpus`；若 `FAILED`，检查 RAGFlow 解析、embedding 和 Java 日志。该检查用 ADMIN 调用状态 GET，后端可能推进已有同步任务，但不新增上传或清空库。

检查要求八份内容 SHA256 与仓库一致、Java 文档 `DONE`、RAGFlow 映射 `DONE`。允许其他文档共存。要与公开质量报告比较，则另建仅含这八份文档的语料，记录模型、参数、预算和 SHA。

## 5. 前端验收

打开 `http://localhost:8080/demo.html`，签发 USER 身份，选择 **Durable Workflow** 和知识库检索。按[五分钟讲稿](FIVE_MINUTE_DEMO.md)创建新运行：

1. `Last-Event-ID` 正例：事实正确、有有效编号与真实 chunk 引用，安全轨迹含 `DIFY_STAGE`。
2. 银行账户题：`INSUFFICIENT_EVIDENCE`，不捏造号码或来源。
3. 仍运行时点 **断线演练**：同一 run 自动重连，events 请求带旧游标，没有重建任务。

网页或混合检索另运行 `python3 scripts/preflight-showcase.py --require-web-search --online --corpus`，再选择页面的网页搜索。纯网页可问 asyncio 的并发/I/O 用途；检查引用是否显示实际 URL、标题、摘要，摘要是否支持每句话。网页来源只证明来自本 run 已完成且获授权的搜索回执，不等于抓取全文或证明事实正确。当前五项定向终态通过、严格逐句支持 4/5，全部 12 次尝试和初版超时见 [v8.1 验收](../../integrations/dify/WEB_SEARCH_ACCEPTANCE_2026-09-28.md)。

预检只说明依赖条件，不证明答案质量或恢复能力。一次成功不能代替固定评测、引用支持性审阅或生产门禁；最新结果见[EVIDENCE](EVIDENCE.md)。

停止本仓库服务可用以下命令，不删除数据卷；外部服务生命周期单独管理：

```bash
docker compose -f docker-compose.yml -f docker-compose.showcase.yml stop app postgres
```

重启仍用相同配置与数据；缺失 ADMIN Token 时重新签发。

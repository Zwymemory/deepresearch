# 本机前后端启动

在仓库根目录执行：

```bash
bash scripts/local-services.sh start
bash scripts/local-services.sh status
bash scripts/local-services.sh stop
```

页面：http://127.0.0.1:5173/app/ 。后端：8080；研究执行服务：8091；专用 PostgreSQL：55433。
启动后可在“连接与身份”签发 USER Dev Token 并保存，再选择“自主研究（候选）”。
启动命令不创建研究、不调用研究规划模型；首次会为本机项目建立专用 RAGFlow 知识库，
通过既有 ADMIN 入库接口上传 `docs/kb-project` 的 9 份脱敏项目文档，解析时使用已配置的 embedding 服务。
ADMIN 身份由本机安装密钥签发，仅在初始化进程内存中短期使用，不开放匿名 ADMIN 签发。
用户提交研究后使用真实 DeepSeek 和 RAGFlow。

脚本复用已有 RAGFlow（默认本机 9380），开启证据、阶段一至四记忆和自动保存。
后台进程退出终端后继续运行。日志及进程身份保存在被 Git 忽略的 `target/local-services/`。
重复启动复用就绪进程；修改代码后先 stop 再 start，启动时重新构建后端。
stop 只停止此脚本记录且身份一致的进程及专用数据库容器，不删除数据库卷。
首次新建数据库不含之前临时验收记录；研究完成后会自动生成档案。
start 必须检查九份文档的内容哈希、DONE 状态、RAGFlow 映射和真实检索非空，才报告启动完成。
status 同样核对知识库。重复启动不会重复导入相同内容，不删除其他知识库或旧研究记录。
项目库使用 512 token 分片，减少机制与代码、测试证据被拆散的情况；旧 128 token 索引会通过正常重建接口迁移，保留文档身份和既有研究快照。
修改私有配置后必须先 stop 再 start，脚本会拒绝复用配置不匹配的旧后端。

本机已准备私有配置 `target/local-services/config.json`，权限为 600。
其他机器需在此文件配置以下字段（真实值不得提交或截图）：

- `FRONTEND_DIR`：当前 React 前端的绝对目录，内有 package.json。
- `DEEPSEEK_API_KEY`、`RAGFLOW_API_KEY`、`RAGFLOW_DATASET_IDS`。
- `LOCAL_PROJECT_KB_DATASET_NAME`：本机项目专用知识库名称，首次创建后自动写回 `RAGFLOW_DATASET_IDS`。
- `LOCAL_PROJECT_KB_EMBEDDING_MODEL`：首次创建使用的已配置 RAGFlow embedding 模型标识。
- `POSTGRES_PASSWORD`、`WORKFLOW_DB_PASSWORD`，后者至少 32 字节。
- 三个各不相同的至少 32 字节密钥：`DEEPRESEARCH_JWT_SECRET`、`DEEPRESEARCH_INTERNAL_JWT_SECRET`、`DEEPRESEARCH_MCP_JWT_SECRET`。
- 可选 `ZHIPU_API_KEY`、`TAVILY_API_KEY`、`RAGFLOW_BASE_URL`。
- 网页原文读取：本机 Fake-IP 网络配置 `DEEPRESEARCH_AGENT_EVIDENCE_WEB_DNS_MODE=google-doh`，通过固定公网地址连接 Google 加密 DNS，并验证 `dns.google` TLS 证书。分别解析 A/AAAA、检查全部地址后，再固定真实公网地址读取官网。默认 `system` 使用系统 DNS；不自动放行保留地址，不更改系统代理或关闭证书校验。需要网络能访问 Google DoH；失败时明确返回读取失败，不回退到 Fake-IP。查询不携带客户端子网信息。
- 模型默认使用已验收的 `deepseek-flash` / `function_call`（显式关闭思考模式） 组合；修改模型时需同步验证传输契约。

依赖：Docker、Java 21、Maven、Python 3、Node >=20.19，以及 `workflow-service/.venv`。
首次准备 Python 依赖，在 workflow-service 中执行 `uv sync --locked`。
端口被其他服务占用时脚本报错，不自动终止其他服务。

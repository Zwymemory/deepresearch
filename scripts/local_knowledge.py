"""Initialize and verify the sanitized project corpus through the normal APIs."""
import base64
import hashlib
import hmac
import json
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import uuid

FILES = ["README.md", "01-architecture-and-trust-boundaries.md", "02-checkpoint-and-crash-recovery.md",
         "03-claim-lease-and-fencing.md", "04-sse-durable-replay.md", "05-tool-receipt-and-unknown-result.md",
         "06-verification-boundaries.md", "07-agent-evaluation-fact-cards.md", "08-workflow-creation-idempotency.md"]
PROBE = "DeepResearch 的 SSE 断线后如何用 Last-Event-ID 恢复事件，如何避免重复创建任务？"
PARSER = {"chunk_token_num": 512, "delimiter": "\n", "auto_keywords": 0, "auto_questions": 0}


def api(url, token, data=None, content_type="application/json", timeout=45, method=None):
    if data is not None and not isinstance(data, bytes):
        data = json.dumps(data).encode()
    try:
        with urlopen(Request(url, data=data, headers={"Authorization": "Bearer " + token,
                                                    "Content-Type": content_type}, method=method), timeout=timeout) as r:
            return json.load(r)
    except HTTPError as e:
        raise RuntimeError(f"知识库接口返回 HTTP {e.code}；未输出凭据或响应正文。") from None
    except (URLError, TimeoutError):
        raise RuntimeError("知识库接口未响应；检查本机 RAGFlow 和后端日志。") from None


def admin_token(cfg):
    # Local installation administrator: short-lived, in-memory, never exported.
    def b64(value):
        return base64.urlsafe_b64encode(value).decode().rstrip("=")
    now = int(time.time())
    claims = {"iss": "deepresearch", "aud": "deepresearch-api", "sub": "local-kb-bootstrap",
              "tenantId": "local-services", "roles": ["ADMIN"], "iat": now,
              "jti": str(uuid.uuid4()), "exp": now + 1800}
    unsigned = b64(b'{"alg":"HS256","typ":"JWT"}') + "." + b64(json.dumps(claims).encode())
    return unsigned + "." + b64(hmac.new(cfg["DEEPRESEARCH_JWT_SECRET"].encode(), unsigned.encode(), hashlib.sha256).digest())


def dataset(cfg, persist):
    name = cfg.get("LOCAL_PROJECT_KB_DATASET_NAME")
    if not name:
        raise RuntimeError("请配置 LOCAL_PROJECT_KB_DATASET_NAME，明确指定本机项目专用知识库。")
    base = cfg.get("RAGFLOW_BASE_URL", "http://127.0.0.1:9380").rstrip("/") + "/api/v1"
    token = cfg["RAGFLOW_API_KEY"]
    found = []
    for page in range(1, 101):
        result = api(base + "/datasets?" + urlencode({"page": page, "page_size": 100}), token)
        if result.get("code") != 0 or not isinstance(result.get("data"), list):
            raise RuntimeError("无法读取 RAGFlow 知识库列表。")
        rows = result["data"]
        found.extend(x for x in rows if x.get("name", "").casefold() == name.casefold())
        if len(rows) < 100:
            break
    else:
        raise RuntimeError("知识库列表超过检查范围，无法确认专用库唯一性。")
    if len(found) > 1:
        raise RuntimeError("专用知识库名称不唯一，请检查配置。")
    if not found:
        embedding = cfg.get("LOCAL_PROJECT_KB_EMBEDDING_MODEL")
        if not embedding:
            raise RuntimeError("首次创建专用知识库需要配置 LOCAL_PROJECT_KB_EMBEDDING_MODEL。")
        result = api(base + "/datasets", token, {"name": name, "permission": "me", "language": "Chinese",
                     "embedding_model": embedding, "chunk_method": "naive",
                     "parser_config": PARSER})
        if result.get("code") != 0 or not isinstance(result.get("data"), dict) or not result["data"].get("id"):
            raise RuntimeError("专用知识库创建未确认；再次启动会先按名称查询，不盲目重复创建。")
        found = [result["data"]]
        print("已创建本机项目专用 RAGFlow 知识库", flush=True)
    did = found[0]["id"]
    if found[0].get("parser_config", {}).get("chunk_token_num") != PARSER["chunk_token_num"]:
        result = api(base + "/datasets/" + did, token, {"parser_config": PARSER}, method="PUT")
        if result.get("code") != 0:
            raise RuntimeError("本机项目知识库分片配置未更新。")
    if cfg.get("RAGFLOW_DATASET_IDS") != did:
        cfg["RAGFLOW_DATASET_IDS"] = did
        persist(cfg)
    return did


def verify(root, cfg, state, initialize=True):
    base = "http://127.0.0.1:8080"
    token = admin_token(cfg)
    rows = api(base + "/api/kb/documents", token)
    remote_base = cfg.get("RAGFLOW_BASE_URL", "http://127.0.0.1:9380").rstrip("/") + "/api/v1/datasets/" + cfg["RAGFLOW_DATASET_IDS"]
    remote = api(remote_base + "/documents?page=1&page_size=100", cfg["RAGFLOW_API_KEY"])
    if remote.get("code") != 0 or not isinstance(remote.get("data"), dict):
        raise RuntimeError("无法确认专用知识库分片配置。")
    expected = []
    for name in FILES:
        p = root / "docs/kb-project" / name
        content = p.read_bytes()
        title = content.decode().splitlines()[0].removeprefix("# ")
        sha = hashlib.sha256(content).hexdigest()
        exact = [r for r in rows if r["filename"] == name and r["title"] == title and r["contentHash"] == sha]
        if len(exact) > 1:
            raise RuntimeError("项目文档登记重复，需先核对。")
        if not exact or exact[0]["status"] != "DONE":
            if not initialize:
                raise RuntimeError("项目知识文档缺失、内容变化或解析未完成。")
            boundary = "local-project-" + uuid.uuid4().hex
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="title"\r\n\r\n{title}\r\n'
                    f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                    'Content-Type: text/markdown\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
            result = api(base + "/api/kb/documents/file", token, body, "multipart/form-data; boundary=" + boundary)
            if result.get("status") == "FAILED":
                raise RuntimeError("项目文档入库失败，检查后端日志。")
            print("项目知识文档入库：" + name, flush=True)
        elif initialize:
            row = exact[0]
            remote_name = f'dr-{row["docId"]}-v{row["version"]}-{row["contentHash"][:12]}.md'
            old = next((x for x in remote["data"].get("docs", []) if x.get("name") == remote_name), None)
            if old and old.get("parser_config", {}).get("chunk_token_num") != PARSER["chunk_token_num"]:
                sync = api(base + "/api/kb/documents/" + row["docId"] + "/ragflow-sync", token)
                if sync.get("status") == "DONE":
                    api(base + "/api/kb/documents/" + row["docId"] + "/reindex", token, {})
                    print("项目知识文档重建完整分片：" + name, flush=True)
        expected.append({"filename": name, "title": title, "sha256": sha})
    for _ in range(120):
        rows = api(base + "/api/kb/documents", token)
        matched = []
        for e in expected:
            found = [r for r in rows if r["filename"] == e["filename"] and r["contentHash"] == e["sha256"] and r["title"] == e["title"]]
            if len(found) == 1 and found[0]["status"] == "DONE":
                sync = api(base + "/api/kb/documents/" + found[0]["docId"] + "/ragflow-sync", token)
                if sync.get("status") == "DONE":
                    # The status read can finish a reindex and advance the active
                    # version; do not verify an already retired remote name.
                    fresh = api(base + "/api/kb/documents", token)
                    current = next((r for r in fresh if r["docId"] == found[0]["docId"]
                                    and r["contentHash"] == e["sha256"] and r["status"] == "DONE"), None)
                    if current:
                        matched.append(current)
        if len(matched) == len(FILES):
            break
        if not initialize:
            raise RuntimeError("项目知识文档与 RAGFlow 映射未全部就绪。")
        time.sleep(2)
    else:
        raise RuntimeError("项目知识库仍在解析；启动尚未验收成功，稍后重试 start。")
    response = api(base + "/api/research/hybrid/debug", token, {"question": PROBE, "topK": 5, "history": []})
    entries = response.get("compressedContext", [])
    remote = api(cfg.get("RAGFLOW_BASE_URL", "http://127.0.0.1:9380").rstrip("/") +
                 "/api/v1/datasets/" + cfg["RAGFLOW_DATASET_IDS"] + "/documents?page=1&page_size=100", cfg["RAGFLOW_API_KEY"])
    if remote.get("code") != 0 or not isinstance(remote.get("data"), dict):
        raise RuntimeError("无法确认专用知识库的实际文档状态。")
    active = set()
    for r in matched:
        name = f'dr-{r["docId"]}-v{r["version"]}-{r["contentHash"][:12]}.md'
        found = [x for x in remote["data"].get("docs", []) if x.get("name") == name]
        if len(found) != 1 or found[0].get("run") not in ("DONE", "3") or found[0].get("chunk_count", 0) < 1 or found[0].get("parser_config", {}).get("chunk_token_num") != PARSER["chunk_token_num"]:
            raise RuntimeError("项目文档的实际 RAGFlow 解析结果不完整。")
        active.add(found[0]["id"])
    if response.get("provider") != "ragflow" or not entries or any(
        x.get("docId") not in active or not x.get("preview") or x.get("evidenceChars", 0) < 1
        or not x.get("chunkKey", "").startswith("ragflow:" + cfg["RAGFLOW_DATASET_IDS"] + ":")
        for x in entries
    ):
        raise RuntimeError("项目文档已登记，但真实检索仍为空；启动尚未验收成功。")
    report = {"verified_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "documents_done": len(matched),
              "chunks": sum(r["chunkCount"] for r in matched), "probe": PROBE, "evidence_count": len(entries),
              "titles": [r["title"] for r in matched], "files": expected,
              "source_titles": [x.get("title") for x in entries]}
    (state / "knowledge-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"知识库已就绪：{len(matched)} 份项目文档，检索返回 {len(entries)} 条来源", flush=True)
    return report

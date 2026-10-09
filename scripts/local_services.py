"""Local Java + Python worker + current React frontend, with persistent PostgreSQL.

Private configuration: target/local-services/config.json (never committed).
No research/model calls are made by the launcher itself.
"""
import json
import fcntl
import hashlib
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
from urllib.parse import quote
from urllib.request import urlopen
from local_knowledge import dataset, verify as verify_knowledge

os.umask(0o077)
ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / "target/local-services"
DB = "deepresearch-memory-local-pg"
VOLUME = "deepresearch-memory-local-pgdata"
LABEL = "deepresearch.local.root"
PORTS = {"backend": 8080, "worker": 8091, "frontend": 5173, "database": 55433}


def command(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def private_json(path, value):
    temp = path.with_suffix(".tmp")
    with os.fdopen(os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
    temp.replace(path)


def owned(name):
    p = STATE / (name + ".json")
    if not p.exists():
        return None
    d = json.loads(p.read_text())
    try:
        current = command(["ps", "-p", str(d["pid"]), "-o", "lstart=", "-o", "command="])
    except subprocess.CalledProcessError:
        return None
    return d if current == d["identity"] else None


def free(port):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("127.0.0.1", port))
        except OSError:
            raise RuntimeError(f"端口 {port} 已被其他服务占用；未停止该服务。")


def ready(url, seconds=2):
    try:
        with urlopen(url, timeout=seconds) as r:
            return r.status == 200
    except Exception:
        return False


def launch(name, args, env, cwd, url):
    if owned(name):
        if not ready(url):
            raise RuntimeError(f"{name} 进程存在但未就绪，请先执行 stop，再 start。")
        print(f"复用已启动的 {name}", flush=True)
        return
    free(PORTS[name])
    log_path = STATE / (name + ".log")
    with os.fdopen(os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600), "a") as log:
        p = subprocess.Popen(args, env=env, cwd=cwd, stdout=log, stderr=log,
                             stdin=subprocess.DEVNULL, start_new_session=True)
    time.sleep(0.3)
    if p.poll() is not None:
        raise RuntimeError(f"{name} 启动失败，请查看 {log_path}")
    identity = command(["ps", "-p", str(p.pid), "-o", "lstart=", "-o", "command="])
    private_json(STATE / (name + ".json"), {"pid": p.pid, "identity": identity})
    for _ in range(180):
        if ready(url):
            print(f"{name} 已就绪", flush=True)
            return
        if p.poll() is not None:
            break
        time.sleep(1)
    raise RuntimeError(f"{name} 尚未就绪，请查看 {log_path}；已启动进程保留，可用 stop 停止。")


def database_owned():
    r = subprocess.run(["docker", "inspect", "--format", '{{ index .Config.Labels "' + LABEL + '" }}', DB],
                       text=True, capture_output=True)
    if r.returncode:
        return False
    if r.stdout.strip() != str(ROOT):
        raise RuntimeError("同名数据库归属不匹配，未操作该容器。")
    return True


def start():
    cfg_path = STATE / "config.json"
    if not cfg_path.exists():
        raise RuntimeError(f"缺少私有配置：{cfg_path}；按 scripts/LOCAL_SERVICES.md 配置。")
    cfg = json.loads(cfg_path.read_text())
    required = ["DEEPSEEK_API_KEY", "RAGFLOW_API_KEY", "RAGFLOW_DATASET_IDS", "POSTGRES_PASSWORD",
                "WORKFLOW_DB_PASSWORD", "DEEPRESEARCH_JWT_SECRET", "DEEPRESEARCH_INTERNAL_JWT_SECRET", "DEEPRESEARCH_MCP_JWT_SECRET"]
    if any(not cfg.get(k) or cfg[k] == "replace-me" for k in required) or not cfg.get("FRONTEND_DIR"):
        raise RuntimeError("私有配置存在缺失或占位值；未输出密钥。")
    for k in required:
        if not isinstance(cfg[k], str) or "\n" in cfg[k] or "\r" in cfg[k]:
            raise RuntimeError("配置值必须为单行字符串。")
    dataset(cfg, lambda updated: private_json(cfg_path, updated))
    cfg_hash = hashlib.sha256(json.dumps(cfg, sort_keys=True).encode()).hexdigest()
    runtime_cfg = STATE / "runtime-config.json"
    if owned("backend") and (not runtime_cfg.exists() or json.loads(runtime_cfg.read_text()).get("sha256") != cfg_hash):
        raise RuntimeError("运行中的后端配置与当前配置不一致，请先 stop，再 start。")
    frontend = Path(cfg["FRONTEND_DIR"]).resolve()
    if not (frontend / "package.json").exists():
        raise RuntimeError("FRONTEND_DIR 未指向当前 React 前端目录。")
    env = dict(os.environ)
    env.update({k: v for k, v in cfg.items() if isinstance(v, str) and k != "FRONTEND_DIR"})
    java_home = command(["/usr/libexec/java_home", "-v", "21"])
    env["JAVA_HOME"] = java_home
    if not shutil.which("node"):
        candidates = sorted((Path.home() / ".nvm/versions/node").glob("*/bin/node"), key=lambda p: p.stat().st_mtime)
        if candidates:
            env["PATH"] = str(candidates[-1].parent) + ":" + env["PATH"]
    npm = shutil.which("npm", path=env["PATH"])
    if not npm or not shutil.which("mvn"):
        raise RuntimeError("需要本机 Node/npm、Maven 和 Java 21。")
    command(["docker", "info", "--format", "{{.ServerVersion}}"])
    for name in ["backend", "worker", "frontend"]:
        if not owned(name):
            free(PORTS[name])
    if not owned("backend"):
        print("构建当前记忆版后端……", flush=True)
        with (STATE / "build.log").open("a") as log:
            subprocess.run(["mvn", "-q", "-DskipTests", "package"], cwd=ROOT, env=env,
                           stdout=log, stderr=log, check=True)
    if not database_owned():
        free(PORTS["database"])
        db_env = STATE / "database.env"
        with os.fdopen(os.open(db_env, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
            for k, v in {"POSTGRES_USER": "deepresearch", "POSTGRES_DB": "deepresearch",
                         "POSTGRES_PASSWORD": cfg["POSTGRES_PASSWORD"], "WORKFLOW_DB_PASSWORD": cfg["WORKFLOW_DB_PASSWORD"]}.items():
                f.write(k + "=" + v + "\n")
        command(["docker", "run", "-d", "--name", DB, "--label", LABEL + "=" + str(ROOT),
                 "--env-file", str(db_env), "-p", "127.0.0.1:55433:5432", "-v", VOLUME + ":/var/lib/postgresql/data",
                 "-v", str(ROOT / "docker/postgres/001-workflow-role.sh") + ":/docker-entrypoint-initdb.d/001-workflow-role.sh:ro",
                 "pgvector/pgvector:pg16"])
    else:
        command(["docker", "start", DB])
    for _ in range(60):
        r = subprocess.run(["docker", "exec", DB, "pg_isready", "-U", "deepresearch", "-d", "deepresearch"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if r.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError("本机数据库未就绪。")
    env.update({"SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": "8080", "SPRING_CONFIG_IMPORT": "",
                "SPRING_DATASOURCE_URL": "jdbc:postgresql://127.0.0.1:55433/deepresearch",
                "SPRING_DATASOURCE_USERNAME": "deepresearch", "SPRING_DATASOURCE_PASSWORD": cfg["POSTGRES_PASSWORD"],
                "DEEPRESEARCH_WORKFLOW_ENABLED": "true", "DEEPRESEARCH_WORKFLOW_ENGINE": "langgraph",
                "DEEPRESEARCH_AGENT_EVIDENCE_ENABLED": "true", "DEEPRESEARCH_DEV_TOKEN_ENABLED": "true",
                "DEEPRESEARCH_DEV_TOKEN_ALLOW_ADMIN": "false", "DEEPRESEARCH_RETRIEVAL_PROVIDER": "ragflow",
                "RAGFLOW_BASE_URL": cfg.get("RAGFLOW_BASE_URL", "http://127.0.0.1:9380"),
                "MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED": "false", "DEEPRESEARCH_MEMORY_SUMMARY_ENABLED": "true",
                "DEEPRESEARCH_MEMORY_CONTEXT_BUDGET_BYTES": "64000",
                "DEEPRESEARCH_MEMORY_AGENT_MAX_INPUT_TOKENS": "240000",
                "DEEPRESEARCH_MEMORY_AGENT_MAX_TOKENS": "200000",
                "DEEPRESEARCH_MEMORY_AGENT_MAX_COST_CNY": "2.0",
                "DEEPRESEARCH_MEMORY_AUTO_SAVE_ENABLED": "true", "DEEPRESEARCH_MEMORY_AUTO_SAVE_SCHEDULER_ENABLED": "true"})
    launch("backend", [java_home + "/bin/java", "-jar", str(ROOT / "target/deepresearch-0.0.1-SNAPSHOT.jar")],
           env, ROOT, "http://127.0.0.1:8080/actuator/health")
    private_json(runtime_cfg, {"sha256": cfg_hash})
    verify_knowledge(ROOT, cfg, STATE)
    worker_env = dict(env)
    worker_env.update({"WORKFLOW_DATABASE_URL": "postgresql://deepresearch_workflow:" + quote(cfg["WORKFLOW_DB_PASSWORD"], safe="") + "@127.0.0.1:55433/deepresearch",
                       "JAVA_BASE_URL": "http://127.0.0.1:8080", "MCP_URL": "http://127.0.0.1:8080/mcp/sse",
                       "RUNNER_ENABLED": "true", "MODEL_PROVIDER": "openai", "OPENAI_API_KEY": cfg["DEEPSEEK_API_KEY"],
                       "OPENAI_BASE_URL": "https://api.deepseek.com", "MODEL_NAME": cfg.get("MODEL_NAME", "deepseek-flash"),
                       "AGENT_RESULT_TRANSPORT": cfg.get("AGENT_RESULT_TRANSPORT", "function_call"),
                       "PYTHONPATH": str(ROOT / "workflow-service/src"),
                       "LANGGRAPH_STRICT_MSGPACK": "true", "MAX_CONCURRENT_RUNS": "1", "PYTHONDONTWRITEBYTECODE": "1"})
    python = ROOT / "workflow-service/.venv/bin/python"
    if not python.exists():
        raise RuntimeError("需要 workflow-service/.venv；在该目录执行 uv sync --locked。")
    launch("worker", [str(python), "-m", "uvicorn", "deepresearch_workflow.app:app", "--host", "127.0.0.1",
                      "--port", "8091", "--no-access-log"], worker_env, ROOT / "workflow-service",
           "http://127.0.0.1:8091/internal/health/ready")
    frontend_env = dict(os.environ)
    frontend_env["PATH"] = env["PATH"]
    frontend_env["DEEPRESEARCH_API_PROXY"] = "http://127.0.0.1:8080"
    if not (frontend / "node_modules/vite").exists():
        subprocess.run([npm, "ci"], cwd=frontend, env=frontend_env, check=True)
    launch("frontend", [npm, "run", "dev"], frontend_env, frontend, "http://127.0.0.1:5173/app/")
    print("启动完成：页面 http://127.0.0.1:5173/app/\n后端 http://127.0.0.1:8080\n日志 " + str(STATE), flush=True)


def stop():
    for name in ["frontend", "worker", "backend"]:
        d = owned(name)
        if d:
            os.killpg(d["pid"], signal.SIGTERM)
            for _ in range(120):
                if not owned(name):
                    break
                time.sleep(0.5)
            if owned(name):
                raise RuntimeError(f"{name} 尚未退出，请查看日志。")
        print(f"{name} 已停止或未运行")
    if database_owned():
        command(["docker", "stop", DB])
    print("本次服务已停止，数据库卷和私有配置保留。")


def status():
    for name, url in {"backend": "http://127.0.0.1:8080/actuator/health",
                      "worker": "http://127.0.0.1:8091/internal/health/ready",
                      "frontend": "http://127.0.0.1:5173/app/"}.items():
        print(name + ": " + ("已就绪" if owned(name) and ready(url) else "未运行或未就绪"))
    if database_owned():
        print("database: " + command(["docker", "inspect", "--format", "{{.State.Status}}", DB]))
    if owned("backend") and ready("http://127.0.0.1:8080/actuator/health"):
        cfg = json.loads((STATE / "config.json").read_text())
        verify_knowledge(ROOT, cfg, STATE, initialize=False)


if __name__ == "__main__":
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    action = sys.argv[1] if len(sys.argv) == 2 else "start" if len(sys.argv) == 1 else "invalid"
    if action not in {"start", "stop", "status"}:
        sys.exit("用法：bash scripts/local-services.sh [start|stop|status]")
    try:
        with (STATE / "operation.lock").open("a") as lock:
            if action != "status":
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    raise RuntimeError("已有启动或停止操作正在执行，请稍后查看 status。")
            {"start": start, "stop": stop, "status": status}[action]()
    except (RuntimeError, subprocess.CalledProcessError, OSError) as e:
        print("操作未完成：" + (str(e) if isinstance(e, RuntimeError) else "请检查依赖及 target/local-services 日志。"), file=sys.stderr)
        sys.exit(1)

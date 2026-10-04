#!/usr/bin/env python3
"""Own one isolated live-validation environment; never operate showcase resources."""
import argparse
import ipaddress
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import tarfile
import time
import uuid
import zipfile
from urllib.request import urlopen

from agent_live_common import file_sha, http_json, read_private, source_digest, verify_runtime, write_private
from agent_retest_batch import ACTION_RECOVERY_WEB_BATCH, OBLIGATION_ALIGNMENT_WEB_BATCH, BATCH, BATCHES, DIAGNOSTICS_WEB_BATCH, JSON_DIAGNOSTICS_WEB_BATCH, JSON_WEB_BATCH, JSON_WEB_BATCHES, POST_IDENTITY_BATCH, SEGMENTS_WEB_BATCH, V22_WEB_BATCH
from agent_acceptance_v22 import candidate_schema_policy, verify_database_schema

ISOLATION = "agent-live-20260930"
CONTAINER = "deepresearch-agent-live-20260930-pg"
APP_CONTAINER = "deepresearch-agent-live-20260930-app"
NETWORK = "deepresearch-agent-live-20260930"
VOLUME = "deepresearch-agent-live-20260930-pgdata"
PORTS = {"app": 18080, "sidecar": 18090, "database": 15432}


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def database(credentials):
    import psycopg
    return psycopg.connect(host="127.0.0.1", port=PORTS["database"], dbname="deepresearch",
                          user="deepresearch", password=credentials["POSTGRES_PASSWORD"])


def build(root, state, sha, batch_id=BATCH):
    if batch_id not in BATCHES:
        raise ValueError("Unknown explicitly authorized batch")
    record_path = state / ("build.json" if batch_id == BATCH else batch_id + "-build.json")
    if record_path.exists():
        raise ValueError("Existing batch build record must be reconciled, never overwritten")
    if git(root, "rev-parse", sha).decode().strip() != sha:
        raise ValueError("Build requires an exact Git commit")
    directory = state / ("build-" + sha[:12])
    source = directory / "source"
    if directory.exists():
        raise ValueError("Build directory already exists; preserve it and use a new commit")
    source.mkdir(parents=True)
    os.chmod(directory, 0o700)
    archive = git(root, "archive", "--format=tar", sha)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        tar.extractall(source, filter="data")
    blobs = {}
    for entry in git(root, "ls-tree", "-rz", sha).split(b"\0"):
        if not entry:
            continue
        meta, name = entry.split(b"\t", 1)
        mode, kind, blob = meta.decode().split()
        name = name.decode()
        if (source / name).is_file():
            if kind != "blob" or mode not in {"100644", "100755"}:
                raise ValueError("Unsupported source archive entry")
            blobs[name] = blob
    manifest = directory / "source-manifest.json"
    write_private(manifest, {"code_sha": sha, "blobs": blobs})
    digest = file_sha(manifest)
    # Build from the committed archive. The stamped identity is compiled into this JAR.
    with (directory / "build.log").open("w") as log:
        result = subprocess.run(["mvn", "-o", "-q", "-Pagent-live", "-DskipTests",
                                 "-Dagent.build.revision=" + sha,
                                 "-Dagent.build.source-manifest-sha256=" + digest,
                                 "-Dagent.build.isolation-id=" + ISOLATION, "package"],
                                cwd=source, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise ValueError("Isolated artifact build failed; inspect protected build.log")
    jar = source / "target/deepresearch-0.0.1-SNAPSHOT.jar"
    with zipfile.ZipFile(jar) as z:
        entries = [p for p in z.namelist() if p in {
            "BOOT-INF/classes/META-INF/build-info.properties", "META-INF/build-info.properties"}]
        if len(entries) != 1:
            raise ValueError("Executable JAR must contain one build identity resource")
        properties = z.read(entries[0]).decode()
        if "build.revision=" + sha not in properties or "build.source-manifest-sha256=" + digest not in properties:
            raise ValueError("Built artifact is missing the exact source identity")
    output = {"build_sha": sha, "git_repository": str(root.resolve()), "source_archive": str(source), "source_manifest_path": str(manifest),
              "source_manifest_sha256": digest, "jar_path": str(jar), "jar_sha256": file_sha(jar),
              "sidecar_source_sha256": source_digest(source / "workflow-service/src")}
    write_private(record_path, output)
    return output


def wait_health(base, path, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            value = http_json(base, path)
            if value.get("status") == "UP":
                return value
        except ValueError:
            pass
        time.sleep(1)
    raise ValueError("Isolated service did not become healthy within the preparation window")


def start(state, credentials, built, python, ready_path, sources, model_name, batch_id=BATCH, agent_result_transport="function_call"):
    schema_policy = candidate_schema_policy(built)
    if agent_result_transport not in {"function_call", "deepseek_json_object"} or (
            agent_result_transport == "deepseek_json_object" and model_name != "deepseek-flash"):
        raise ValueError("Unsupported Agent result transport capability")
    if model_name not in {"deepseek-flash", "deepseek-v4-flash"}:
        raise ValueError("Explicit supported request model required; retired aliases do not pin V4")
    if batch_id in JSON_WEB_BATCHES and agent_result_transport != "deepseek_json_object":
        raise ValueError("JSON web batch requires explicit single-object transport")
    if batch_id not in BATCHES or (batch_id != BATCH and model_name != "deepseek-flash"):
        raise ValueError("New post-identity batch requires the explicit canonical request model")
    token_record = state / ("api-token.json" if batch_id == BATCH else batch_id + "-api-token.json")
    if ready_path.exists() or token_record.exists():
        raise ValueError("Existing batch runtime metadata must be reconciled, never overwritten")
    existing = subprocess.run(["docker", "inspect", CONTAINER], capture_output=True)
    database_exists = existing.returncode == 0
    if database_exists:
        current = json.loads(existing.stdout)[0]
        if (current["Config"].get("Labels") or {}).get("deepresearch.validation.owner") != ISOLATION:
            raise ValueError("Existing database is owned by another environment")
        if VOLUME not in [m.get("Name") for m in current["Mounts"]]:
            raise ValueError("Existing database volume does not match this validation")
        if current["HostConfig"]["PortBindings"].get("5432/tcp") != [{"HostIp": "127.0.0.1", "HostPort": "15432"}]:
            raise ValueError("Existing database is not on this validation's loopback port")
        if not current["State"]["Running"]:
            subprocess.run(["docker", "start", CONTAINER], check=True, capture_output=True)
    for port in ([PORTS["app"], PORTS["sidecar"]] if database_exists else PORTS.values()):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    # Names are dedicated to this validation. Refuse to reuse an unaccounted resource.
    env_file = state / "postgres.env"
    source = Path(built["source_archive"])
    if not database_exists:
        subprocess.run(["docker", "volume", "create", "--label", "deepresearch.validation.owner=" + ISOLATION,
                        VOLUME], check=True, capture_output=True)
        subprocess.run(["docker", "network", "create", "--label", "deepresearch.validation.owner=" + ISOLATION,
                        NETWORK], check=True, capture_output=True)
        descriptor = os.open(env_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write("POSTGRES_USER=deepresearch\nPOSTGRES_DB=deepresearch\n")
            for key in ["POSTGRES_PASSWORD", "WORKFLOW_DB_PASSWORD"]:
                stream.write(key + "=" + credentials[key] + "\n")
        subprocess.run(["docker", "run", "-d", "--pull=never", "--name", CONTAINER,
                    "--label", "deepresearch.validation.owner=" + ISOLATION,
                    "--network", NETWORK,
                    "--env-file", str(env_file), "-p", "127.0.0.1:15432:5432",
                    "--mount", "type=volume,source=" + VOLUME + ",target=/var/lib/postgresql/data",
                    "--mount", "type=bind,source=" + str(source / "docker/postgres/001-workflow-role.sh")
                    + ",target=/docker-entrypoint-initdb.d/001-workflow-role.sh,readonly",
                        "pgvector/pgvector:pg16"], check=True, capture_output=True)
    deadline = time.monotonic() + 45
    while True:
        try:
            with database(credentials) as conn:
                conn.execute("SELECT 1")
            break
        except Exception:
            if time.monotonic() >= deadline:
                raise ValueError("Dedicated database did not become available") from None
            time.sleep(1)
    datasets = sources.get("ragflow_dataset_ids", []) if sources else []
    datasets = datasets or credentials.get("RAGFLOW_DATASET_IDS", "").split(",")
    if not any(datasets):
        raise ValueError("RAGFLOW_DATASET_IDS is required for RAGFlow configuration")
    env = {}
    env.update({k: v for k, v in credentials.items() if k in {
        "DEEPSEEK_API_KEY", "ZHIPU_API_KEY", "TAVILY_API_KEY", "RAGFLOW_API_KEY",
        "DEEPRESEARCH_JWT_SECRET", "DEEPRESEARCH_INTERNAL_JWT_SECRET", "DEEPRESEARCH_MCP_JWT_SECRET"}})
    env.update({"SERVER_ADDRESS": "0.0.0.0", "SERVER_PORT": "18080", "SPRING_CONFIG_IMPORT": "",
                "SPRING_DATASOURCE_URL": "jdbc:postgresql://" + CONTAINER + ":5432/deepresearch",
                "SPRING_DATASOURCE_USERNAME": "deepresearch",
                "SPRING_DATASOURCE_PASSWORD": credentials["POSTGRES_PASSWORD"],
                "DEEPRESEARCH_WORKFLOW_ENABLED": "true", "DEEPRESEARCH_AGENT_EVIDENCE_ENABLED": "true",
                "DEEPRESEARCH_DEV_TOKEN_ENABLED": "true", "DEEPRESEARCH_RETRIEVAL_PROVIDER": "ragflow",
                "RAGFLOW_BASE_URL": "http://host.docker.internal:9380", "RAGFLOW_DATASET_IDS": ",".join(datasets),
                "RAGFLOW_QUERY_EXPANSION_ENABLED": "false", "MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED": "false",
                "LOGGING_LEVEL_COM_DEEPRESEARCH": "INFO", "DEEPRESEARCH_MEMORY_SUMMARY_ENABLED": "false"})
    app_env = state / ("app-" + built["build_sha"][:12] + ".env")
    descriptor = os.open(app_env, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        for key, value in env.items():
            stream.write(key + "=" + value + "\n")
    base_info = json.loads(subprocess.check_output(["docker", "image", "inspect",
                                                    "deepresearch-release-20260928-app"]))[0]
    base_image = base_info["Id"]
    if any(any(part in line.split("=", 1)[0] for part in ["KEY", "SECRET", "PASSWORD"])
           and line.split("=", 1)[-1] for line in base_info["Config"].get("Env", [])):
        raise ValueError("Base image contains embedded credentials; cannot use for validation")
    image = "deepresearch-agent-live-20260930:" + built["build_sha"][:12]
    staging = "deepresearch-agent-live-20260930-image-" + built["build_sha"][:12]
    # A never-started, credential-free staging container avoids registry lookups and
    # records an immutable local base image ID. Only the committed JAR is replaced.
    subprocess.run(["docker", "create", "--name", staging, "--network", "none",
                    "--label", "deepresearch.validation.owner=" + ISOLATION, base_image],
                   check=True, capture_output=True)
    subprocess.run(["docker", "cp", built["jar_path"], staging + ":/app/deepresearch.jar"],
                   check=True, capture_output=True)
    subprocess.run(["docker", "commit", staging, image], check=True, capture_output=True)
    subprocess.run(["docker", "rm", staging], check=True, capture_output=True)
    # Host VPN DNS synthesizes non-public addresses. Override only this reviewed public
    # source inside this dedicated container, using fresh public DNS, with unchanged TLS/pinning.
    with urlopen("https://dns.google/resolve?name=www.iana.org&type=A", timeout=10) as response:
        dns = json.loads(response.read(10000))
    addresses = [r["data"] for r in dns.get("Answer", []) if r.get("type") == 1]
    if not addresses or not all(ipaddress.ip_address(ip).is_global for ip in addresses):
        raise ValueError("Reviewed web source did not resolve to public addresses")
    command = ["docker", "run", "-d", "--pull=never", "--name", APP_CONTAINER,
               "--label", "deepresearch.validation.owner=" + ISOLATION,
               "--label", "deepresearch.validation.code-sha=" + built["build_sha"],
               "--network", NETWORK, "--env-file", str(app_env),
               "-p", "127.0.0.1:18080:18080", "--add-host", "www.iana.org:" + addresses[0], image]
    subprocess.run(command, check=True, capture_output=True)
    image_id = json.loads(subprocess.check_output(["docker", "image", "inspect", image]))[0]["Id"]
    ready = {**built, "phase": "starting", "ready": False, "isolation_id": ISOLATION,
             "batch_id": batch_id, "historical_state_dir": str(state.resolve()),
             "run_manifest_path": str((ready_path.parent / ({OBLIGATION_ALIGNMENT_WEB_BATCH: "agent-live-obligation-alignment-web-runs-20261005.json", ACTION_RECOVERY_WEB_BATCH: "agent-live-action-recovery-web-runs-20261004.json", JSON_DIAGNOSTICS_WEB_BATCH: "agent-live-json-diagnostics-web-runs-20261004.json", SEGMENTS_WEB_BATCH: "agent-live-segments-web-runs-20261004.json", DIAGNOSTICS_WEB_BATCH: "agent-live-diagnostics-web-runs-20261004.json", V22_WEB_BATCH: "agent-live-v22-web-runs-20261004.json", JSON_WEB_BATCH: "agent-live-json-web-runs-20261003.json", POST_IDENTITY_BATCH: "agent-live-post-identity-runs-20261003.json"}.get(batch_id, "agent-live-retest-runs-20261003.json"))).resolve()),
             "scenario_manifest_path": str((source / "testdata/agent-live/sources/scenarios.json").resolve()),
             "scenario_manifest_sha256": file_sha(source / "testdata/agent-live/sources/scenarios.json"),
             "ci": None, "research_runs_submitted": 0,
             "app_container": APP_CONTAINER, "app_image_id": image_id, "base_image_id": base_image,
             "web_dns_mapping": {"host": "www.iana.org", "public_ipv4": addresses,
                                 "provider": "https://dns.google/resolve", "scope": "dedicated container only",
                                 "observed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                                 "formal_dns_unchanged": True, "safe_web_reader_unchanged": True},
             "database_container": CONTAINER, "database_volume": VOLUME,
             "database_port": PORTS["database"], "app_base_url": "http://127.0.0.1:18080",
             "sidecar_base_url": "http://127.0.0.1:18090", "formal_services_unchanged": True}
    write_private(ready_path, ready)
    wait_health(ready["app_base_url"], "/actuator/health")
    with database(credentials) as conn:
        conn.execute("CREATE SCHEMA IF NOT EXISTS langgraph AUTHORIZATION deepresearch_workflow")
        schema_verification = verify_database_schema(conn, schema_policy)
        migrations = schema_verification["migration_version"]
    ready["schema_verification"] = schema_verification
    token = http_json(ready["app_base_url"], "/api/auth/dev-token", body={
        "tenantId": "agent-live-validation", "userId": "acceptance-20260930",
        "roles": ["USER"], "ttlSeconds": 86400})["token"]
    token_path = state / ("api-token.json" if batch_id == BATCH else batch_id + "-api-token.json")
    write_private(token_path, {"token": token})
    side_env = dict(os.environ)
    side_env.update({"WORKFLOW_DATABASE_URL": "postgresql://deepresearch_workflow:" + credentials["WORKFLOW_DB_PASSWORD"] + "@127.0.0.1:15432/deepresearch",
                     "JAVA_BASE_URL": ready["app_base_url"], "MCP_URL": ready["app_base_url"] + "/mcp/sse",
                     "DEEPRESEARCH_INTERNAL_JWT_SECRET": credentials["DEEPRESEARCH_INTERNAL_JWT_SECRET"],
                     "RUNNER_ENABLED": "true", "MODEL_PROVIDER": "openai", "MODEL_NAME": model_name,
                     "AGENT_RESULT_TRANSPORT": agent_result_transport,
                     "OPENAI_API_KEY": credentials["DEEPSEEK_API_KEY"], "OPENAI_BASE_URL": "https://api.deepseek.com",
                     "LANGGRAPH_STRICT_MSGPACK": "true", "MAX_CONCURRENT_RUNS": "1", "WORKER_MAX_CONCURRENCY": "1",
                     "PYTHONDONTWRITEBYTECODE": "1"})
    identity_path = state / ("sidecar-identity-" + built["build_sha"][:12] + ".json")
    side_log = (state / ("sidecar.log" if batch_id == BATCH else batch_id + "-sidecar.log")).open("a")
    side = subprocess.Popen([str(python), str(source / "scripts/agent-live-sidecar.py"),
                             "--source-dir", str(source / "workflow-service/src"),
                             "--source-sha256", built["sidecar_source_sha256"],
                             "--identity-output", str(identity_path), "--port", "18090"],
                            cwd=source / "workflow-service", env=side_env, stdin=subprocess.DEVNULL,
                            stdout=side_log, stderr=subprocess.STDOUT, start_new_session=True)
    ready.update({"sidecar_pid": side.pid, "sidecar_identity_path": str(identity_path),
                  "token_access": {"kind": "protected_json_file", "path": str(token_path), "mode": "0600"},
                  "credential_access": {"kind": "protected_json_file", "path": str(state / "credentials.json"), "mode": "0600"},
                  "model": {"provider": "deepseek-openai-compatible", "name": model_name,
                            "adapter": "OpenAIAgentModel", "actual_cost": None},
                  "model_identity": {"provider": "deepseek-openai-compatible", "name": model_name,
                                     "adapter": "OpenAIAgentModel", "endpoint": "https://api.deepseek.com"},
                  "ragflow_dataset_ids": datasets, "baseline_sha": "62e29a77130f727413558140a6c8872410a0b731",
                  "migration_version": migrations, "max_research_runs": 1 if batch_id in JSON_WEB_BATCHES else 5 if batch_id == POST_IDENTITY_BATCH else 8, "limits": {"decisions": 8, "models": 16, "tools": 16, "seconds": 180, "input_tokens": 64000, "output_tokens": 16384},
                  "registry_configured": False, "real_research_runs_started": 0})
    write_private(ready_path, ready)
    wait_health(ready["sidecar_base_url"], "/internal/health/ready")
    sidecar_identity = read_private(identity_path)
    ready["model_identity_receipts_path"] = sidecar_identity["model_identity_receipts_path"]
    if (sidecar_identity["model_identity"]["name"] != model_name
            or sidecar_identity["model_identity"]["endpoint"] != "https://api.deepseek.com"
            or sidecar_identity["model_identity"].get("result_transport") != agent_result_transport):
        raise ValueError("Actual sidecar request configuration differs")
    ready["model_identity"] = sidecar_identity["model_identity"]
    if batch_id in {SEGMENTS_WEB_BATCH, JSON_DIAGNOSTICS_WEB_BATCH, ACTION_RECOVERY_WEB_BATCH, OBLIGATION_ALIGNMENT_WEB_BATCH}:
        ready["planner_identity"] = sidecar_identity["planner_identity"]
    if batch_id in {JSON_DIAGNOSTICS_WEB_BATCH, ACTION_RECOVERY_WEB_BATCH, OBLIGATION_ALIGNMENT_WEB_BATCH}:
        ready["json_diagnostic_identity"] = sidecar_identity["json_diagnostic_identity"]
    if batch_id in {ACTION_RECOVERY_WEB_BATCH, OBLIGATION_ALIGNMENT_WEB_BATCH}:
        ready["continuation_identity"] = sidecar_identity["continuation_identity"]
    if batch_id == OBLIGATION_ALIGNMENT_WEB_BATCH:
        ready["obligation_identity"] = sidecar_identity["obligation_identity"]
    ready["build_verification"] = verify_runtime({**ready, "ready": True}, token)
    ready.update({"ready": True, "phase": "runtime_ready_sources_pending"})
    write_private(ready_path, ready)
    return ready


def register_sources(credentials, ready, sources):
    if not sources.get("ready"):
        raise ValueError("B sources are not ready")
    ids = set(sources["ragflow_dataset_ids"])
    if not ids.issubset(set(ready["ragflow_dataset_ids"])):
        raise ValueError("New source dataset requires an explicit isolated application reconfiguration")
    documents = sources["registry_documents"]
    with database(credentials) as conn:
        for doc in documents:
            if doc["dataset_id"] not in ids or doc["source_classification"] not in {"real-public", "real-public-curated", "synthetic"}:
                raise ValueError("Only B-reviewed public or synthetic source documents are allowed")
            legacy = "live-" + uuid.uuid5(uuid.NAMESPACE_URL, doc["dataset_id"] + ":" + doc["document_id"]).hex
            conn.execute("""INSERT INTO kb_document(doc_id,title,source_type,filename,raw_content,content_hash,version,chunk_count,status)
                VALUES (%s,%s,'agent-live-acceptance',%s,'',%s,1,1,'DONE') ON CONFLICT (doc_id) DO NOTHING""",
                         (legacy, doc["title"], doc["title"], doc["sha256"]))
            conn.execute("""INSERT INTO kb_ragflow_document(legacy_doc_id,dataset_id,document_id,version,content_hash,sync_status)
                VALUES (%s,%s,%s,1,%s,'DONE') ON CONFLICT (legacy_doc_id) DO NOTHING""",
                         (legacy, doc["dataset_id"], doc["document_id"], doc["sha256"]))
    ready.update({"registry_configured": True, "registry_document_count": len(documents),
                  "tested_peer_sha": sources["final_sha"], "phase": "ready_for_serial_research"})
    return ready


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--runtime-ready", type=Path, required=True)
    parser.add_argument("--build-sha")
    parser.add_argument("--start", action="store_true")
    parser.add_argument("--batch", choices=BATCHES, default=BATCH)
    parser.add_argument("--model-name", choices=["deepseek-flash", "deepseek-v4-flash"],
                        help="Explicit request identity; legacy names route to V4.1 and do not pin retired V4")
    parser.add_argument("--agent-result-transport", choices=["function_call", "deepseek_json_object"],
                        default="function_call", help="Explicit internal Agent structured result transport")
    parser.add_argument("--python", type=Path)
    parser.add_argument("--sources-ready", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    state = args.state_dir.resolve()
    state.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(state, 0o700)
    credentials = read_private(state / "credentials.json")
    sources = json.loads(args.sources_ready.read_text()) if args.sources_ready else None
    if args.build_sha:
        built = build(root, state, args.build_sha, args.batch)
        print(json.dumps({"artifact_built": True, "build_sha": built["build_sha"], "jar_sha256": built["jar_sha256"]}), flush=True)
    else:
        built = read_private(state / ("build.json" if args.batch == BATCH else args.batch + "-build.json"))
    if args.start:
        if not args.python or not args.model_name:
            raise ValueError("--python and explicit --model-name are required to start the sidecar")
        ready = start(state, credentials, built, args.python, args.runtime_ready, sources, args.model_name, args.batch, args.agent_result_transport)
    else:
        ready = read_private(args.runtime_ready) if args.runtime_ready.exists() else None
    if sources and ready and sources.get("ready"):
        ready = register_sources(credentials, ready, sources)
        write_private(args.runtime_ready, ready)
    if ready:
        print(json.dumps({"ready": ready["ready"], "phase": ready["phase"], "build_sha": ready.get("build_sha")}), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Provider/configuration values are never included in preparation errors.
        raise SystemExit(type(error).__name__ + ": isolated preparation failed; inspect protected logs") from None

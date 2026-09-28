#!/usr/bin/env python3
"""Check local showcase configuration and optionally authenticated dependencies."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from showcase_env import load_env


ROOT = Path(__file__).resolve().parents[1]
SECRETS = ("DEEPRESEARCH_JWT_SECRET", "DEEPRESEARCH_INTERNAL_JWT_SECRET",
           "DEEPRESEARCH_MCP_JWT_SECRET", "WORKFLOW_DB_PASSWORD",
           "DEEPRESEARCH_WORKFLOW_DIFY_TOOL_SERVICE_TOKEN")


def configured(value):
    return bool(value and value != "replace-me")


def request_json(url, token=None, body=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None if body is None else json.dumps(body).encode()
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, headers=headers, data=data)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        # Never print response bodies, URLs, authorization or dataset identifiers.
        raise ValueError(f"HTTP {error.code}") from None
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        raise ValueError("unreachable dependency or invalid JSON response") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--online", action="store_true", help="GET Java health, RAGFlow datasets and Dify parameters")
    parser.add_argument("--corpus", action="store_true", help="also verify eight canonical Java documents and DONE RAGFlow mappings")
    parser.add_argument("--base-url", default=None)
    args = parser.parse_args()
    try:
        env = load_env(args.env_file.resolve())
    except (OSError, ValueError) as error:
        parser.exit(1, f"FAIL configuration: {error}\n")
    failures = []

    def check(name, action):
        try:
            action()
            print("PASS " + name)
        except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
            failures.append(name)
            print(f"FAIL {name}: {error}")

    def config():
        required = (*SECRETS, "DEEPSEEK_API_KEY", "RAGFLOW_API_KEY", "RAGFLOW_DATASET_IDS",
                    "DEEPRESEARCH_DIFY_APP_KEY", "ZHIPU_API_KEY")
        missing = [key for key in required if not configured(env.get(key))]
        if missing:
            raise ValueError("Set " + ", ".join(missing))
        if any(len(env[key].encode()) < 32 for key in SECRETS):
            raise ValueError("All five local service secrets must contain at least 32 bytes")
        if len({env[key] for key in SECRETS}) != len(SECRETS):
            raise ValueError("Generate five distinct local service secrets")
        expected = {"DEEPRESEARCH_RETRIEVAL_PROVIDER": "ragflow",
                    "DEEPRESEARCH_WORKFLOW_ENGINE": "dify", "DEEPRESEARCH_WORKFLOW_ENABLED": "true"}
        if any(env.get(key, "").lower() != value for key, value in expected.items()):
            raise ValueError("Set retrieval=ragflow, workflow engine=dify and workflow enabled=true")
        for key in ("RAGFLOW_BASE_URL", "DEEPRESEARCH_DIFY_BASE_URL"):
            url = urllib.parse.urlparse(env.get(key, ""))
            if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password:
                raise ValueError("Set a plain HTTP(S) endpoint for " + key)
            if url.hostname in ("localhost", "127.0.0.1", "::1"):
                raise ValueError(key + " must be reachable from the Java container")

    def compose():
        result = subprocess.run(
            ["docker", "compose", "--env-file", str(args.env_file.resolve()),
             "-f", "docker-compose.yml", "-f", "docker-compose.showcase.yml", "config", "--format", "json"],
            cwd=ROOT, env=env, capture_output=True, timeout=30,
        )
        if result.returncode:
            raise ValueError("Compose config failed; use Compose >= 2.24.4 and check the two YAML files")
        app = json.loads(result.stdout)["services"]["app"]
        if set(app.get("depends_on", {})) != {"workflow-db-bootstrap"}:
            raise ValueError("Showcase must depend only on the database role bootstrap")
        selected = app["environment"]
        if (selected.get("DEEPRESEARCH_RETRIEVAL_PROVIDER") != "ragflow"
                or selected.get("DEEPRESEARCH_WORKFLOW_ENGINE") != "dify"
                or selected.get("MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED") != "false"):
            raise ValueError("Compose must select ragflow/dify and disable legacy ES health")

    check("local configuration (values hidden)", config)
    check("Compose showcase dependency graph", compose)
    if failures:
        parser.exit(1, "Configuration preflight failed; no online requests were sent.\n")
    if args.online or args.corpus:
        java = (args.base_url or f"http://127.0.0.1:{env.get('DEEPRESEARCH_APP_PORT', '8080')}").rstrip("/")

        def health():
            if request_json(java + "/actuator/health").get("status") != "UP":
                raise ValueError("Java health is not UP")

        def datasets():
            base = env.get("SHOWCASE_RAGFLOW_URL", "http://127.0.0.1:9380").rstrip("/")
            for dataset in env["RAGFLOW_DATASET_IDS"].split(","):
                query = urllib.parse.urlencode({"id": dataset.strip(), "page_size": 100})
                reply = request_json(base + "/api/v1/datasets?" + query, env["RAGFLOW_API_KEY"])
                if reply.get("code") != 0 or not any(row.get("id") == dataset.strip() for row in reply.get("data", [])):
                    raise ValueError("A configured dataset is missing or inaccessible")

        check("Java health", health)
        check("RAGFlow key and configured dataset access", datasets)
        check("Dify published App Key access", lambda: request_json(
            env.get("SHOWCASE_DIFY_URL", "http://127.0.0.1:8081/v1").rstrip("/") + "/parameters",
            env["DEEPRESEARCH_DIFY_APP_KEY"],
        ))
        if args.corpus:
            def corpus():
                token = env.get("DEEPRESEARCH_ADMIN_TOKEN")
                if not token:
                    raise ValueError("Set DEEPRESEARCH_ADMIN_TOKEN for the corpus check")
                documents = request_json(java + "/api/kb/documents", token)
                files = sorted((ROOT / "docs/kb-project").glob("*.md"))
                if len(files) != 8:
                    raise ValueError("Canonical showcase corpus must have exactly eight Markdown files")
                for file in files:
                    digest = hashlib.sha256(file.read_bytes()).hexdigest()
                    matches = [row for row in documents if row.get("filename") == file.name and row.get("contentHash") == digest]
                    if len(matches) != 1:
                        raise ValueError("Missing, stale or duplicate canonical document: " + file.name)
                    row = matches[0]
                    state = request_json(java + "/api/kb/documents/" + urllib.parse.quote(row["docId"], safe="") + "/ragflow-sync", token)
                    if state.get("status") != "DONE":
                        raise ValueError("RAGFlow mapping is not DONE: " + file.name)
                    detail = request_json(java + "/api/kb/documents/" + urllib.parse.quote(row["docId"], safe=""), token)
                    if detail.get("document", {}).get("status") != "DONE":
                        raise ValueError("Java document is not DONE: " + file.name)
                print("  Eight canonical content hashes and DONE mappings matched; other documents may coexist.")
            check("project corpus", corpus)
    if failures:
        parser.exit(1, f"{len(failures)} dependency check(s) failed.\n")
    print("Preflight passed. This does not verify workflow answers, model quality or SSE recovery.")


if __name__ == "__main__":
    main()

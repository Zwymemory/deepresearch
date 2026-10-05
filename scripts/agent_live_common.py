"""Local validation helpers. Secret values are only read into process memory."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

LIMITS = {"decisions": 8, "models": 16, "tools": 16, "seconds": 180}
TERMINAL = {"SUCCEEDED", "INSUFFICIENT_EVIDENCE", "FAILED", "CANCELLED",
            "TIMED_OUT", "BUDGET_EXCEEDED"}


def read_private(path):
    path = Path(path)
    mode = path.stat()
    if mode.st_uid != os.getuid() or stat.S_IMODE(mode.st_mode) != 0o600:
        raise ValueError("Protected configuration must be owned by this user with mode 0600")
    return json.loads(path.read_text())


def write_private(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".new")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.chmod(temporary, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, default=str)
        stream.write("\n")
    temporary.replace(path)


def file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def source_digest(source):
    source = Path(source)
    rows = [[str(p.relative_to(source)), file_sha(p)] for p in sorted(source.rglob("*.py"))]
    return hashlib.sha256(json.dumps(rows, separators=(",", ":")).encode()).hexdigest()


def http_json(base, path, token=None, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    raw = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    try:
        with urlopen(Request(base.rstrip("/") + path, data=raw, headers=headers), timeout=10) as response:
            data = response.read(500001)
            if len(data) > 500000:
                raise ValueError("Local API response exceeded validation capacity")
            return json.loads(data)
    except HTTPError as error:
        # Do not print response bodies or authenticated request headers.
        raise ValueError("Local API rejected request: HTTP " + str(error.code)) from None
    except (URLError, OSError):
        raise ValueError("Local validation service unavailable") from None


def assert_process(pid, required_args):
    command = subprocess.check_output(["ps", "-p", str(pid), "-o", "command="], text=True).strip()
    if not all(str(argument) in command for argument in required_args):
        raise ValueError("Running process does not match the recorded artifact")


def verify_runtime(ready, token):
    if not ready.get("ready") or ready.get("formal_services_unchanged") is not True:
        raise ValueError("Isolated runtime is not ready")
    if ready["app_base_url"] != "http://127.0.0.1:18080":
        raise ValueError("Only the dedicated loopback application is allowed")
    if not re.fullmatch(r"[a-f0-9]{40}", ready["build_sha"]):
        raise ValueError("An exact committed build is required")
    if file_sha(ready["jar_path"]) != ready["jar_sha256"]:
        raise ValueError("Running application artifact changed")
    application = json.loads(subprocess.check_output(["docker", "inspect", ready["app_container"]]))[0]
    labels = application["Config"].get("Labels") or {}
    if labels.get("deepresearch.validation.owner") != ready["isolation_id"] or application["Image"] != ready["app_image_id"]:
        raise ValueError("Running application container belongs to another build")
    actual_jar = subprocess.check_output(["docker", "exec", ready["app_container"],
                                          "sha256sum", "/app/deepresearch.jar"], text=True).split()[0]
    if actual_jar != ready["jar_sha256"]:
        raise ValueError("Running container contains a different JAR")
    bindings = application["NetworkSettings"]["Ports"].get("18080/tcp", [])
    if bindings != [{"HostIp": "127.0.0.1", "HostPort": "18080"}]:
        raise ValueError("Application is not published on its dedicated loopback port")
    info = http_json(ready["app_base_url"], "/actuator/info", token)
    build = info.get("build", {})
    for key, expected in {"revision": ready["build_sha"],
                          "source-manifest-sha256": ready["source_manifest_sha256"],
                          "isolation-id": ready["isolation_id"]}.items():
        if build.get(key) != expected:
            raise ValueError("Application reported a different embedded build identity")
    manifest = read_private(ready["source_manifest_path"])
    if file_sha(ready["source_manifest_path"]) != ready["source_manifest_sha256"]:
        raise ValueError("Source manifest changed")
    source = Path(ready["source_archive"])
    for name, expected in manifest["blobs"].items():
        data = (source / name).read_bytes()
        actual = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        if actual != expected:
            raise ValueError("Verified source archive changed")
    identity = read_private(ready["sidecar_identity_path"])
    if identity["fixtures"] or identity["model_class"] != "OpenAIAgentModel":
        raise ValueError("Real Agent model adapter required")
    assert_process(ready["sidecar_pid"], ["agent-live-sidecar.py", identity["source_dir"]])
    if identity["pid"] != ready["sidecar_pid"] or source_digest(identity["source_dir"]) != ready["sidecar_source_sha256"]:
        raise ValueError("Sidecar process or source does not match the build")
    if ("json_diagnostic_identity" in ready and ready["json_diagnostic_identity"]
            != identity.get("json_diagnostic_identity")):
        raise ValueError("Actual parser diagnostic identity differs from readiness")
    if "obligation_identity" in ready:
        obligation = ready["obligation_identity"]
        if obligation != identity.get("obligation_identity"):
            raise ValueError("Actual obligation implementation differs from readiness")
        if any(obligation.get(k) != v for k, v in {
            "planner_contract": "agent-planning-obligations/3",
            "continuation_contract": "agent-frozen-requirements/2",
            "claims_contract": "agent-obligation-claims/1",
            "verifier_protocol": "evidence-check/3",
            "original_context_contract": "agent-obligation-context/1",
        }.items()):
            raise ValueError("Actual obligation protocol differs")
        modules = obligation.get("modules", {})
        if set(modules) != {"agent_obligations", "agent_runtime", "evidence_check", "evidence_client"}:
            raise ValueError("Loaded obligation implementation proof incomplete")
        for row in modules.values():
            path = Path(row["path"]).resolve()
            if not path.is_relative_to(source.resolve()) or file_sha(path) != row["sha256"]:
                raise ValueError("Loaded obligation module changed or is foreign")
    if "decision_identity" in ready:
        decision = ready["decision_identity"]
        if decision != identity.get("decision_identity"):
            raise ValueError("Actual decision instruction or diagnostic differs from readiness")
        if (decision.get("instruction_policy") != "agent-obligation-instruction/1"
                or decision.get("schema_diagnostic_version") != "agent-schema-diagnostic/1"
                or set(decision.get("modules", {})) != {
                    "agent_decision_instruction", "agent_schema_diagnostics", "agent_runtime", "agent_budget"}):
            raise ValueError("Loaded decision policy proof incomplete")
        for row in decision["modules"].values():
            path = Path(row["path"]).resolve()
            if not path.is_relative_to(source.resolve()) or file_sha(path) != row["sha256"]:
                raise ValueError("Loaded decision module changed or is foreign")
        for key in ("instruction_builder_sha256", "failure_classifier_sha256",
                    "initial_instruction_sha256", "continuation_instruction_sha256"):
            if not re.fullmatch(r"[a-f0-9]{64}", decision.get(key, "")):
                raise ValueError("Loaded decision function or instruction proof incomplete")
    sidecar = http_json(ready["sidecar_base_url"], "/internal/health/ready")
    if sidecar.get("status") != "UP" or sidecar.get("runner") != "enabled":
        raise ValueError("Real sidecar is not ready")
    container = json.loads(subprocess.check_output(["docker", "inspect", ready["database_container"]]))[0]
    labels = container["Config"].get("Labels") or {}
    if labels.get("deepresearch.validation.owner") != ready["isolation_id"]:
        raise ValueError("Database belongs to another environment")
    bindings = container["NetworkSettings"]["Ports"].get("5432/tcp", [])
    if bindings != [{"HostIp": "127.0.0.1", "HostPort": str(ready["database_port"])}]:
        raise ValueError("Database is not isolated on its declared loopback port")
    if ready["database_volume"] not in [m.get("Name") for m in container["Mounts"]]:
        raise ValueError("Database volume does not match the environment")
    verified = {"embedded_build": build, "jar_sha256": ready["jar_sha256"], "model_identity": identity.get("model_identity"),
            "sidecar_source_sha256": ready["sidecar_source_sha256"],
            "json_diagnostic_identity": identity.get("json_diagnostic_identity"), "verified": True}
    if "obligation_identity" in ready:
        verified["obligation_identity"] = identity["obligation_identity"]
    if "decision_identity" in ready:
        verified["decision_identity"] = identity["decision_identity"]
    return verified

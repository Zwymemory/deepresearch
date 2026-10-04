"""Pinned V22 preparation and immutable native-requirement audit checks. No API calls."""

import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile
import zlib

from agent_live_common import file_sha, read_private

AUDIT_VERSION = "agent-live-audit/2"
CAPTURE_VERSION = "agent-native-capture/2"
SCHEMA_POLICY = "committed-v1-v22/1"
ISOLATION_ID = "agent-live-20260930"
MIGRATIONS = "src/main/resources/db/migration/"
CAPTURE_LIMITS = {
    "run": 1,
    "operations": 128,
    "tasks": 64,
    "criteria": 512,
    "checks": 64,
    "read_receipts": 512,
    "records": 512,
    "publications": 32,
    "tool_receipts": 512,
    "requirements": 1,
    "requirement_bindings": 32,
    "investigation_progress": 128,
}
TABLE_COLUMNS = {
    "agent_research_requirements": {
        "run_id": "varchar",
        "manifest": "jsonb",
        "declaration_key": "varchar",
        "declaration_attempt": "int4",
        "claim_token": "uuid",
    },
    "agent_research_requirement_binding": {
        "run_id": "varchar",
        "requirement_id": "varchar",
        "task_id": "varchar",
        "criterion_id": "varchar",
        "claim_token": "uuid",
    },
}
TABLE_TRIGGERS = {
    "agent_research_requirements": {
        "agent_requirements_fence",
        "agent_requirements_immutable",
    },
    "agent_research_requirement_binding": {
        "agent_requirement_binding_fence",
        "agent_requirement_binding_immutable",
    },
}
TABLE_CONSTRAINTS = {
    "agent_research_requirements": {
        ("p", ("run_id",), None),
        ("f", ("run_id",), "agent_research_run"),
        (
            "f",
            ("run_id", "declaration_key", "declaration_attempt"),
            "agent_research_operation",
        ),
    },
    "agent_research_requirement_binding": {
        ("p", ("run_id", "requirement_id"), None),
        ("u", ("run_id", "criterion_id"), None),
        ("f", ("run_id",), "agent_research_requirements"),
        ("f", ("run_id", "task_id", "criterion_id"), "agent_research_criterion"),
    },
}


def encoded(value):
    return json.dumps(
        value,
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
        default=str,
        allow_nan=False,
    ).encode("utf-8")


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def flyway_checksum(data):
    crc = 0
    for line in re.split(r"\r\n|\r|\n", data.decode("utf-8-sig")):
        crc = zlib.crc32(line.encode("utf-8"), crc)
    return crc if crc < 2**31 else crc - 2**32


def candidate_schema_policy(built):
    """Validate local committed source/JAR identity before touching runtime resources."""
    sha = built.get("build_sha", "")
    if type(sha) is not str or not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("SCHEMA_CANDIDATE_IDENTITY_INVALID")
    manifest = read_private(built["source_manifest_path"])
    if (
        manifest.get("code_sha") != sha
        or file_sha(built["source_manifest_path"]) != built["source_manifest_sha256"]
        or file_sha(built["jar_path"]) != built["jar_sha256"]
    ):
        raise ValueError("SCHEMA_CANDIDATE_IDENTITY_MISMATCH")
    blobs = manifest["blobs"]
    try:
        tree = subprocess.check_output(
            ["git", "-C", built["git_repository"], "ls-tree", "-rz", sha],
            stderr=subprocess.DEVNULL,
        )
    except (KeyError, OSError, subprocess.CalledProcessError) as error:
        raise ValueError("SCHEMA_COMMITTED_CANDIDATE_UNAVAILABLE") from error
    committed = {}
    for item in tree.split(b"\0"):
        if item:
            meta, name = item.split(b"\t", 1)
            mode, kind, blob = meta.decode().split()
            if kind != "blob" or mode not in {"100644", "100755"}:
                raise ValueError("SCHEMA_COMMITTED_ENTRY_UNSUPPORTED")
            committed[name.decode()] = blob
    if blobs != committed:
        raise ValueError("SCHEMA_COMMITTED_MANIFEST_MISMATCH")
    rows = []
    source = Path(built["source_archive"])
    with zipfile.ZipFile(built["jar_path"]) as jar:
        identities = [
            name
            for name in jar.namelist()
            if name
            in {
                "BOOT-INF/classes/META-INF/build-info.properties",
                "META-INF/build-info.properties",
            }
        ]
        if len(identities) != 1:
            raise ValueError("SCHEMA_JAR_IDENTITY_RESOURCE_INVALID")
        identity = jar.read(identities[0]).decode()
        properties = dict(
            line.split("=", 1)
            for line in identity.splitlines()
            if line and not line.startswith("#") and "=" in line
        )
        if (
            properties.get("build.revision") != sha
            or properties.get("build.source-manifest-sha256")
            != built["source_manifest_sha256"]
            or properties.get("build.isolation-id") != ISOLATION_ID
        ):
            raise ValueError("SCHEMA_JAR_IDENTITY_MISMATCH")
        names = sorted(name for name in blobs if name.startswith(MIGRATIONS))
        packaged = sorted(
            name.removeprefix("BOOT-INF/classes/db/migration/")
            for name in jar.namelist()
            if name.startswith("BOOT-INF/classes/db/migration/")
            and name.endswith(".sql")
        )
        if packaged != sorted(Path(name).name for name in names):
            raise ValueError("SCHEMA_PACKAGED_MIGRATIONS_MISMATCH")
        for name in names:
            match = re.fullmatch(r"V([1-9][0-9]*)__[^/]+\.sql", Path(name).name)
            if not match:
                raise ValueError("SCHEMA_UNSUPPORTED_MIGRATION")
            data = (source / name).read_bytes()
            blob = hashlib.sha1(
                b"blob " + str(len(data)).encode() + b"\0" + data
            ).hexdigest()
            if (
                blob != blobs[name]
                or jar.read("BOOT-INF/classes/db/migration/" + Path(name).name) != data
            ):
                raise ValueError("SCHEMA_MIGRATION_CONTENT_MISMATCH")
            rows.append(
                {
                    "version": str(int(match[1])),
                    "script": Path(name).name,
                    "checksum": flyway_checksum(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                }
            )
    rows.sort(key=lambda row: int(row["version"]))
    if [row["version"] for row in rows] != [str(v) for v in range(1, 23)]:
        raise ValueError("SCHEMA_ONLY_REVIEWED_V1_V22_SUPPORTED")
    if rows[-1]["script"] != "V22__agent_original_requirements.sql":
        raise ValueError("SCHEMA_V22_REQUIREMENTS_MISSING")
    return {
        "policy": SCHEMA_POLICY,
        "candidate_sha": sha,
        "source_manifest_sha256": built["source_manifest_sha256"],
        "jar_sha256": built["jar_sha256"],
        "isolation_id": ISOLATION_ID,
        "migrations": rows,
    }


def verify_database_schema(conn, policy):
    if policy.get("policy") != SCHEMA_POLICY or [
        r["version"] for r in policy["migrations"]
    ] != [str(v) for v in range(1, 23)]:
        raise ValueError("SCHEMA_POLICY_INVALID")
    rows = conn.execute(
        "SELECT version,type,script,checksum,success FROM public.flyway_schema_history WHERE type<>'SCHEMA' ORDER BY installed_rank"
    ).fetchall()
    expected = [
        (r["version"], "SQL", r["script"], r["checksum"], True)
        for r in policy["migrations"]
    ]
    if rows != expected:
        raise ValueError("SCHEMA_APPLIED_MIGRATIONS_MISMATCH")
    role = conn.execute(
        "SELECT rolsuper,rolcreaterole,rolcreatedb,rolbypassrls,rolcanlogin FROM pg_roles WHERE rolname='deepresearch_workflow'"
    ).fetchone()
    if role != (False, False, False, False, True):
        raise ValueError("SCHEMA_WORKFLOW_ROLE_INVALID")
    if (
        conn.execute(
            "SELECT has_schema_privilege('deepresearch_workflow','public','USAGE')"
        ).fetchone()[0]
        is not True
    ):
        raise ValueError("SCHEMA_WORKFLOW_USAGE_MISSING")
    for table, columns in TABLE_COLUMNS.items():
        actual = {
            name: (kind, nullable)
            for name, kind, nullable in conn.execute(
                "SELECT column_name,udt_name,is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name=%s",
                (table,),
            ).fetchall()
        }
        if actual != {name: (kind, "NO") for name, kind in columns.items()}:
            raise ValueError("SCHEMA_REQUIRED_TABLE_INVALID")
        constraints = conn.execute(
            "SELECT contype,ARRAY(SELECT attname FROM pg_attribute WHERE attrelid=conrelid "
            "AND attnum=ANY(conkey) ORDER BY array_position(conkey,attnum)),"
            "CASE WHEN confrelid=0 THEN NULL ELSE confrelid::regclass::text END,convalidated "
            "FROM pg_constraint WHERE conrelid=%s::regclass AND contype IN ('p','u','f')",
            ("public." + table,),
        ).fetchall()
        if {
            (kind, tuple(names), target) for kind, names, target, _ in constraints
        } != TABLE_CONSTRAINTS[table] or not all(row[3] for row in constraints):
            raise ValueError("SCHEMA_REQUIRED_CONSTRAINT_INVALID")
        triggers = set(
            conn.execute(
                "SELECT tgname FROM pg_trigger WHERE tgrelid=%s::regclass AND NOT tgisinternal AND tgenabled IN ('O','A')",
                ("public." + table,),
            ).fetchall()
        )
        if {(name,) for name in TABLE_TRIGGERS[table]} != triggers:
            raise ValueError("SCHEMA_REQUIRED_TRIGGER_MISSING")
        for privilege in (
            "SELECT",
            "INSERT",
            "UPDATE",
            "DELETE",
            "TRUNCATE",
            "REFERENCES",
            "TRIGGER",
        ):
            allowed = conn.execute(
                "SELECT has_table_privilege('deepresearch_workflow',%s,%s)",
                ("public." + table, privilege),
            ).fetchone()[0]
            if allowed is not (privilege in {"SELECT", "INSERT"}):
                raise ValueError("SCHEMA_WORKFLOW_TABLE_PRIVILEGE_INVALID")
    return {
        "policy": SCHEMA_POLICY,
        "candidate_sha": policy["candidate_sha"],
        "policy_sha256": digest(policy),
        "migration_version": 22,
        "applied_migrations": len(rows),
        "required_tables_and_role_verified": True,
    }


def workflow_helpers():
    # Reuse the reviewed production algorithms from the same committed source bundle.
    source = str(Path(__file__).resolve().parents[1] / "workflow-service/src")
    if source not in sys.path:
        sys.path.insert(0, source)
    from deepresearch_workflow.agent_completion import criterion_id, normalized_claim
    from deepresearch_workflow.agent_requirements import (
        evaluate_coverage,
        freeze_requirements,
        validate_manifest,
    )

    return (
        criterion_id,
        normalized_claim,
        freeze_requirements,
        validate_manifest,
        evaluate_coverage,
    )


def native_requirements_validation(database):
    """Check persisted mappings, never certify arbitrary-language semantic coverage."""
    result = {
        "contract_version": CAPTURE_VERSION,
        "database_sha256": digest(database),
        "status": "invalid",
        "mapping_complete": False,
        "eligible_for_complete_review": False,
        "semantic_review_required": True,
        "requirements": [],
        "issues": [],
    }
    try:
        capture = database.get("capture", {})
        if (
            capture.get("contract_version") != CAPTURE_VERSION
            or capture.get("status") != "complete"
        ):
            raise ValueError("CAPTURE_INCOMPLETE_OR_LEGACY")
        if (
            len(encoded(database)) > 8388608
            or capture.get("row_limits") != CAPTURE_LIMITS
        ):
            raise ValueError("NATIVE_CAPTURE_BOUNDS_INVALID")
        for key, limit in CAPTURE_LIMITS.items():
            rows = database[key]
            if (
                not isinstance(rows, list)
                or len(rows) > limit
                or capture["counts"][key]["rows"] != len(rows)
                or any(len(encoded(row)) > 1048576 for row in rows)
            ):
                raise ValueError("NATIVE_CAPTURE_COUNT_OR_BOUND_MISMATCH")
        run_rows = database["run"]
        if len(run_rows) != 1 or run_rows[0]["run_id"] != capture["run_id"]:
            raise ValueError("NATIVE_RUN_ID_MISMATCH")
        run = run_rows[0]
        manifests, bindings = database["requirements"], database["requirement_bindings"]
        if not manifests:
            if bindings:
                raise ValueError("NATIVE_ORPHAN_BINDINGS")
            result.update(
                status="missing_manifest", issues=["NATIVE_MANIFEST_NOT_PERSISTED"]
            )
            return result
        if len(manifests) != 1 or manifests[0]["run_id"] != run["run_id"]:
            raise ValueError("NATIVE_MANIFEST_RUN_MISMATCH")
        criterion_id, normalized_claim, freeze, validate, coverage = workflow_helpers()
        row = manifests[0]
        if type(row["declaration_attempt"]) is not int or row[
            "declaration_attempt"
        ] not in {1, 2}:
            raise ValueError("NATIVE_DECLARATION_ATTEMPT_INVALID")
        manifest = validate(
            row["manifest"], run_id=run["run_id"], question=run["question"]
        )
        declarations = [
            o
            for o in database["operations"]
            if o["operation_key"] == row["declaration_key"]
            and o["attempt"] == row["declaration_attempt"]
        ]
        if len(declarations) != 1:
            raise ValueError("NATIVE_DECLARATION_RECEIPT_MISSING")
        declaration = declarations[0]
        if (
            declaration["kind"] != "MODEL"
            or declaration["purpose"] != "DECISION"
            or declaration["status"] != "SETTLED"
        ):
            raise ValueError("NATIVE_DECLARATION_NOT_SETTLED_DECISION")
        from deepresearch_workflow.agent_question_segments import declaration_drafts

        freeze(
            run["run_id"],
            run["question"],
            declaration_drafts(run["question"], declaration["safe_result"]),
            existing=manifest,
        )
        tasks = {t["task_id"]: t for t in database["tasks"]}
        criteria = {(c["task_id"], c["criterion_id"]): c for c in database["criteria"]}
        if len(tasks) != len(database["tasks"]) or len(criteria) != len(
            database["criteria"]
        ):
            raise ValueError("NATIVE_DUPLICATE_TASK_OR_CRITERION")
        wanted = {r["requirement_id"]: r for r in manifest["requirements"]}
        if len({b["requirement_id"] for b in bindings}) != len(bindings) or len(
            {b["criterion_id"] for b in bindings}
        ) != len(bindings):
            raise ValueError("NATIVE_DUPLICATE_BINDING")
        for binding in bindings:
            if (
                binding["run_id"] != run["run_id"]
                or binding["requirement_id"] not in wanted
            ):
                raise ValueError("NATIVE_BINDING_RUN_OR_REQUIREMENT_MISMATCH")
            task = tasks[binding["task_id"]]
            criterion = criteria[(binding["task_id"], binding["criterion_id"])]
            index, text = criterion["criterion_index"], criterion["criterion_text"]
            if (
                type(index) is not int
                or index < 0
                or task["acceptance_criteria"][index] != text
                or criterion_id(run["run_id"], task["task_id"], index, text)
                != criterion["criterion_id"]
            ):
                raise ValueError("NATIVE_CRITERION_IDENTITY_MISMATCH")
            requirement = wanted[binding["requirement_id"]]
            expected = criterion.get("expected_claim")
            if expected:
                if digest(expected) != criterion.get("expected_hash"):
                    raise ValueError("NATIVE_CRITERION_EXPECTED_HASH_MISMATCH")
                normalized = normalized_claim(expected)
                if (
                    normalized["kind"] != requirement["kind"]
                    or normalized["applicability"] != requirement["applicability"]
                ):
                    raise ValueError("NATIVE_CRITERION_SCOPE_MISMATCH")
            result["requirements"].append(
                {
                    "requirement_id": binding["requirement_id"],
                    "task_id": binding["task_id"],
                    "criterion_id": binding["criterion_id"],
                    "expected_claim_bound": bool(expected),
                }
            )
        missing = sorted(set(wanted) - {b["requirement_id"] for b in bindings})
        result["missing_requirement_ids"] = missing
        result["mapping_complete"] = not missing
        result["status"] = "verified_mapping" if not missing else "incomplete_mapping"
        # Exact current scoped checks must be inspectable; native publication and semantic
        # evidence/source/quote truth still require independent review of the saved audit.
        current = {
            p["investigation"]: p["current_call_id"]
            for p in database["investigation_progress"]
        }
        investigations = {}
        records = {}
        for record in database["records"]:
            payload = record["payload"]
            if (
                record["payload_sha256"] != digest(payload)
                or payload["run_id"] != run["run_id"]
                or payload["record_type"] != record["record_type"]
            ):
                raise ValueError("NATIVE_RECORD_HASH_MISMATCH")
            if record["record_type"] == "Evidence":
                snapshot = payload["snapshot"]
                if (
                    hashlib.sha256(snapshot["text"].encode()).hexdigest()
                    != snapshot["sha256"]
                ):
                    raise ValueError("NATIVE_SOURCE_SNAPSHOT_HASH_MISMATCH")
            records[digest(payload)] = payload
        for summary in result["requirements"]:
            c = criteria[(summary["task_id"], summary["criterion_id"])]
            checks = [
                x
                for x in database["checks"]
                if x["call_id"] == c.get("last_call_id")
                and x["task_id"] == summary["task_id"]
                and x["investigation"] == c.get("investigation")
            ]
            operations = [
                o
                for o in database["operations"]
                if o["operation_key"] == c.get("last_call_id")
                and o["kind"] == "TOOL"
                and o["status"] == "SETTLED"
            ]
            summary["current_check_bound"] = (
                bool(c.get("expected_claim"))
                and len(checks) == 1
                and checks[0]["status"] == "COMPLETED"
                and current.get(c.get("investigation")) == c.get("last_call_id")
                and len(operations) == 1
            )
            if summary["current_check_bound"]:
                checked = checks[0]
                if digest(checked["request"]) != checked["request_sha256"]:
                    raise ValueError("NATIVE_CHECK_REQUEST_HASH_MISMATCH")
                request = checked["request"]
                if (
                    request["check_id"] != checked["check_id"]
                    or request["investigation_id"] != checked["investigation"]
                ):
                    raise ValueError("NATIVE_CHECK_REQUEST_IDENTITY_MISMATCH")
                packet = copy.deepcopy(checked["result"])
                if any(digest(p) not in records for p in packet["records"]):
                    raise ValueError("NATIVE_CHECK_RECORD_NOT_EXPORTED")
                expected = normalized_claim(c["expected_claim"])
                requested = [
                    claim
                    for claim in request["claims"]
                    if normalized_claim(claim) == expected
                ]
                if len(requested) != 1:
                    raise ValueError("NATIVE_CHECK_REQUEST_CLAIM_MISMATCH")
                requested_claim = requested[0]
                checked_claims = [
                    claim
                    for claim in packet["records"]
                    if claim["record_type"] == "Claim"
                    and claim["claim_id"] == requested_claim["claim_id"]
                    and normalized_claim(claim) == expected
                ]
                if len(checked_claims) != 1:
                    raise ValueError("NATIVE_CHECK_RESULT_CLAIM_MISMATCH")
                receipts = []
                for operation in database["operations"]:
                    if (
                        operation["kind"] != "MODEL"
                        or operation.get("purpose") != "CHECK"
                        or operation["status"] != "SETTLED"
                    ):
                        continue
                    body = operation.get("safe_result")
                    if not isinstance(body, dict) or not isinstance(
                        body.get("request_binding"), dict
                    ):
                        raise ValueError("NATIVE_CHECK_MODEL_RECEIPT_MALFORMED")
                    if body["request_binding"].get("check_id") == checked["check_id"]:
                        receipts.append(body)
                if len(receipts) != 1:
                    raise ValueError("NATIVE_CHECK_MODEL_RECEIPT_MISSING_OR_AMBIGUOUS")
                receipt = receipts[0]
                binding = receipt["request_binding"]
                if (
                    binding["request_sha256"] != checked["request_sha256"]
                    or binding["response_sha256"] != checked["response_sha256"]
                    or digest(receipt["value"]) != checked["response_sha256"]
                ):
                    raise ValueError("NATIVE_CHECK_MODEL_RECEIPT_HASH_MISMATCH")
                response_claims = [
                    claim
                    for claim in receipt["value"]["claims"]
                    if claim["claim_id"] == requested_claim["claim_id"]
                ]
                if len(response_claims) != 1:
                    raise ValueError("NATIVE_CHECK_MODEL_RESPONSE_CLAIM_MISMATCH")
                if packet.get("check_id", checked["check_id"]) != checked["check_id"]:
                    raise ValueError("NATIVE_CHECK_RESULT_IDENTITY_MISMATCH")
                packet["check_id"] = checked["check_id"]
                investigations[c["investigation"]] = {
                    "latest_call_id": c["last_call_id"],
                    "attempt_status": "accepted",
                    "packet": packet,
                }
        projected = []
        for task in tasks.values():
            projected.append(
                {
                    **task,
                    "criteria": [
                        {
                            **c,
                            "text": c["criterion_text"],
                            "index": c["criterion_index"],
                            "investigation_key": c["investigation"],
                        }
                        for (task_id, _), c in criteria.items()
                        if task_id == task["task_id"]
                    ],
                }
            )
        proof = coverage(
            manifest,
            projected,
            investigations,
            [{k: b[k] for k in ("requirement_id", "criterion_id")} for b in bindings],
        )
        result["structural_coverage"] = proof
        sources = {
            p["evidence_id"] for p in records.values() if p["record_type"] == "Evidence"
        }
        if any(
            not set(r.get("evidence_ids", [])) <= sources for r in proof["requirements"]
        ):
            raise ValueError("NATIVE_ADOPTED_SOURCE_NOT_EXPORTED")
        result["eligible_for_complete_review"] = (
            result["mapping_complete"]
            and proof["complete"]
            and all(r["current_check_bound"] for r in result["requirements"])
        )
    except (KeyError, TypeError, ValueError, IndexError) as error:
        result["status"] = "invalid"
        result["issues"] = [
            str(error)
            if isinstance(error, ValueError)
            and re.fullmatch(r"[A-Z][A-Z0-9_]+", str(error))
            else "NATIVE_EXPORT_MALFORMED"
        ]
    return result


def audit_native_validation(audit):
    proof = native_requirements_validation(audit["database"])
    if "case" in audit or "view" in audit or "run" in audit:
        persisted = audit["database"].get("run", [])
        actual = persisted[0] if len(persisted) == 1 else {}
        proof["submitted_request_matches"] = actual.get("run_id") == audit.get(
            "run", {}
        ).get("runId") == audit.get("view", {}).get("runId") and actual.get(
            "question"
        ) == audit.get("case", {}).get("question")
        if not proof["submitted_request_matches"]:
            proof["status"] = "invalid"
            proof["eligible_for_complete_review"] = False
            proof["issues"].append("NATIVE_SUBMITTED_REQUEST_MISMATCH")
    return proof


def finalize_audit(audit):
    output = copy.deepcopy(audit)
    output["audit_version"] = AUDIT_VERSION
    output["native_requirements_validation"] = audit_native_validation(output)
    return output


def validate_saved_audit(audit):
    if "audit_version" not in audit:
        if any(
            key in audit.get("database", {})
            for key in ("capture", "requirements", "requirement_bindings")
        ):
            raise ValueError("NATIVE_AUDIT_VERSION_MISSING")
        return {
            "status": "legacy_audit",
            "eligible_for_complete_review": False,
            "semantic_review_required": True,
        }
    if audit["audit_version"] != AUDIT_VERSION:
        raise ValueError("NATIVE_AUDIT_VERSION_UNSUPPORTED")
    actual = audit_native_validation(audit)
    if actual != audit.get("native_requirements_validation"):
        raise ValueError("NATIVE_AUDIT_BINDING_CHANGED")
    return actual

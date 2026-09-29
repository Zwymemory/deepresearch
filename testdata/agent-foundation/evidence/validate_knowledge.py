"""Offline B contract checks; no service, model, download or universal schema claim."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import re
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[3]
SCHEMA = ROOT / "contracts/agent/v0/knowledge.schema.json"
ID_FIELDS = {"Evidence": "evidence_id", "Claim": "claim_id", "DecisionRecord": "decision_id",
             "Challenge": "challenge_id", "ResearchPacket": "packet_id", "MemoryItem": "memory_id"}
EXTERNAL_IDS = {"ResearchProject": "project_id", "Task": "task_id", "Run": "run_id",
                "Session": "session_id", "Receipt": "receipt_id", "Assessment": "assessment_id",
                "AgentContext": "context_id"}
KEYWORDS = {"$schema", "$id", "$defs", "$comment", "title", "description", "$ref", "type",
            "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
            "uniqueItems", "minLength", "maxLength", "pattern", "format", "minimum", "maximum",
            "enum", "const", "oneOf", "anyOf", "allOf", "not", "if", "then", "else"}


class ContractError(ValueError):
    def __init__(self, code, path="$"):
        self.code, self.path = code, path
        super().__init__(f"{code} at {path}")


def fail(code, path="$"):
    raise ContractError(code, path)


def load_json(path):
    def pairs(rows):
        result = {}
        for key, value in rows:
            if key in result:
                fail("DUPLICATE_JSON_KEY")
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=pairs,
                      parse_constant=lambda _: fail("NONFINITE_JSON_NUMBER"))


def timestamp(value):
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})", value):
        fail("STRUCTURE_DATE_TIME")
    try:
        result = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        fail("STRUCTURE_DATE_TIME")
    if result.tzinfo is None:
        fail("STRUCTURE_DATE_TIME")
    return result


def check_schema(schema, root=None):
    """Fail if our bounded evaluator cannot execute a keyword; no remote resolver."""
    root = root or schema
    if isinstance(schema, bool):
        return
    if not isinstance(schema, dict) or set(schema) - KEYWORDS:
        fail("UNSUPPORTED_SCHEMA_KEYWORD")
    if "$ref" in schema:
        resolve(schema["$ref"], root)
    for key in ("properties", "$defs"):
        for child in schema.get(key, {}).values():
            check_schema(child, root)
    for key in ("oneOf", "anyOf", "allOf"):
        for child in schema.get(key, []):
            check_schema(child, root)
    for key in ("items", "additionalProperties", "not", "if", "then", "else"):
        if key in schema:
            check_schema(schema[key], root)
    if "format" in schema and schema["format"] not in ("date-time", "uri"):
        fail("UNSUPPORTED_SCHEMA_FORMAT")


def resolve(reference, root):
    if not isinstance(reference, str) or not reference.startswith("#/"):
        fail("REMOTE_SCHEMA_REFERENCE_DENIED")
    value = root
    try:
        for part in reference[2:].split("/"):
            value = value[part.replace("~1", "/").replace("~0", "~")]
    except (KeyError, TypeError):
        fail("SCHEMA_REFERENCE_MISSING")
    return value


def matches(value, schema, root):
    try:
        validate_schema(value, schema, root)
        return True
    except ContractError as error:
        if error.code.startswith("STRUCTURE"):
            return False
        raise


def validate_schema(value, schema, root=None, path="$", depth=0):
    """Execute only this schema's declared subset of draft2020-12 keywords."""
    if depth > 64:
        fail("STRUCTURE_DEPTH", path)
    root = root or schema
    if isinstance(schema, bool):
        if not schema:
            fail("STRUCTURE_FALSE_SCHEMA", path)
        return
    if set(schema) - KEYWORDS:
        fail("UNSUPPORTED_SCHEMA_KEYWORD", path)
    if "$ref" in schema:
        validate_schema(value, resolve(schema["$ref"], root), root, path, depth + 1)
    for key, valid in (("oneOf", lambda n: n == 1), ("anyOf", lambda n: n >= 1)):
        if key in schema and not valid(sum(matches(value, child, root) for child in schema[key])):
            fail("STRUCTURE_" + key.upper(), path)
    for child in schema.get("allOf", []):
        validate_schema(value, child, root, path, depth + 1)
    if "not" in schema and matches(value, schema["not"], root):
        fail("STRUCTURE_NOT", path)
    if "if" in schema:
        branch = "then" if matches(value, schema["if"], root) else "else"
        if branch in schema:
            validate_schema(value, schema[branch], root, path, depth + 1)
    kind = schema.get("type")
    types = {"object": isinstance(value, dict), "array": isinstance(value, list),
             "string": isinstance(value, str), "integer": type(value) is int,
             "number": type(value) is int or type(value) is float and math.isfinite(value),
             "boolean": type(value) is bool, "null": value is None}
    if kind is not None and not types.get(kind, False):
        fail("STRUCTURE_TYPE", path)
    canonical = lambda item: json.dumps(item, ensure_ascii=False, sort_keys=True, allow_nan=False)
    if "const" in schema and canonical(value) != canonical(schema["const"]):
        fail("STRUCTURE_CONST", path)
    if "enum" in schema and canonical(value) not in {canonical(x) for x in schema["enum"]}:
        fail("STRUCTURE_ENUM", path)
    if isinstance(value, dict):
        if set(schema.get("required", [])) - set(value):
            fail("STRUCTURE_REQUIRED", path)
        properties = schema.get("properties", {})
        for key, item in value.items():
            child = properties.get(key, schema.get("additionalProperties", True))
            validate_schema(item, child, root, path + "." + key, depth + 1)
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0) or len(value) > schema.get("maxItems", float("inf")):
            fail("STRUCTURE_ARRAY_LENGTH", path)
        if schema.get("uniqueItems") and len({canonical(x) for x in value}) != len(value):
            fail("STRUCTURE_ARRAY_DUPLICATE", path)
        for index, item in enumerate(value):
            validate_schema(item, schema.get("items", True), root, f"{path}[{index}]", depth + 1)
    if isinstance(value, str):
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", float("inf")):
            fail("STRUCTURE_STRING_LENGTH", path)
        if "pattern" in schema and not re.search(schema["pattern"], value):
            fail("STRUCTURE_PATTERN", path)
        if schema.get("format") == "date-time":
            timestamp(value)
        if schema.get("format") == "uri":
            if not re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", value) or re.search(r"[\s\x00-\x1f]", value):
                fail("STRUCTURE_URI", path)
    if type(value) in (int, float):
        if type(value) is float and not math.isfinite(value) or value < schema.get("minimum", -math.inf) or value > schema.get("maximum", math.inf):
            fail("STRUCTURE_NUMBER_RANGE", path)


def validate_record(record, schema=None):
    schema = schema or load_json(SCHEMA)
    check_schema(schema)
    validate_schema(record, schema)


def scope(record):
    return tuple(record[key] for key in ("tenant_id", "owner_id", "project_id"))


def source_metadata_hash(source):
    return hashlib.sha256(json.dumps(source, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def validate_bundle(bundle, *, authorized_scope=None, external_registry=None, root=ROOT):
    """Check local labels/identities. Caller supplies trusted authorization in real use."""
    schema = load_json(SCHEMA)
    check_schema(schema)
    for value in bundle.get("usage", {}).values():
        validate_schema(value, schema["$defs"]["Measurement"], schema)
    rows = bundle["records"]
    registry, histories = {}, {}
    for row in rows:
        validate_schema(row, schema)
        kind = row["record_type"]
        key = (kind, row[ID_FIELDS[kind]])
        if key in registry and kind != "MemoryItem":
            fail("DUPLICATE_RECORD_ID")
        if kind == "MemoryItem":
            revision = (key, row["version"])
            if revision in histories:
                fail("DUPLICATE_MEMORY_VERSION")
            histories[revision] = row
            if key in registry and registry[key]["version"] > row["version"]:
                continue
        registry[key] = row
    external = {}
    for row in external_registry if external_registry is not None else bundle.get("external_refs", []):
        kind = row["record_type"]
        if kind not in EXTERNAL_IDS:
            fail("EXTERNAL_TYPE_UNSUPPORTED")
        key = (kind, row[EXTERNAL_IDS[kind]])
        if key in external:
            fail("DUPLICATE_EXTERNAL_REFERENCE")
        external[key] = row
    authorized_scope = authorized_scope or bundle["authorized_scope"]
    auth = tuple(authorized_scope[x] for x in ("tenant_id", "owner_id", "project_id"))

    def find(kind, identity, parent):
        row = registry.get((kind, identity)) or external.get((kind, identity))
        if row is None:
            fail("REFERENCE_MISSING")
        if scope(row) != scope(parent):
            fail("REFERENCE_SCOPE_MISMATCH")
        return row

    for row in rows:
        if scope(row) != auth:
            fail("AUTHORIZED_SCOPE_MISMATCH")
        find("ResearchProject", row["project_id"], row)
        if "run_id" in row:
            find("Run", row["run_id"], row)
        if "task_id" in row:
            task = find("Task", row["task_id"], row)
            if task["run_id"] != row["run_id"]:
                fail("TASK_RUN_MISMATCH")
        kind = row["record_type"]
        if kind == "Evidence":
            receipt = find("Receipt", row["receipt_id"], row)
            if receipt.get("status") != "completed" or receipt.get("run_id") != row["run_id"] or receipt.get("task_id") != row["task_id"] or not receipt.get("authorized"):
                fail("RECEIPT_BINDING_INVALID")
            binding = dict(source_id=row["source"]["source_id"], snapshot_sha256=row["snapshot"]["sha256"],
                           source_metadata_sha256=source_metadata_hash(row["source"]))
            if binding not in receipt.get("source_bindings", []):
                fail("RECEIPT_SOURCE_MISMATCH")
            text = row["snapshot"]["text"]
            if hashlib.sha256(text.encode()).hexdigest() != row["snapshot"]["sha256"]:
                fail("SNAPSHOT_HASH_MISMATCH")
            source = row["source"]
            if source["locator"]["kind"] != {"web": "web_uri", "knowledge": "knowledge_chunk",
                                              "synthetic_fixture": "fixture_file", "controlled_test": "test_result"}[source["kind"]]:
                fail("SOURCE_KIND_MISMATCH")
            if source["locator"]["kind"] == "fixture_file":
                path = (root / source["locator"]["path"]).resolve()
                if not path.is_relative_to((root / "testdata/agent-foundation").resolve()):
                    fail("FIXTURE_PATH_ESCAPE")
                if not path.is_file() or path.read_text() != text:
                    fail("FIXTURE_SOURCE_MISMATCH")
            elif source["locator"]["kind"] == "web_uri":
                url = urlsplit(source["locator"]["uri"])
                if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password:
                    fail("SOURCE_URI_UNSAFE")
            if source["derivation"] in ("mirror", "repost", "derived") and not source["parent_source_id"]:
                fail("SOURCE_PARENT_MISSING")
            if source["parent_source_id"]:
                parents = [e for e in rows if e["record_type"] == "Evidence" and e["source"]["source_id"] == source["parent_source_id"] and scope(e) == scope(row)]
                if not parents:
                    fail("SOURCE_PARENT_REFERENCE_MISSING")
                if source["derivation"] in ("mirror", "repost") and source["source_group"]["status"] == "known" and any(p["source"]["source_group"]["status"] == "known" and p["source"]["source_group"] != source["source_group"] for p in parents):
                    fail("SOURCE_GROUP_MISMATCH")
            if row["validity"] == "invalidated" and not row["invalidation_reason"]:
                fail("INVALIDATION_REASON_MISSING")
        elif kind == "Claim":
            for link in row["evidence_links"]:
                evidence = find("Evidence", link["evidence_id"], row)
                if evidence["run_id"] != row["run_id"]:
                    fail("EVIDENCE_RUN_MISMATCH")
                quote, text = link["quote"], evidence["snapshot"]["text"]
                if not 0 <= quote["start"] < quote["end"] <= len(text) or text[quote["start"]:quote["end"]] != quote["text"]:
                    fail("QUOTE_RANGE_MISMATCH")
                if hashlib.sha256(quote["text"].encode()).hexdigest() != quote["sha256"]:
                    fail("QUOTE_HASH_MISMATCH")
                find("Assessment", link["assessment_ref"], row)
            if not any(d["record_type"] == "DecisionRecord" and d["claim_id"] == row["claim_id"] for d in rows):
                fail("DECISION_MISSING")
        elif kind == "DecisionRecord":
            claim = find("Claim", row["claim_id"], row)
            if row["run_id"] != claim["run_id"]:
                fail("DECISION_RUN_MISMATCH")
            if row["decision_status"] != claim["decision_status"]:
                fail("DECISION_STATUS_MISMATCH")
            linked = {x["evidence_id"]: x["relation"] for x in claim["evidence_links"]}
            if any(linked[x["evidence_id"]] != x["relation"] for x in claim["evidence_links"]):
                fail("AMBIGUOUS_EVIDENCE_RELATION")
            ids = row["adopted_evidence_ids"] + row["unresolved_evidence_ids"] + [x["evidence_id"] for x in row["dismissed_evidence"]]
            if len(ids) != len(set(ids)) or set(ids) != set(linked):
                fail("DECISION_EVIDENCE_PARTITION")
            for identity in ids:
                find("Evidence", identity, row)
            status = row["decision_status"]
            if row["unresolved_evidence_ids"] and status != "contested":
                fail("UNRESOLVED_CONFLICT")
            expected_relation = {"supported": "supports", "refuted": "refutes"}.get(status)
            if expected_relation and (not row["adopted_evidence_ids"] or any(linked[x] != expected_relation for x in row["adopted_evidence_ids"])):
                fail("DECISION_RELATION_MISMATCH")
            for identity in row["adopted_evidence_ids"]:
                evidence = find("Evidence", identity, row)
                if claim["applicability"]["version"] != evidence["applicability"]["version"]:
                    fail("EVIDENCE_VERSION_SCOPE_MISMATCH")
            if status in ("contested", "insufficient") and not row["gaps"]:
                fail("DECISION_GAPS_MISSING")
        elif kind == "Challenge":
            claim = find("Claim", row["claim_id"], row)
            if claim["run_id"] != row["run_id"]:
                fail("CHALLENGE_RUN_MISMATCH")
            for identity in row["evidence_ids"]:
                find("Evidence", identity, row)
            if row["response_decision_id"]:
                decision = find("DecisionRecord", row["response_decision_id"], row)
                if decision["claim_id"] != row["claim_id"]:
                    fail("CHALLENGE_CLAIM_MISMATCH")
            elif row["status"] == "resolved":
                fail("CHALLENGE_RESPONSE_MISSING")
        elif kind == "ResearchPacket":
            for field, target in (("claim_ids", "Claim"), ("evidence_ids", "Evidence"), ("decision_ids", "DecisionRecord"), ("challenge_ids", "Challenge")):
                for identity in row[field]:
                    other = find(target, identity, row)
                    if other.get("run_id") != row["run_id"]:
                        fail("PACKET_RUN_MISMATCH")
            if row["context_id"]:
                find("AgentContext", row["context_id"], row)
        elif kind == "MemoryItem":
            if (row["version"] == 1) != (row["previous_version"] is None) or row["version"] > 1 and row["previous_version"] != row["version"] - 1:
                fail("MEMORY_VERSION_CHAIN")
            if timestamp(row["updated_at"]) < timestamp(row["created_at"]):
                fail("MEMORY_TIME_ORDER")
            for identity in row["origin"]["run_ids"]:
                find("Run", identity, row)
            for identity in row["origin"]["session_ids"]:
                find("Session", identity, row)
            for identity in row["origin"]["packet_ids"]:
                find("ResearchPacket", identity, row)
            for dependency in row["dependencies"]:
                target = find(dependency["record_type"], dependency["record_id"], row)
                changed = dependency["version"] != target.get("version", 1)
                if dependency["record_type"] == "Evidence":
                    changed |= dependency["snapshot_sha256"] != target["snapshot"]["sha256"] or target["validity"] == "invalidated" or target["availability"] != "available" or target["freshness"] != "fresh"
                elif dependency["record_type"] == "Claim":
                    changed |= target["freshness"] != "fresh"
                elif dependency["record_type"] == "MemoryItem":
                    changed |= target["lifecycle"] == "deleted" or target["freshness"] != "fresh"
                if changed and row["lifecycle"] == "active" and row["freshness"] == "fresh":
                    fail("MEMORY_DEPENDENCY_CHANGED")
            if row["result"]:
                for field, target in (("claim_ids", "Claim"), ("decision_ids", "DecisionRecord"), ("evidence_ids", "Evidence")):
                    for identity in row["result"][field]:
                        find(target, identity, row)
                required = {(t, i) for field, t in (("claim_ids", "Claim"), ("decision_ids", "DecisionRecord"), ("evidence_ids", "Evidence")) for i in row["result"][field]}
                actual = {(d["record_type"], d["record_id"]) for d in row["dependencies"]}
                if not required <= actual:
                    fail("MEMORY_DEPENDENCY_MISSING")
                claims = set(row["result"]["claim_ids"])
                decisions = [find("DecisionRecord", i, row) for i in row["result"]["decision_ids"]]
                if {d["claim_id"] for d in decisions} != claims:
                    fail("MEMORY_DECISION_CLAIM_MISMATCH")
                evidence_ids = {link["evidence_id"] for i in claims for link in find("Claim", i, row)["evidence_links"]}
                if not evidence_ids <= set(row["result"]["evidence_ids"]):
                    fail("MEMORY_RESULT_SOURCE_MISSING")
                if any(find(kind, identity, row)["run_id"] not in row["origin"]["run_ids"] for kind, identity in required):
                    fail("MEMORY_ORIGIN_RUN_MISMATCH")
            if row["progress"]:
                for field in ("completed_task_ids", "pending_task_ids"):
                    for identity in row["progress"][field]:
                        task = find("Task", identity, row)
                        if (field == "completed_task_ids") != (task["status"] == "done"):
                            fail("MEMORY_TASK_STATE_MISMATCH")
                for identity in row["progress"]["unresolved_claim_ids"]:
                    find("Claim", identity, row)
            if row["tombstone"] and row["tombstone"]["version"] != row["version"]:
                fail("MEMORY_TOMBSTONE_VERSION")
    memory_edges = {key[1]: [d["record_id"] for d in memory["dependencies"] if d["record_type"] == "MemoryItem"]
                    for key, memory in registry.items() if key[0] == "MemoryItem"}
    def visit(identity, ancestors):
        if identity in ancestors:
            fail("MEMORY_DEPENDENCY_CYCLE")
        for child in memory_edges.get(identity, []):
            visit(child, ancestors | {identity})
    for identity in memory_edges:
        visit(identity, set())
    outcomes = []
    for request in bundle.get("memory_requests", []):
        memory = registry.get(("MemoryItem", request["memory_id"]))
        if memory is None:
            fail("MEMORY_NOT_AVAILABLE")
        if scope(memory) != tuple(request["authorized_scope"][x] for x in ("tenant_id", "owner_id", "project_id")):
            fail("MEMORY_ACCESS_DENIED")
        if memory["lifecycle"] == "deleted":
            fail("MEMORY_DELETED")
        if request["version"] != memory["version"]:
            fail("MEMORY_VERSION_CONFLICT")
        find("Session", request["session_id"], memory)
        operation = request["operation"]
        if operation == "resume" and (memory["memory_type"] != "research_progress" or request["session_id"] in memory["origin"]["session_ids"]):
            fail("NEW_SESSION_CONTINUATION_REQUIRED")
        if operation == "reuse_as_evidence":
            if memory["memory_type"] != "reusable_result" or memory["freshness"] != "fresh" or memory["applicability"]["version"] != request["target_version"]:
                fail("MEMORY_RECHECK_REQUIRED")
            if memory["review"]["due_at"]["status"] == "known" and timestamp(request["as_of"]) >= timestamp(memory["review"]["due_at"]["value"]):
                fail("MEMORY_RECHECK_REQUIRED")
            for identity in memory["result"]["decision_ids"]:
                if find("DecisionRecord", identity, memory)["decision_status"] != "supported":
                    fail("MEMORY_NOT_PUBLISHABLE")
        elif operation not in ("resume", "select_as_lead"):
            fail("MEMORY_OPERATION_INVALID")
        outcomes.append({"operation": operation, "memory_id": request["memory_id"], "eligibility": "allowed_by_fixture_rules", "runtime_execution": False})
    writes = {}
    for write in bundle.get("memory_writes", []):
        item = find("MemoryItem", write["memory_id"], {**authorized_scope})
        if write["expected_version"] != item["version"]:
            fail("MEMORY_WRITE_VERSION_CONFLICT")
        fingerprint = hashlib.sha256(json.dumps(write["payload"], sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        if write["idempotency_key"] in writes and writes[write["idempotency_key"]] != fingerprint:
            fail("MEMORY_IDEMPOTENCY_CONFLICT")
        writes[write["idempotency_key"]] = fingerprint
    return {"records_checked": len(rows), "memory_requests": outcomes, "semantic_verification": False, "runtime_execution": False}


def validate_manifest():
    manifests = [ROOT / f"testdata/agent-foundation/{area}/manifest.json" for area in ("evidence", "memory")]
    checked = []
    for manifest_path in manifests:
        manifest = load_json(manifest_path)
        for case in manifest["cases"]:
            bundle = load_json(ROOT / case["path"])
            result = validate_bundle(bundle)
            checked.append({"fixture_id": bundle["fixture_id"], **result})
    return checked


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", nargs="?", type=Path)
    args = parser.parse_args()
    result = validate_bundle(load_json(args.fixture)) if args.fixture else validate_manifest()
    print(json.dumps({"status": "passed", "results": result, "semantic_verification": False, "runtime_execution": False}, ensure_ascii=False, indent=2))

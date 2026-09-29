"""Offline runtime structure/reference checks; does not authorize live execution."""
from __future__ import annotations

import json
import re
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlsplit

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry

ROOT = Path(__file__).resolve().parents[3]
SCHEMA = ROOT / "contracts/agent/v0/runtime.schema.json"
ID_FIELDS = {"ResearchProject": "project_id", "Task": "task_id", "Budget": "budget_id",
             "AgentEvent": "event_id", "AgentContext": "context_id", "Run": "run_id",
             "Session": "session_id", "Assessment": "assessment_id", "Receipt": "receipt_id",
             "Evidence": "evidence_id", "Claim": "claim_id", "DecisionRecord": "decision_id",
             "Challenge": "challenge_id", "ResearchPacket": "packet_id", "MemoryItem": "memory_id"}
TRANSITIONS = {"pending": {"running", "blocked", "cancelled"},
               "running": {"blocked", "done", "cancelled"},
               "blocked": {"pending", "running", "cancelled"}, "done": set(), "cancelled": set()}


class ContractError(ValueError):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def load_json(path: Path):
    def reject_constant(value):
        raise ContractError("NONFINITE_JSON")

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ContractError("DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    return json.loads(path.read_text(), parse_constant=reject_constant, object_pairs_hook=unique_object)


def _deny_remote(uri: str):
    raise ContractError("REMOTE_SCHEMA_REFERENCE_FORBIDDEN")


FORMAT_CHECKER = FormatChecker(formats=[])


@FORMAT_CHECKER.checks("date-time", raises=ValueError)
def _date_time(value):
    """Contract profile: RFC3339 shape, real calendar, explicit numeric offset/Z; no leap seconds."""
    if not isinstance(value, str):
        return True  # JSON Schema's type keyword checks non-strings.
    if re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}"
                    r"(?:\.[0-9]+)?(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])", value) is None:
        return False
    parsed = datetime.fromisoformat(value.upper().replace("Z", "+00:00"))
    return parsed.tzinfo is not None


@FORMAT_CHECKER.checks("uri", raises=ValueError)
def _uri(value):
    """Bounded absolute URI syntax profile; application source/authorization policy is separate."""
    if not isinstance(value, str):
        return True
    if re.fullmatch(r"[A-Za-z][A-Za-z0-9+.-]*:[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]*", value) is None \
            or re.search(r"%(?![0-9A-Fa-f]{2})", value):
        return False
    parts = urlsplit(value)
    if parts.netloc:
        parts.port  # Reject malformed/out-of-range ports and malformed IPv6 authorities.
    if parts.scheme.lower() in {"http", "https"} and not parts.hostname:
        return False
    return True


def _check_formats(node):
    if isinstance(node, dict):
        if isinstance(node.get("format"), str) and node["format"] not in FORMAT_CHECKER.checkers:
            raise ContractError("UNSUPPORTED_SCHEMA_FORMAT")
        for child in node.values():
            _check_formats(child)
    elif isinstance(node, list):
        for child in node:
            _check_formats(child)


def validator(schema):
    Draft202012Validator.check_schema(schema)
    _check_formats(schema)
    return Draft202012Validator(schema, format_checker=FORMAT_CHECKER,
                                registry=Registry(retrieve=_deny_remote))


def validate_record(record, schema=None):
    errors = list(validator(schema or load_json(SCHEMA)).iter_errors(record))
    if errors:
        raise ContractError("SCHEMA_INVALID")


def validate_external_reference(record, schema=None):
    schema = dict(schema or load_json(SCHEMA))
    schema["oneOf"] = [{"$ref": "#/$defs/FixtureReference"}]
    validate_record(record, schema)


def scope(row):
    return tuple(row.get(key) for key in ("tenant_id", "owner_id", "project_id"))


def memory_preview(memory):
    """Canonical bounded context projection of the exact registered memory version."""
    value = json.dumps({key: memory[key] for key in ["memory_type", "version", "freshness", "progress", "result"]},
                       ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return value[:4000], len(value) > 4000


def validate_bundle(bundle, *, authorized_scope, knowledge_records=(), knowledge_schema=None, external_registry=None):
    """Authorization comes from the caller, not context text. Fixtures are not live grants."""
    schema = load_json(SCHEMA)
    rows = bundle["records"]
    for row in rows:
        validate_record(row, schema)
    if knowledge_records:
        if knowledge_schema is None:
            peer_path = SCHEMA.with_name("knowledge.schema.json")
            if not peer_path.exists():
                raise ContractError("PEER_SCHEMA_REQUIRED")
            knowledge_schema = load_json(peer_path)
        for row in knowledge_records:
            validate_record(row, knowledge_schema)
    registry = {}
    external = bundle.get("external_refs", []) if external_registry is None else external_registry
    for row in external:
        validate_external_reference(row, schema)
    for row in [*rows, *knowledge_records, *external]:
        kind = row.get("record_type")
        if kind not in ID_FIELDS or not row.get(ID_FIELDS[kind]):
            raise ContractError("REGISTRY_RECORD_INVALID")
        key = kind, row[ID_FIELDS[kind]]
        if key in registry:
            raise ContractError("DUPLICATE_RECORD_ID")
        registry[key] = row
    auth = scope(authorized_scope)

    def find(kind, identity, parent, *, same_run=False):
        row = registry.get((kind, identity))
        if row is None:
            raise ContractError("REFERENCE_MISSING")
        if scope(row) != scope(parent):
            raise ContractError("REFERENCE_SCOPE_MISMATCH")
        if same_run and row.get("run_id") != parent.get("run_id"):
            raise ContractError("REFERENCE_RUN_MISMATCH")
        return row

    for row in rows:
        if scope(row) != auth:
            raise ContractError("AUTHORIZED_SCOPE_MISMATCH")
        if row["project_id"] is not None and row["record_type"] != "ResearchProject":
            find("ResearchProject", row["project_id"], row)
        if "run_id" in row:
            find("Run", row["run_id"], row)

    def verified_use(identity, parent, *, context_id=None, entry_id=None):
        proof = find("Assessment", identity, parent)
        if proof.get("status") != "confirmed" or proof.get("observed_model_usage") is not True \
                or proof.get("run_id") != parent.get("run_id"):
            raise ContractError("MODEL_USE_UNVERIFIED")
        if context_id is not None and (proof.get("context_id") != context_id or proof.get("entry_id") != entry_id):
            raise ContractError("USE_TRACE_CONTEXT_MISMATCH")
        return proof

    tasks = {row["task_id"]: row for row in rows if row["record_type"] == "Task"}
    for row in rows:
        kind = row["record_type"]
        if kind == "Task":
            run = find("Run", row["run_id"], row)
            if not set(row["allowed_tools"]) <= set(run.get("allowed_tools", [])):
                raise ContractError("TOOL_SCOPE_DENIED")
            if row["agent_id"] not in run.get("agent_ids", []):
                raise ContractError("AGENT_SCOPE_DENIED")
            budget = find("Budget", row["budget_id"], row, same_run=True)
            visited = {row["task_id"]}
            parent = row
            depth = 0
            while parent["parent_task_id"] is not None:
                parent = find("Task", parent["parent_task_id"], row, same_run=True)
                if "budget_id" not in parent or "parent_task_id" not in parent:
                    raise ContractError("TASK_PARENT_DETAIL_REQUIRED")
                if parent["task_id"] in visited:
                    raise ContractError("TASK_CYCLE")
                visited.add(parent["task_id"])
                depth += 1
                if parent["budget_id"] != row["budget_id"]:
                    raise ContractError("CHILD_BUDGET_NOT_SHARED")
            if depth > budget["limits"]["max_delegation_depth"]:
                raise ContractError("DELEGATION_DEPTH_EXCEEDED")
            for dependency in row["dependencies"]:
                dep = find("Task", dependency, row, same_run=True)
                if row["status"] in {"running", "done"} and dep["status"] != "done":
                    raise ContractError("DEPENDENCY_NOT_DONE")
            for identity in row["evidence_ids"]:
                find("Evidence", identity, row, same_run=True)
        elif kind == "Budget":
            active = {}
            keys = set()
            reservation_ids = set()
            for reservation in row["reservations"]:
                find("Task", reservation["task_id"], row, same_run=True)
                if reservation["idempotency_key"] in keys or reservation["reservation_id"] in reservation_ids:
                    raise ContractError("DUPLICATE_RESERVATION")
                keys.add(reservation["idempotency_key"])
                reservation_ids.add(reservation["reservation_id"])
                if reservation["state"] == "reserved":
                    resource = reservation["resource"]
                    active[resource] = active.get(resource, 0) + reservation["amount"]
                if reservation["state"] == "settled" and reservation["settled_amount"] > reservation["amount"]:
                    raise ContractError("RESERVATION_OVERDRAWN")
            for resource in ["decision_steps", "model_calls", "tool_calls", "input_tokens", "output_tokens"]:
                usage = row["usage"][resource]
                limit = row["limits"].get("max_" + resource, row["limits"].get(resource))
                limit = {"status": "known", "value": limit} if isinstance(limit, int) else limit
                if usage["status"] == limit["status"] == "known" and usage["value"] + active.get(resource, 0) > limit["value"]:
                    raise ContractError("BUDGET_LIMIT_EXCEEDED")
            used_cost, limit_cost = row["usage"]["cost"], row["limits"]["cost"]
            if used_cost["status"] == limit_cost["status"] == "known":
                if used_cost["value"]["currency"] != limit_cost["value"]["currency"]:
                    raise ContractError("BUDGET_CURRENCY_MISMATCH")
                if Decimal(used_cost["value"]["amount"]) > Decimal(limit_cost["value"]["amount"]):
                    raise ContractError("BUDGET_LIMIT_EXCEEDED")
        elif kind == "AgentContext":
            entry_ids = set()
            for entry in row["entries"]:
                if entry["entry_id"] in entry_ids:
                    raise ContractError("DUPLICATE_CONTEXT_ENTRY")
                entry_ids.add(entry["entry_id"])
                if entry["source_run_id"] is not None:
                    find("Run", entry["source_run_id"], row)
                if entry["source_memory_id"] is not None:
                    memory = find("MemoryItem", entry["source_memory_id"], row)
                    if memory["lifecycle"] != "active":
                        raise ContractError("MEMORY_NOT_REUSABLE")
                    if memory["version"] != entry["source_memory_version"]:
                        raise ContractError("MEMORY_VERSION_MISMATCH")
                    if entry["purpose"] == "result_reuse" and memory["memory_type"] != "reusable_result":
                        raise ContractError("MEMORY_RESULT_REQUIRED")
                    if entry["purpose"] == "result_reuse" and memory["freshness"] != "fresh":
                        raise ContractError("MEMORY_RECHECK_REQUIRED")
                    if entry["purpose"] == "result_reuse":
                        due = memory["review"]["due_at"]
                        if due["status"] == "known" and datetime.fromisoformat(row["prepared_at"].upper().replace("Z", "+00:00")) >= datetime.fromisoformat(due["value"].upper().replace("Z", "+00:00")):
                            raise ContractError("MEMORY_RECHECK_REQUIRED")
                        for identity in memory["result"]["decision_ids"]:
                            if find("DecisionRecord", identity, row)["decision_status"] != "supported":
                                raise ContractError("MEMORY_NOT_PUBLISHABLE")
                        for identity in memory["result"]["claim_ids"]:
                            claim = find("Claim", identity, row)
                            if claim["decision_status"] != "supported" or claim["freshness"] != "fresh":
                                raise ContractError("MEMORY_NOT_PUBLISHABLE")
                    preview, clipped = memory_preview(memory)
                    if entry["content"] != preview or entry["truncated"] != clipped:
                        raise ContractError("MEMORY_CONTENT_BINDING_INVALID")
                for identity in entry["used"]["verification_refs"]:
                    verified_use(identity, row, context_id=row["context_id"], entry_id=entry["entry_id"])
        elif kind == "AgentEvent":
            run = find("Run", row["run_id"], row)
            if row["agent_id"] not in run.get("agent_ids", []):
                raise ContractError("AGENT_SCOPE_DENIED")
            if row["task_id"] is not None:
                task = find("Task", row["task_id"], row, same_run=True)
                if task["agent_id"] != row["agent_id"]:
                    raise ContractError("EVENT_AGENT_MISMATCH")
            refs = row["references"]
            for kind_name, field in [("Evidence", "evidence_ids"), ("MemoryItem", "memory_ids"), ("DecisionRecord", "decision_ids")]:
                for identity in refs[field]:
                    find(kind_name, identity, row)
            if refs["context_id"] is not None:
                find("AgentContext", refs["context_id"], row, same_run=True)
            for identity in refs["verification_ids"]:
                verified_use(identity, row)
            before, after = row["before_state"], row["after_state"]
            if before is not None and after is not None and before != after and after not in TRANSITIONS[before]:
                raise ContractError("TASK_TRANSITION_INVALID")

    def visit(identity, chain):
        if identity in chain:
            raise ContractError("TASK_CYCLE")
        for dependency in tasks[identity]["dependencies"]:
            if dependency in tasks:
                visit(dependency, chain | {identity})
    for identity in tasks:
        visit(identity, set())
    seen_events = set()
    last_sequence = {}
    last_state = {}
    for row in rows:
        if row["record_type"] != "AgentEvent":
            continue
        key = row["run_id"], row["idempotency_key"]
        if key in seen_events or row["sequence"] <= last_sequence.get(row["run_id"], 0):
            raise ContractError("EVENT_REPLAY_CONFLICT")
        seen_events.add(key)
        last_sequence[row["run_id"]] = row["sequence"]
        if row["task_id"] is not None and row["after_state"] is not None:
            previous = last_state.get(row["task_id"])
            if previous is not None and row["before_state"] != previous:
                raise ContractError("EVENT_STATE_DISCONTINUITY")
            last_state[row["task_id"]] = row["after_state"]
    for task_id, state in last_state.items():
        if tasks[task_id]["status"] != state:
            raise ContractError("TASK_EVENT_STATE_MISMATCH")
    return {"runtime_records_checked": len(rows), "semantic_verification": False,
            "live_authorization_verified": False, "runtime_execution": False}

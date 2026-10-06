"""Deterministic model-only projection; checkpoint/server evidence remains authoritative."""

from __future__ import annotations

import copy
import hashlib

from .agent_budget import canonical
from .agent_decision_instruction import CHECK_CAPACITY_POLICIES
from .agent_investigations import investigation_state

CONTEXT_VERSION = "agent-decision-context/2"
PREVIEW_CHARACTERS = 1600


def decision_context(state, budget):
    """Intern repeated objects, retain every check history and explicit omission metadata.

    Only checkpoint data affects bytes. A fresh ledger summary must never change a
    settled decision's identity after a crash before checkpointing.
    """
    records, checks, specs = {}, {}, {}

    def intern(table, prefix, value):
        identity = prefix + hashlib.sha256(canonical(value).encode()).hexdigest()
        table.setdefault(identity, value)
        return identity

    def spec(value):
        return {"claim_spec_ref": intern(specs, "spec-", copy.deepcopy(value))}

    def record(value):
        item = copy.deepcopy(value)
        # Shared authorization envelope remains server-owned and is never delegated.
        for key in ("tenant_id", "owner_id", "project_id", "run_id", "schema_version"):
            item.pop(key, None)
        if item.get("record_type") == "Claim":
            draft = {key: item.pop(key) for key in ("text", "kind", "applicability")}
            item.update(spec(draft))
        return {"record_ref": intern(records, "record-", item)}

    def packet(value):
        if not value:
            return {}
        projected = project(value)
        return {
            "check_ref": intern(checks, "check-", projected),
            **({"status": value["status"]} if "status" in value else {}),
        }

    def project(value):
        if isinstance(value, list):
            return [project(item) for item in value]
        if not isinstance(value, dict):
            return value
        if value.get("record_type") in {"Claim", "DecisionRecord"}:
            return record(value)
        result = {}
        for key, item in value.items():
            if key == "claim_specs":
                result[key] = [spec(claim) for claim in item]
            elif key == "expected_claim" and item:
                result[key] = spec(item)
            elif key == "packet":
                result[key] = packet(item)
            else:
                result[key] = project(item)
        return result

    evidence = []
    for value in state.get("evidence", []):
        item = copy.deepcopy(value)
        for key in ("tenant_id", "owner_id", "project_id", "run_id", "schema_version"):
            item.pop(key, None)
        snapshot = item.get("snapshot", {})
        text = snapshot.get("text")
        if isinstance(text, str):
            preview = text[:PREVIEW_CHARACTERS]
            snapshot["text"] = preview
            snapshot["context_preview_only"] = len(preview) < len(text)
            snapshot["projection"] = {
                "shown_codepoints": [0, len(preview)],
                "total_codepoints": len(text),
                "omitted_codepoints": len(text) - len(preview),
                "full_text_sha256": hashlib.sha256(text.encode()).hexdigest(),
                "authority": "full original retained by evidence service and checkpoint",
            }
        evidence.append(item)

    investigations, _ = investigation_state(state)
    investigation_views = []
    for key, entry in sorted(investigations.items()):
        view = project({name: value for name, value in entry.items() if name != "history"})
        view["investigation_key"] = key
        view["history"] = [packet(value) for value in entry.get("history", [])]
        investigation_views.append(view)
    latest = state.get("observations", [])[-1:]
    observations = []
    for value in latest:
        if value.get("check_id") or value.get("packet_id"):
            observations.append(
                {
                    "action": value.get("action"),
                    **packet({key: item for key, item in value.items() if key != "action"}),
                }
            )
        elif value.get("action") == "read_source":
            observations.append(
                {key: project(item) for key, item in value.items() if key != "records"}
                | {"evidence_ids": [item.get("evidence_id") for item in value.get("records", [])]}
            )
        elif value.get("action") == "search":
            observations.append(
                {key: project(item) for key, item in value.items() if key != "candidates"}
                | {
                    "candidates": [
                        {"source_id": item["source_id"]} for item in value.get("candidates", [])
                    ]
                }
            )
        else:
            observations.append(project(value))
    payload = {
        "context_version": CONTEXT_VERSION,
        "phase": "planning"
        if not state.get("tasks")
        else "adjudication"
        if investigations
        else "research",
        "original_question": state["question"],
        "plan_version": state["plan_version"],
        "tasks": project(state["tasks"]),
        "observations": observations,
        "action_gaps": [
            project(
                {
                    key: item
                    for key, item in value.items()
                    if key not in {"records", "claim_specs", "candidates"}
                }
            )
            for value in state.get("observations", [])
            if value.get("errorCode")
        ],
        "candidates": project(state.get("candidates", [])),
        "evidence": evidence,
        "packet": packet(state.get("packet", {})),
        "investigations": investigation_views,
        "allowed_tools": state["requested_scopes"],
        "remaining_decisions": budget.max_decision_steps - state["decision_steps"],
        "budget_usage": copy.deepcopy(state.get("agent_usage", {})),
        "prior_context": {
            key: state.get("context_snapshot", {}).get(key)
            for key in ["sessionSummary", "recentConversation", "memories", "truncated"]
        },
        "projection_notes": {
            "references": "Resolve claim_spec_ref, record_ref, check_ref in canonical_objects",
            "shared_record_envelope": (
                "tenant/owner/project/run/schema envelope omitted; server "
                "authoritative"
            ),
            "latest_action_only": True,
            "source_text": (
                "Explicit bounded previews; checked quotes and offsets retained "
                "in records"
            ),
            "closure": (
                "Use the next budgeted decision to finish when all original "
                "obligations are verified; "
                "publication is mechanical and tool-budgeted. Preserve unresolved gaps."
            ),
        },
    }
    if state.get("instruction_policy") in CHECK_CAPACITY_POLICIES:
        payload["actual_read_source_kinds"] = sorted({
            item["source"]["kind"] for item in evidence
            if isinstance(item.get("source", {}).get("kind"), str)
        })
    from .agent_requirements import evaluate_coverage

    payload["original_requirements"] = copy.deepcopy(state.get("original_requirements"))
    payload["requirement_bindings"] = copy.deepcopy(state.get("requirement_bindings", []))
    payload["requirement_coverage"] = evaluate_coverage(
        state.get("original_requirements"),
        state["tasks"],
        state.get("investigations", {}),
        state.get("requirement_bindings", []),
    )
    # Build last: interning packet/tasks may add canonical objects.
    payload["canonical_objects"] = {"claim_specs": specs, "records": records, "checks": checks}
    from .progress_memory import frozen_progress

    selected = frozen_progress(state.get("context_snapshot", {}))
    if selected is not None:
        payload["context_version"] = "agent-decision-context/3"
        payload["prior_progress"] = selected[0]
    return payload

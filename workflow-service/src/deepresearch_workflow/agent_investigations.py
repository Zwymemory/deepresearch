"""Checkpoint-owned investigation identity and immutable successful check history.

These keys organize a run; authorization and publication eligibility remain server-owned.
"""

from __future__ import annotations

import copy
import hashlib

from .agent_budget import canonical


class InvestigationError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def scope_key(claims):
    """Claim order and unknown-reason wording do not create another investigation."""
    normalized = []
    for claim in claims:
        item = copy.deepcopy(claim)
        scope = item["applicability"]
        scope["conditions"] = sorted(set(scope.get("conditions", [])))
        for field in ("version", "valid_at"):
            if scope[field]["status"] == "unknown":
                scope[field].pop("reason", None)
        normalized.append(canonical(item))
    if len(set(normalized)) != len(normalized):
        raise InvestigationError("DUPLICATE_CLAIM")
    return (
        "investigation-" + hashlib.sha256(canonical(sorted(normalized)).encode()).hexdigest()[:48]
    )


def investigation_state(state):
    investigations = copy.deepcopy(state.get("investigations", {}))
    bindings = dict(state.get("task_investigations", {}))
    # Old candidate checkpoints had one packet. Preserve it rather than assigning
    # it to a newly selected task or silently discarding a completed check.
    packet = state.get("packet", {})
    if not investigations and packet.get("claim_specs") and not packet.get("errorCode"):
        key = scope_key(packet["claim_specs"])
        task_ids = [state["tasks"][0]["task_id"]] if state.get("tasks") else []
        investigations[key] = {
            "investigation_id": key,
            "claim_specs": copy.deepcopy(packet["claim_specs"]),
            "task_ids": task_ids,
            "packet": copy.deepcopy(packet),
            "history": [copy.deepcopy(packet)],
        }
        bindings.update({task_id: key for task_id in task_ids})
    return investigations, bindings


def select_investigation(state, task, claims, requested_id=None, *, criterion_scoped=False):
    investigations, bindings = investigation_state(state)
    key = scope_key(claims)
    bound = bindings.get(task["task_id"])
    if requested_id is not None:
        requested_key = next(
            (
                identity
                for identity, saved in investigations.items()
                if saved.get("packet", {}).get("investigation_id") == requested_id
            ),
            requested_id,
        )
        if requested_key not in investigations:
            raise InvestigationError("INVESTIGATION_MISSING")
        if requested_key != key:
            raise InvestigationError("CLAIM_SCOPE_CHANGED")
    keys = [bound] if isinstance(bound, str) else bound or []
    if keys and key not in keys and not criterion_scoped:
        raise InvestigationError("CLAIM_SCOPE_CHANGED")
    entry = investigations.setdefault(
        key,
        {
            "investigation_id": key,
            "claim_specs": sorted(copy.deepcopy(claims), key=canonical),
            "task_ids": [],
            "packet": {},
            "history": [],
        },
    )
    if task["task_id"] not in entry["task_ids"]:
        entry["task_ids"].append(task["task_id"])
    bindings[task["task_id"]] = list(dict.fromkeys([*keys, key]))
    return key, entry, investigations, bindings


def accept_check(entry, result):
    """Failures are observations. They never become the successful current packet."""
    if result.get("errorCode"):
        return False
    claims = [row for row in result.get("records", []) if row.get("record_type") == "Claim"]
    if not claims or any(
        row.get("decision_status") not in {"supported", "refuted", "contested", "insufficient"}
        for row in claims
    ):
        raise InvestigationError("CHECK_RESULT_INVALID")
    history = entry["history"]
    identity = result.get("check_id")
    previous = next((row for row in history if identity and row.get("check_id") == identity), None)
    if previous is not None:
        if canonical(previous) != canonical(result):
            raise InvestigationError("CHECK_REPLAY_CHANGED")
    else:
        if len(history) >= 3:
            raise InvestigationError("DISPUTE_LIMIT")
        history.append(copy.deepcopy(result))
    entry["packet"] = copy.deepcopy(result)
    return True


def current_packets(state):
    investigations, _ = investigation_state(state)
    return [entry["packet"] for entry in investigations.values() if entry.get("packet")]


def public_investigations(state):
    investigations, _ = investigation_state(state)
    return [
        {
            "investigation_id": entry.get("packet", {}).get("investigation_id") or key,
            "task_ids": entry["task_ids"],
            "claim_specs": entry["claim_specs"],
            "check_id": entry.get("packet", {}).get("check_id"),
            "dispute_round": entry.get("packet", {}).get("dispute_round", -1),
            "records": entry.get("packet", {}).get("records", []),
            "gaps": entry.get("packet", {}).get("gaps", []),
        }
        for key, entry in investigations.items()
    ]

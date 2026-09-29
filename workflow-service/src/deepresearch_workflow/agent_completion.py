"""Structural criterion coverage and dependency freshness; semantic planning is not certified."""

from __future__ import annotations

import copy
import hashlib

from .agent_budget import canonical
from .agent_investigations import InvestigationError, scope_key


def java_order(value):
    return value.encode("utf-16-be", errors="surrogatepass")


def normalized_claim(claim):
    value = {name: copy.deepcopy(claim[name]) for name in ("text", "kind", "applicability")}
    scope = value["applicability"]
    scope["conditions"] = sorted(set(scope.get("conditions", [])), key=java_order)
    for name in ("version", "valid_at"):
        if scope[name]["status"] == "unknown":
            scope[name]["reason"] = "Not independently established"
    return value


def criterion_id(run_id, task_id, index, text):
    value = {"run_id": run_id, "task_id": task_id, "index": index, "text": text}
    return "criterion-" + hashlib.sha256(canonical(value).encode()).hexdigest()[:48]


def server_identity(claims):
    specs = []
    for claim in claims:
        value = {k: copy.deepcopy(claim[k]) for k in ("text", "kind", "applicability")}
        value["applicability"]["conditions"] = sorted(
            set(value["applicability"].get("conditions", [])), key=java_order
        )
        specs.append(canonical(value))
    return hashlib.sha256(canonical(sorted(specs, key=java_order)).encode()).hexdigest()


def ensure_criteria(run_id, tasks):
    for task in tasks:
        saved = {c["criterion_id"]: c for c in task.get("criteria", [])}
        expected = []
        for index, text in enumerate(task["acceptance_criteria"]):
            identity = criterion_id(run_id, task["task_id"], index, text)
            entry = saved.pop(identity, {"criterion_id": identity, "text": text, "index": index})
            if entry["text"] != text or entry["index"] != index:
                raise InvestigationError("CRITERION_IDENTITY_CHANGED")
            expected.append(entry)
        if saved:
            raise InvestigationError("CRITERION_IDENTITY_CHANGED")
        task["criteria"] = expected


def bind_criteria(task, claims, bindings):
    if not bindings:
        return []  # Legacy checks may preserve evidence but never cover a criterion implicitly.
    indices = [b["claim_index"] for b in bindings]
    if len(set(indices)) != len(indices) or set(indices) - set(range(len(claims))):
        raise InvestigationError("CRITERION_COVERAGE_INVALID")
    if len({b["criterion_id"] for b in bindings}) != len(bindings):
        raise InvestigationError("CRITERION_COVERAGE_INVALID")
    selected = []
    proposed = copy.deepcopy(task["criteria"])
    proposed_by_id = {c["criterion_id"]: c for c in proposed}
    for binding in bindings:
        entry = proposed_by_id.get(binding["criterion_id"])
        if entry is None:
            raise InvestigationError("CRITERION_NOT_IN_TASK")
        claim = normalized_claim(claims[binding["claim_index"]])
        if entry.get("expected_claim") is not None and canonical(
            entry["expected_claim"]
        ) != canonical(claim):
            raise InvestigationError("CRITERION_SCOPE_CHANGED")
        entry["expected_claim"] = claim
        selected.append(entry["criterion_id"])
    scopes = [canonical(c["expected_claim"]) for c in proposed if c.get("expected_claim")]
    if len(set(scopes)) != len(scopes):
        raise InvestigationError("CRITERION_CLAIM_REUSED")
    key = scope_key(claims)
    if any(
        c.get("investigation_key") and c["investigation_key"] != key
        for c in proposed
        if c["criterion_id"] in selected
    ):
        raise InvestigationError("CRITERION_SCOPE_CHANGED")
    task["criteria"] = proposed
    return selected


def signature(task, investigations):
    return [
        {
            "criterion_id": c["criterion_id"],
            "expected_claim": c.get("expected_claim"),
            "current_call_id": investigations.get(c.get("investigation_key"), {}).get(
                "latest_call_id"
            ),
        }
        for c in task.get("criteria", [])
    ]


def dependency_snapshot(task, tasks, investigations):
    by_id = {t["task_id"]: t for t in tasks}
    return {
        identity: signature(by_id[identity], investigations)
        for identity in task["dependencies"]
        if identity in by_id
    }


def begin_attempt(task, selected, entry, key, investigation_key, tasks, investigations):
    if entry.get("latest_call_id") != key:
        entry["latest_call_id"] = key
        entry["attempt_status"] = "pending"
    for criterion in task["criteria"]:
        if criterion["criterion_id"] in selected:
            previous = criterion.get("investigation_key")
            if previous and previous != investigation_key:
                raise InvestigationError("CRITERION_SCOPE_CHANGED")
            criterion["investigation_key"] = investigation_key
            criterion["investigation_id"] = server_identity(entry["claim_specs"])
            if criterion.get("last_call_id") != key:
                criterion["last_call_id"] = key
                criterion["dependency_snapshot"] = dependency_snapshot(task, tasks, investigations)


def recompute_tasks(tasks, investigations):
    by_id = {t["task_id"]: t for t in tasks}
    visited = {}

    def visit(task, trail):
        identity = task["task_id"]
        if identity in visited:
            return visited[identity]
        if identity in trail:
            return False
        prerequisites = all(
            dep in by_id and visit(by_id[dep], trail | {identity}) for dep in task["dependencies"]
        )
        snapshot = dependency_snapshot(task, tasks, investigations)
        resolved = bool(task.get("criteria"))
        for criterion in task.get("criteria", []):
            expected = criterion.get("expected_claim")
            entry = investigations.get(criterion.get("investigation_key"), {})
            packet = entry.get("packet", {})
            rows = [
                r
                for r in packet.get("records", [])
                if r.get("record_type") == "Claim"
                and expected
                and canonical(normalized_claim(r)) == canonical(expected)
            ]
            state, gap = "uncovered", "No scoped check is bound to this stored criterion"
            if expected:
                if not prerequisites or criterion.get("dependency_snapshot") != snapshot:
                    state, gap = (
                        "stale",
                        "Prerequisite proof changed or is incomplete; recheck this criterion",
                    )
                elif entry.get("attempt_status") != "accepted":
                    state, gap = (
                        "blocked",
                        "Latest investigation attempt failed or has not completed",
                    )
                elif len(rows) != 1 or rows[0].get("decision_status") not in {
                    "supported",
                    "refuted",
                }:
                    state, gap = "blocked", "Current scoped adjudication remains unresolved"
                else:
                    state, gap = "resolved", None
            criterion["status"] = state
            criterion["gaps"] = [gap] if gap else []
            resolved = resolved and state == "resolved"
        good = resolved and prerequisites and task["status"] != "cancelled"
        if task["status"] != "cancelled":
            task["status"] = (
                "done"
                if good
                else "blocked"
                if any(c.get("expected_claim") for c in task.get("criteria", []))
                else "pending"
            )
        visited[identity] = good
        return good

    for task in tasks:
        visit(task, set())
    return tasks

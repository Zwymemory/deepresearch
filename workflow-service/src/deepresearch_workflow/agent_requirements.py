"""Immutable original-question obligations and structural, provenance-bound coverage.

Semantic extraction belongs to an existing budgeted planning response. Exact question
anchors and stable IDs cannot certify that the planner captured every meaning. These
helpers are pure; persisted server manifests and evidence authority remain mandatory.
"""

from __future__ import annotations

import copy
import hashlib
import json
from typing import Annotated, Literal

from pydantic import Field, ValidationError, field_validator, model_validator

from .domain import StrictModel

CONTRACT_VERSION = "agent-original-requirements/1"
MAX_REQUIREMENTS = 32


class RequirementError(ValueError):
    """Safe machine-readable failure; no raw question or source text in errors."""

    def __init__(self, code):
        self.code = code
        super().__init__(code)


# Declaration types have no dependency on agent_protocol, which imports them for
# AgentDecision. Operational validators below lazily reuse existing Claim/criterion
# helpers after modules are loaded, retaining one coverage/scope algorithm.
class RequirementKnownValue(StrictModel):
    status: Literal["known"]
    value: str = Field(min_length=1, max_length=200, pattern=r"\S")


class RequirementUnknownValue(StrictModel):
    status: Literal["unknown"]
    value: None
    reason: str = Field(min_length=1, max_length=400, pattern=r"\S")


class RequirementKnownTime(RequirementKnownValue):
    @field_validator("value")
    @classmethod
    def effective_instant(cls, value):
        from .agent_protocol import valid_at_instant

        valid_at_instant(value)
        return value


class RequirementScope(StrictModel):
    subject: str = Field(min_length=1, max_length=1000, pattern=r"\S")
    version: Annotated[
        RequirementKnownValue | RequirementUnknownValue, Field(discriminator="status")
    ]
    valid_at: Annotated[
        RequirementKnownTime | RequirementUnknownValue, Field(discriminator="status")
    ]
    conditions: list[Annotated[str, Field(min_length=1, max_length=400, pattern=r"\S")]] = Field(
        default_factory=list, max_length=20
    )


def canonical(value):
    # Same wire-independent canonical JSON used by agent_budget; avoiding eager
    # imports permits protocol -> declaration models without a circular import.
    return json.dumps(
        value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False
    )


class QuestionSpan(StrictModel):
    start: int = Field(ge=0, strict=True)
    end: int = Field(ge=1, strict=True)

    @model_validator(mode="after")
    def nonempty(self):
        if self.end <= self.start:
            raise ValueError("question span must be nonempty")
        return self


class RequirementDraft(StrictModel):
    text: str = Field(min_length=1, max_length=400, pattern=r"\S")
    question_spans: list[QuestionSpan] = Field(min_length=1, max_length=16)
    kind: Literal["factual", "inference", "recommendation"]
    applicability: RequirementScope

    @field_validator("applicability")
    @classmethod
    def substantive_scope(cls, scope):
        values = [scope.subject, *scope.conditions]
        for tagged in (scope.version, scope.valid_at):
            values.append(tagged.value if tagged.status == "known" else tagged.reason)
        if any(not value.strip() for value in values):
            raise ValueError("scope must contain substantive values")
        return scope


class RequirementBinding(StrictModel):
    requirement_id: str = Field(min_length=1, max_length=128, pattern=r"^\S+$")
    criterion_id: str = Field(min_length=1, max_length=128, pattern=r"^\S+$")


def _sha(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def _draft(value):
    from .agent_completion import normalized_claim
    from .agent_protocol import ClaimScope

    try:
        result = RequirementDraft.model_validate(value).model_dump(mode="json")
    except (ValidationError, TypeError, ValueError) as invalid:
        raise RequirementError("REQUIREMENT_INVALID") from invalid
    result["applicability"] = ClaimScope.model_validate(result["applicability"]).model_dump(
        mode="json"
    )
    result["applicability"] = normalized_claim(
        {"text": result["text"], "kind": result["kind"], "applicability": result["applicability"]}
    )["applicability"]
    spans = sorted(result["question_spans"], key=lambda span: (span["start"], span["end"]))
    if len({canonical(span) for span in spans}) != len(spans):
        raise RequirementError("REQUIREMENT_DUPLICATE_SPAN")
    result["question_spans"] = spans
    return result


def _build(run_id, question_hash, question_length, drafts):
    if not isinstance(run_id, str) or not run_id.strip() or len(run_id) > 128:
        raise RequirementError("REQUIREMENT_RUN_INVALID")
    if not isinstance(drafts, list) or not 1 <= len(drafts) <= MAX_REQUIREMENTS:
        raise RequirementError("REQUIREMENTS_MISSING_OR_LIMIT")
    declarations = [_draft(value) for value in drafts]
    if len({canonical(value) for value in declarations}) != len(declarations):
        raise RequirementError("REQUIREMENT_DUPLICATE")
    requirements = []
    for declaration in declarations:
        if any(span["end"] > question_length for span in declaration["question_spans"]):
            raise RequirementError("REQUIREMENT_ANCHOR_INVALID")
        identity = (
            "requirement-"
            + _sha(
                {
                    "contract_version": CONTRACT_VERSION,
                    "run_id": run_id,
                    "question_sha256": question_hash,
                    "declaration": declaration,
                }
            )[:48]
        )
        requirements.append({"requirement_id": identity, **declaration})
    manifest = {
        "contract_version": CONTRACT_VERSION,
        "run_id": run_id,
        "question_sha256": question_hash,
        "question_length": question_length,
        "requirements": sorted(requirements, key=lambda item: item["requirement_id"]),
    }
    return {**manifest, "manifest_sha256": _sha(manifest)}


def _check_anchors(question, manifest):
    covered = bytearray(len(question))
    for requirement in manifest["requirements"]:
        for span in requirement["question_spans"]:
            if not question[span["start"] : span["end"]].strip():
                raise RequirementError("REQUIREMENT_ANCHOR_INVALID")
            covered[span["start"] : span["end"]] = b"\x01" * (span["end"] - span["start"])
    if any(not char.isspace() and not covered[index] for index, char in enumerate(question)):
        raise RequirementError("REQUIREMENT_QUESTION_REGION_UNASSIGNED")


def freeze_requirements(run_id, question, drafts, existing=None):
    """Create once or replay exactly; no evidence/plan step may redefine obligations."""
    if not isinstance(question, str) or not question.strip():
        raise RequirementError("REQUIREMENT_QUESTION_INVALID")
    result = _build(
        run_id, hashlib.sha256(question.encode("utf-8")).hexdigest(), len(question), drafts
    )
    _check_anchors(question, result)
    if existing is not None:
        previous = validate_manifest(existing, run_id=run_id, question=question)
        if canonical(previous) != canonical(result):
            raise RequirementError("REQUIREMENTS_CHANGED")
    return result


def validate_manifest(manifest, run_id=None, question=None):
    """Check identities; caller still compares with an immutable server-owned copy."""
    fields = {
        "contract_version",
        "run_id",
        "question_sha256",
        "question_length",
        "requirements",
        "manifest_sha256",
    }
    if not isinstance(manifest, dict) or set(manifest) != fields:
        raise RequirementError("REQUIREMENT_MANIFEST_INVALID")
    length, question_hash = manifest["question_length"], manifest["question_sha256"]
    if (
        manifest["contract_version"] != CONTRACT_VERSION
        or type(length) is not int
        or length < 1
        or not isinstance(question_hash, str)
        or len(question_hash) != 64
        or any(char not in "0123456789abcdef" for char in question_hash)
    ):
        raise RequirementError("REQUIREMENT_MANIFEST_INVALID")
    if run_id is not None and run_id != manifest["run_id"]:
        raise RequirementError("REQUIREMENT_ORIGINAL_BINDING_CHANGED")
    rows = manifest["requirements"]
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise RequirementError("REQUIREMENT_MANIFEST_INVALID")
    rebuilt = _build(
        manifest["run_id"],
        question_hash,
        length,
        [{k: v for k, v in row.items() if k != "requirement_id"} for row in rows],
    )
    if canonical(rebuilt) != canonical(manifest):
        raise RequirementError("REQUIREMENT_IDENTITY_CHANGED")
    if question is not None:
        if (
            not isinstance(question, str)
            or len(question) != length
            or hashlib.sha256(question.encode("utf-8")).hexdigest() != question_hash
        ):
            raise RequirementError("REQUIREMENT_ORIGINAL_BINDING_CHANGED")
        _check_anchors(question, rebuilt)
    return rebuilt


def _criteria(run_id, tasks):
    from .agent_completion import ensure_criteria
    from .agent_investigations import InvestigationError

    proposed = copy.deepcopy(tasks)
    if not isinstance(proposed, list):
        raise RequirementError("REQUIREMENT_TASK_INVALID")
    ids = [task.get("task_id") for task in proposed if isinstance(task, dict)]
    if len(ids) != len(proposed) or len(set(ids)) != len(ids):
        raise RequirementError("REQUIREMENT_TASK_INVALID")
    try:
        ensure_criteria(run_id, proposed)
        result = {}
        for task in proposed:
            for criterion in task["criteria"]:
                identity = criterion["criterion_id"]
                if identity in result:
                    raise RequirementError("REQUIREMENT_CRITERION_DUPLICATE")
                result[identity] = (task, criterion)
        return proposed, result
    except (KeyError, TypeError, InvestigationError) as invalid:
        raise RequirementError("REQUIREMENT_CRITERION_INVALID") from invalid


def bind_requirements(manifest, tasks, bindings, existing=None):
    """Append associations; an original obligation and its criterion cannot be replaced."""
    manifest = validate_manifest(manifest)
    _, criteria = _criteria(manifest["run_id"], tasks)
    required = {row["requirement_id"] for row in manifest["requirements"]}
    if not isinstance(bindings, list) or (existing is not None and not isinstance(existing, list)):
        raise RequirementError("REQUIREMENT_BINDING_INVALID")
    result, criterion_owners = {}, {}
    for batch in (existing or [], bindings):
        seen = set()
        for value in batch:
            try:
                row = RequirementBinding.model_validate(value).model_dump(mode="json")
            except (ValidationError, ValueError, TypeError) as invalid:
                raise RequirementError("REQUIREMENT_BINDING_INVALID") from invalid
            identity, criterion_id = row["requirement_id"], row["criterion_id"]
            if identity in seen:
                raise RequirementError("REQUIREMENT_BINDING_DUPLICATE")
            seen.add(identity)
            if identity not in required:
                raise RequirementError("REQUIREMENT_UNKNOWN")
            if criterion_id not in criteria:
                raise RequirementError("REQUIREMENT_CRITERION_MISSING")
            if identity in result and result[identity] != row:
                raise RequirementError("REQUIREMENT_BINDING_CHANGED")
            if criterion_id in criterion_owners and criterion_owners[criterion_id] != identity:
                raise RequirementError("REQUIREMENT_CRITERION_REUSED")
            result[identity], criterion_owners[criterion_id] = row, identity
    return [result[key] for key in sorted(result)]


def validate_requirement_claims(manifest, tasks, claims, criterion_bindings, bindings):
    """Reject original-scope substitution before paying for a scoped investigation."""
    from .agent_completion import bind_criteria

    manifest = validate_manifest(manifest)
    bound = bind_requirements(manifest, tasks, bindings)
    proposed, _ = _criteria(manifest["run_id"], tasks)
    by_id = {row["requirement_id"]: row for row in manifest["requirements"]}
    by_criterion = {row["criterion_id"]: by_id[row["requirement_id"]] for row in bound}
    # bind_criteria must see only bindings for its own task. Existing one-Claim-
    # per-criterion validation remains its responsibility; this adds frozen scope.
    indices = [row.get("claim_index") for row in criterion_bindings]
    if len(set(indices)) != len(indices):
        raise RequirementError("REQUIREMENT_CHECK_CLAIM_REUSED")
    selected = set()
    for task in proposed:
        local_ids = {row["criterion_id"] for row in task["criteria"]}
        local = [row for row in criterion_bindings if row.get("criterion_id") in local_ids]
        selected.update(bind_criteria(task, claims, local))
    if len(selected) != len(criterion_bindings):
        raise RequirementError("REQUIREMENT_CHECK_BINDING_INVALID")
    criteria = {row["criterion_id"]: row for task in proposed for row in task["criteria"]}
    for identity in selected:
        if identity not in by_criterion:
            continue
        criterion = criteria[identity]
        requirement = by_criterion[identity]
        expected = criterion["expected_claim"]
        if expected["kind"] != requirement["kind"] or canonical(
            expected["applicability"]
        ) != canonical(requirement["applicability"]):
            raise RequirementError("REQUIREMENT_CLAIM_SCOPE_CHANGED")


def _proof(requirement, criterion, entry, run_id):
    from .agent_completion import normalized_claim
    from .agent_protocol import ClaimDraft

    expected = criterion.get("expected_claim")
    if not expected:
        return None, "No initial scoped Claim is bound to the requirement criterion"
    try:
        expected = normalized_claim(ClaimDraft.model_validate(expected).model_dump(mode="json"))
    except (ValidationError, KeyError, TypeError) as invalid:
        raise RequirementError("REQUIREMENT_EXPECTED_CLAIM_INVALID") from invalid
    if expected["kind"] != requirement["kind"] or canonical(expected["applicability"]) != canonical(
        requirement["applicability"]
    ):
        return None, "Bound Claim differs from the original requirement scope or kind"
    packet = entry.get("packet", {})
    if (
        criterion.get("status") != "resolved"
        or entry.get("attempt_status") != "accepted"
        or not criterion.get("last_call_id")
        or criterion["last_call_id"] != entry.get("latest_call_id")
        or packet.get("errorCode")
        or not packet.get("check_id")
    ):
        return None, "Current criterion is missing, stale, blocked or not bound to the latest check"
    matches = [
        row
        for row in packet.get("records", [])
        if row.get("record_type") == "Claim"
        and canonical(normalized_claim(row)) == canonical(expected)
    ]
    if len(matches) != 1:
        return None, "Current check must contain exactly one matching scoped Claim"
    claim = matches[0]
    identity, status = claim.get("claim_id"), claim.get("decision_status")
    decisions = [
        row
        for row in packet.get("records", [])
        if row.get("record_type") == "DecisionRecord" and row.get("claim_id") == identity
    ]
    if (
        not identity
        or claim.get("run_id") != run_id
        or claim.get("freshness") != "fresh"
        or len(decisions) != 1
        or status not in {"supported", "refuted"}
    ):
        return None, "Current Claim lacks a fresh resolved adjudication for this run"
    decision = decisions[0]
    adopted = decision.get("adopted_evidence_ids", [])
    relation = "supports" if status == "supported" else "refutes"
    linked = {
        link.get("evidence_id")
        for link in claim.get("evidence_links", [])
        if link.get("relation") == relation
    }
    if (
        decision.get("run_id") != run_id
        or not decision.get("decision_id")
        or decision.get("decision_status") != status
        or decision.get("gaps") != []
        or decision.get("unresolved_evidence_ids") != []
        or not isinstance(adopted, list)
        or not adopted
        or any(not isinstance(value, str) or not value for value in adopted)
        or len(set(adopted)) != len(adopted)
        or not set(adopted) <= linked
    ):
        return None, "Decision proof has unresolved gaps or lacks adopted matching Claim evidence"
    return {
        "claim_ids": [identity],
        "check_ids": [packet["check_id"]],
        "evidence_ids": sorted(adopted),
        "decision_ids": [decision["decision_id"]],
        "decision_status": status,
    }, None


def evaluate_coverage(manifest, tasks, investigations, bindings):
    """All original obligations must have current scoped Claim/DecisionRecord proof.

    This does not replace server verification of settled operations, evidence ownership,
    quote hashes/offsets or extraction semantics. Input is authoritative checked state.
    Task/packet labels alone never yield complete=True. Caller inputs are never mutated.
    """
    from .agent_completion import recompute_tasks

    if manifest is None or manifest == {}:
        return {
            "contract_version": CONTRACT_VERSION,
            "manifest_sha256": None,
            "complete": False,
            "requirements": [],
            "gaps": ["Original-question requirements have not been explicitly extracted"],
        }
    manifest = validate_manifest(manifest)
    bound = bind_requirements(manifest, tasks, bindings)
    proposed, criteria = _criteria(manifest["run_id"], tasks)
    try:
        recompute_tasks(proposed, investigations)
    except (KeyError, TypeError, ValueError) as invalid:
        raise RequirementError("REQUIREMENT_CHECK_STATE_INVALID") from invalid
    by_requirement = {row["requirement_id"]: row["criterion_id"] for row in bound}
    report, used_claims = [], set()
    for requirement in manifest["requirements"]:
        identity = requirement["requirement_id"]
        criterion_id = by_requirement.get(identity)
        row = {
            "requirement_id": identity,
            "text": requirement["text"],
            "criterion_id": criterion_id,
            "status": "uncovered",
            "claim_ids": [],
            "check_ids": [],
            "evidence_ids": [],
            "decision_ids": [],
            "gaps": [],
        }
        gap = "No distinct persisted criterion is bound to this original requirement"
        if criterion_id:
            task, criterion = criteria[criterion_id]
            entry = investigations.get(criterion.get("investigation_key"), {})
            proof, gap = _proof(requirement, criterion, entry, manifest["run_id"])
            if task.get("status") == "cancelled":
                proof, gap = None, "Original requirement criterion belongs to a cancelled task"
            if proof and used_claims.intersection(proof["claim_ids"]):
                proof, gap = None, "One Claim cannot establish multiple original obligations"
            if proof:
                used_claims.update(proof["claim_ids"])
                row.update(proof, status="resolved")
            else:
                row["status"] = "stale" if criterion.get("status") == "stale" else "blocked"
        row["gaps"] = [gap] if gap else []
        report.append(row)
    return {
        "contract_version": CONTRACT_VERSION,
        "manifest_sha256": manifest["manifest_sha256"],
        "complete": bool(report) and all(row["status"] == "resolved" for row in report),
        "requirements": report,
        "gaps": [f"{row['requirement_id']}: {gap}" for row in report for gap in row["gaps"]],
    }

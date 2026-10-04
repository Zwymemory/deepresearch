"""Versioned planning roles and server-owned Claim references; legacy wires stay unchanged."""

from __future__ import annotations

import copy
from typing import Literal

from pydantic import Field, StrictInt

from .agent_protocol import AgentDecision, Identifier
from .agent_question_segments import SegmentRequirementDraft, question_segments, segment_drafts
from .agent_requirements import RequirementDraft, RequirementError, canonical, validate_manifest
from .domain import StrictModel

PLANNER_VERSION = "agent-planning-obligations/3"
CONTINUATION_VERSION = "agent-frozen-requirements/2"
CLAIMS_VERSION = "agent-obligation-claims/1"


class PlanningConstraint(StrictModel):
    role: Literal["source", "output"]
    segment_ids: list[str] = Field(min_length=1, max_length=16)
    obligation_indices: list[StrictInt] = Field(min_length=1, max_length=32)


class ReferencedClaim(StrictModel):
    text: str = Field(min_length=1, max_length=4000)
    requirement_id: Identifier
    criterion_id: Identifier


class ObligationDecision(AgentDecision):
    planner_contract: Literal[PLANNER_VERSION]
    claims_contract: Literal[CLAIMS_VERSION]
    requirements: list[SegmentRequirementDraft] = Field(alias="obligations", max_length=32)
    constraints: list[PlanningConstraint] = Field(max_length=16)
    claims: list[ReferencedClaim] = Field(default_factory=list, max_length=4)
    # New references include the binding; a second conflicting binding is forbidden.
    criterion_bindings: list = Field(default_factory=list, max_length=0)
    requirement_bindings: list = Field(default_factory=list, max_length=0)


class ObligationContinuation(ObligationDecision):
    requirements: list[SegmentRequirementDraft] = Field(
        alias="obligations", default_factory=list, max_length=32
    )
    constraints: list[PlanningConstraint] = Field(default_factory=list, max_length=16)
    continuation_contract: Literal[CONTINUATION_VERSION]
    requirements_ref: str = Field(pattern=r"^[a-f0-9]{64}$")

    @classmethod
    def wire_schema(cls):
        schema = cls.model_json_schema()
        schema["properties"].pop("obligations")
        schema["properties"].pop("constraints")
        return schema


class CanonicalObligationDecision(AgentDecision):
    requirements: list[RequirementDraft] = Field(default_factory=list, max_length=32)
    claims: list[ReferencedClaim] = Field(default_factory=list, max_length=4)
    constraints: list[PlanningConstraint] = Field(default_factory=list, max_length=16)


def obligation_drafts(question, obligations, constraints):
    """Attach exact server question segments, without semantic classification guesses."""
    units = {s["segment_id"]: s for s in question_segments(question)["segments"]}
    drafts = copy.deepcopy(obligations)
    if not drafts or len(drafts) > 32:
        raise RequirementError("REQUIREMENTS_MISSING_OR_LIMIT")
    seen = set()
    for raw in constraints:
        try:
            c = PlanningConstraint.model_validate(raw).model_dump(mode="json")
        except (ValueError, TypeError):
            raise RequirementError("REQUIREMENT_CONSTRAINT_INVALID") from None
        refs, indices = c["segment_ids"], c["obligation_indices"]
        if (
            len(set(refs)) != len(refs)
            or any(r not in units for r in refs)
            or len(set(indices)) != len(indices)
            or any(type(i) is not int or not 0 <= i < len(drafts) for i in indices)
            or canonical(c) in seen
        ):
            raise RequirementError("REQUIREMENT_CONSTRAINT_INVALID")
        seen.add(canonical(c))
        # Never accept a model-supplied renamed constraint. Exact source/output anchors
        # are preserved in scope; CHECK3 determines whether their classification is sound.
        texts = [units[r]["text"] for r in sorted(refs, key=lambda r: units[r]["start"])]
        for i in indices:
            row = drafts[i]
            row["segment_ids"] = list(dict.fromkeys([*row["segment_ids"], *refs]))
            conditions = row["applicability"].get("conditions", [])
            row["applicability"]["conditions"] = list(dict.fromkeys([*conditions, *texts]))
    return segment_drafts(question, drafts)


def resolve_claims(state, task_id, proposals):
    """Explicit new-contract derivation. Never rewrites legacy submitted Claim scopes."""
    manifest = validate_manifest(
        state["original_requirements"], run_id=state["run_id"], question=state["question"]
    )
    requirements = {r["requirement_id"]: r for r in manifest["requirements"]}
    associations = {
        r["criterion_id"]: r["requirement_id"] for r in state.get("requirement_bindings", [])
    }
    task = next((t for t in state["tasks"] if t["task_id"] == task_id), None)
    allowed = {c["criterion_id"] for c in task.get("criteria", [])} if task else set()
    seen_r, seen_c, claims, bindings = set(), set(), [], []
    for index, raw in enumerate(proposals):
        value = ReferencedClaim.model_validate(raw).model_dump(mode="json")
        r, c = value["requirement_id"], value["criterion_id"]
        if (
            r not in requirements
            or c not in allowed
            or associations.get(c) != r
            or r in seen_r
            or c in seen_c
        ):
            raise RequirementError("REQUIREMENT_CLAIM_REFERENCE_INVALID")
        seen_r.add(r)
        seen_c.add(c)
        req = requirements[r]
        claims.append(
            {
                "text": value["text"],
                "kind": req["kind"],
                "applicability": copy.deepcopy(req["applicability"]),
            }
        )
        bindings.append({"criterion_id": c, "claim_index": index})
    return claims, bindings


def authoritative_context(question, manifest, receipt, native_bindings, task_id, references):
    """Pure export reconstruction matching the native Java context; no client context trust."""
    from .agent_question_segments import declaration_drafts, replay_declaration
    from .agent_requirements import _draft

    manifest = validate_manifest(manifest, question=question)
    drafts = declaration_drafts(question, receipt)
    wire = replay_declaration(receipt)
    units = {s["segment_id"]: s for s in question_segments(question)["segments"]}
    ids = {
        canonical({k: v for k, v in r.items() if k != "requirement_id"}): r["requirement_id"]
        for r in manifest["requirements"]
    }
    ordered_ids = [ids[canonical(_draft(d))] for d in drafts]
    constraints = []
    for c in wire["constraints"]:
        selected = sorted((units[r] for r in c["segment_ids"]), key=lambda u: u["start"])
        spans = []
        for u in selected:
            if spans and spans[-1]["end"] == u["start"]:
                spans[-1]["end"] = u["end"]
            else:
                spans.append({"start": u["start"], "end": u["end"]})
        constraints.append(
            {
                "role": c["role"],
                "question_spans": spans,
                "requirement_ids": [ordered_ids[i] for i in c["obligation_indices"]],
            }
        )
    selected, seen_r, seen_c = [], set(), set()
    for i, ref in enumerate(references):
        r, c = ref["requirement_id"], ref["criterion_id"]
        matches = [
            b
            for b in native_bindings
            if b["task_id"] == task_id and b["requirement_id"] == r and b["criterion_id"] == c
        ]
        if len(matches) != 1 or r in seen_r or c in seen_c:
            raise RequirementError("REQUIREMENT_CLAIM_REFERENCE_INVALID")
        seen_r.add(r)
        seen_c.add(c)
        selected.append({"requirement_id": r, "criterion_id": c, "claim_index": i})
    return {
        "contract_version": "agent-obligation-context/1",
        "question": question,
        "manifest_sha256": manifest["manifest_sha256"],
        "declaration_sha256": receipt["request_binding"]["wire_response_sha256"],
        "obligations": manifest["requirements"],
        "constraints": constraints,
        "claim_bindings": selected,
    }


def alignment_gap(request, response, claim_id, evidence_id):
    """Mirror deterministic native gates; semantic labels remain model proposals."""
    import re

    if response["planning_alignment"]["status"] != "complete":
        return "partition_unverified"
    proposal = next((c for c in response["claims"] if c["claim_id"] == claim_id), None)
    if proposal is None:
        return "verifier_claim_membership_invalid"
    if proposal["answer_alignment"] != "answers":
        return "not_answering_original_obligation"
    relation = next((r for r in proposal["relations"] if r["evidence_id"] == evidence_id), None)
    if relation is None:
        return "verifier_evidence_membership_invalid"
    if relation["source_alignment"] != "qualifies":
        return "required_source_unverified"
    index = next((i for i, c in enumerate(request["claims"]) if c["claim_id"] == claim_id), None)
    if index is None:
        return "original_claim_membership_invalid"
    context = request["original_context"]
    requirement = next(
        (b["requirement_id"] for b in context["claim_bindings"] if b["claim_index"] == index),
        None,
    )
    source = next((e for e in request["evidence"] if e["evidence_id"] == evidence_id), None)
    if requirement is None or source is None:
        return "original_obligation_or_source_membership_invalid"
    uri = source["source"]["locator"].get("uri")
    for c in context["constraints"]:
        if c["role"] != "source" or requirement not in c["requirement_ids"]:
            continue
        urls = set()
        for span in c["question_spans"]:
            text = context["question"][span["start"] : span["end"]]
            pattern = r"""https?://[^\s<>"'()\[\]，。；！？]+"""  # noqa: RUF001 - exact delimiters
            urls.update(u.rstrip(".,;!?") for u in re.findall(pattern, text, re.I))
        if urls and uri not in urls:
            return "actual_final_read_url_mismatch"
    return None


def validate_check3_attestation(request, result, receipt, *, context, request_hash, response_hash):
    from .evidence_check import parse_verifier_response, sha

    if request.get("protocol_version") != "evidence-check/3" or canonical(
        request.get("original_context")
    ) != canonical(context):
        raise ValueError("NATIVE_CHECK_ORIGINAL_CONTEXT_MISMATCH")
    verification = result.get("verification")
    if (
        not isinstance(verification, dict)
        or set(verification)
        != {"protocol_version", "model_call_id", "request_sha256", "response_sha256", "response"}
        or verification["protocol_version"] != "evidence-check/3"
        or verification["request_sha256"] != request_hash
        or verification["response_sha256"] != response_hash
        or canonical(verification["response"]) != canonical(receipt["value"])
    ):
        raise ValueError("NATIVE_CHECK_ALIGNMENT_ATTESTATION_INVALID")
    if sha(canonical(verification["response"])) != response_hash:
        raise ValueError("NATIVE_CHECK_ALIGNMENT_ATTESTATION_INVALID")
    parse_verifier_response(canonical(receipt["value"]), request, request_hash)
    requested_claims = {c["claim_id"] for c in request["claims"]}
    requested_sources = {e["evidence_id"] for e in request["evidence"]}
    for decision in result["records"]:
        if decision["record_type"] == "DecisionRecord":
            claim_id = decision.get("claim_id")
            adopted = decision.get("adopted_evidence_ids")
            if not isinstance(claim_id, str) or claim_id not in requested_claims:
                raise ValueError("NATIVE_CHECK_DECISION_CLAIM_BINDING_INVALID")
            if (
                not isinstance(adopted, list)
                or any(not isinstance(e, str) or e not in requested_sources for e in adopted)
                or len(set(adopted)) != len(adopted)
            ):
                raise ValueError("NATIVE_CHECK_ADOPTED_SOURCE_MEMBERSHIP_INVALID")
        if decision["record_type"] == "DecisionRecord" and decision["decision_status"] in {
            "supported",
            "refuted",
        }:
            if not decision["adopted_evidence_ids"]:
                raise ValueError("NATIVE_CHECK_ADOPTED_SOURCE_ALIGNMENT_INVALID")
            if any(
                alignment_gap(request, receipt["value"], prior["claim_id"], prior["evidence_id"])
                for prior in request.get("prior_relations", [])
                if prior["claim_id"] == decision["claim_id"]
            ):
                raise ValueError("NATIVE_CHECK_PRIOR_SOURCE_ALIGNMENT_UNRESOLVED")
            if any(
                alignment_gap(request, receipt["value"], decision["claim_id"], e)
                for e in decision["adopted_evidence_ids"]
            ):
                raise ValueError("NATIVE_CHECK_ADOPTED_SOURCE_ALIGNMENT_INVALID")
    return verification["response"]["planning_alignment"]["status"] == "complete"

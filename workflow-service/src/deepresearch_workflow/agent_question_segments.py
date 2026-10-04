"""Server-owned coordinates for new planners; canonical requirements remain v1.

Segments are bounded reference units, not extracted semantic obligations. No text is
normalized or discarded. Legacy initialized checkpoints retain their original wire.
"""

from __future__ import annotations

import hashlib
import json
import math
from typing import Literal

from pydantic import Field

from .agent_requirements import RequirementError, RequirementScope, canonical
from .domain import StrictModel

PLANNER_VERSION = "agent-planning-segments/2"
LEGACY_PLANNER_VERSION = "agent-planning-spans/1"
MAPPING_VERSION = "agent-question-segments/1"
SETTLEMENT_VERSION = "agent-planner-settlement/1"
MAX_SEGMENTS = 16  # At most the existing per-requirement span limit, even without merging.
MAX_QUESTION_UTF16 = 4000
BREAKS = frozenset("。！？；，、：;\n\r")  # noqa: RUF001 - exact Chinese delimiters


class SegmentRequirementDraft(StrictModel):
    text: str = Field(min_length=1, max_length=400, pattern=r"\S")
    segment_ids: list[str] = Field(min_length=1, max_length=MAX_SEGMENTS)
    kind: Literal["factual", "inference", "recommendation"]
    applicability: RequirementScope


def question_segments(question):
    if not isinstance(question, str) or not question.strip():
        raise RequirementError("REQUIREMENT_QUESTION_INVALID")
    try:
        raw = question.encode("utf-8", errors="strict")
        units = len(question.encode("utf-16-le", errors="strict")) // 2
    except UnicodeError:
        raise RequirementError("REQUIREMENT_QUESTION_INVALID") from None
    if units > MAX_QUESTION_UTF16:
        raise RequirementError("REQUIREMENT_QUESTION_LIMIT")
    boundaries, start = [], 0
    for index, char in enumerate(question):
        following = question[index + 1 : index + 2]
        boundary = char in BREAKS or (char in ".?!," and (not following or following.isspace()))
        if not boundary:
            continue
        end = index + 1
        # Consecutive delimiters/whitespace belong to a neighbouring reference unit.
        if all(c.isspace() or c in BREAKS or c in ".?!," for c in question[start:end]):
            if boundaries:
                boundaries[-1][1] = end
                start = end
            continue
        boundaries.append([start, end])
        start = end
    if start < len(question):
        if boundaries and not question[start:].strip():
            boundaries[-1][1] = len(question)
        else:
            boundaries.append([start, len(question)])
    if not boundaries:  # E.g. a question consisting solely of punctuation.
        boundaries = [[0, len(question)]]
    width = math.ceil(len(boundaries) / MAX_SEGMENTS)
    ranges = [[boundaries[i][0], boundaries[min(i + width, len(boundaries)) - 1][1]]
              for i in range(0, len(boundaries), width)]
    question_hash = hashlib.sha256(raw).hexdigest()
    core = {
        "mapping_version": MAPPING_VERSION,
        "question_sha256": question_hash,
        "question_length": len(question),
        "segments": [
            {"segment_id": f"qs1-{question_hash}-{ordinal:02d}", "start": start, "end": end,
             "text": question[start:end]}
            for ordinal, (start, end) in enumerate(ranges)
        ],
    }
    return {**core, "mapping_sha256": hashlib.sha256(canonical(core).encode()).hexdigest()}


def planner_binding(mapping):
    return {"planner_contract": PLANNER_VERSION, "question_mapping_version": MAPPING_VERSION,
            "planner_settlement_contract": SETTLEMENT_VERSION,
            "question_sha256": mapping["question_sha256"],
            "question_mapping_sha256": mapping["mapping_sha256"]}


def segment_drafts(question, drafts):
    """Resolve only supplied references. Never infer a missing selection."""
    mapping = question_segments(question)
    if not isinstance(drafts, list) or not 1 <= len(drafts) <= 32:
        raise RequirementError("REQUIREMENTS_MISSING_OR_LIMIT")
    by_id = {s["segment_id"]: s for s in mapping["segments"]}
    result, selected = [], set()
    for raw in drafts:
        try:
            declaration = SegmentRequirementDraft.model_validate(raw).model_dump(mode="json")
        except (ValueError, TypeError):
            raise RequirementError("REQUIREMENT_SEGMENT_INVALID") from None
        references = declaration.pop("segment_ids")
        if len(set(references)) != len(references):
            raise RequirementError("REQUIREMENT_SEGMENT_DUPLICATE")
        if any(reference not in by_id for reference in references):
            raise RequirementError("REQUIREMENT_SEGMENT_UNKNOWN")
        selected.update(references)
        spans = []
        for reference in sorted(references, key=lambda r: by_id[r]["start"]):
            unit = by_id[reference]
            if spans and spans[-1]["end"] == unit["start"]:
                spans[-1]["end"] = unit["end"]
            else:
                spans.append({"start": unit["start"], "end": unit["end"]})
        result.append({**declaration, "question_spans": spans})
    missing = [s for s in mapping["segments"]
               if s["segment_id"] not in selected and s["text"].strip()]
    if missing:
        diagnostic = {
            "mapping_version": MAPPING_VERSION, "mapping_sha256": mapping["mapping_sha256"],
            "missing_count": len(missing),
            "missing_ranges": [{"start": s["start"], "end": s["end"]} for s in missing],
        }
        raise RequirementError("REQUIREMENT_QUESTION_REGION_UNASSIGNED", diagnostic)
    return result


def declaration_drafts(question, receipt):
    """Reconstruct canonical declarations from the actual settled wire + provenance.

    v1 saved receipt bytes/identity remain readable. Unknown versions do not downgrade.
    """
    stored, binding = receipt["value"], receipt.get("request_binding", {})
    bound_version = binding.get("planner_contract")
    if "planner_contract" not in stored and "planner_contract" not in binding:
        if any(k in binding for k in (
            "planner_settlement_contract", "planner_declaration", "wire_response_sha256",
            "question_mapping_version", "question_mapping_sha256",
        )):
            raise RequirementError("REQUIREMENT_PLANNER_VERSION_INVALID")
        return stored["requirements"]
    mapping = question_segments(question)
    if bound_version == "agent-planning-obligations/3":
        from .agent_obligations import (
            CLAIMS_VERSION,
            CONTINUATION_VERSION,
            ObligationDecision,
            obligation_drafts,
        )

        expected = {**planner_binding(mapping), "planner_contract": bound_version}
        if (stored.get("planner_contract") != bound_version
                or any(binding.get(k) != v for k, v in expected.items())
                or binding.get("claims_contract") != CLAIMS_VERSION
                or binding.get("continuation_contract") != CONTINUATION_VERSION
                or binding.get("planning_phase") != "initial"
                or binding.get("response_sha256")
                != hashlib.sha256(canonical(stored).encode()).hexdigest()):
            raise RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID")
        wire = replay_declaration(receipt)
        if wire.get("planner_contract") != bound_version:
            raise RequirementError("REQUIREMENT_PLANNER_VERSION_INVALID")
        try:
            ObligationDecision.model_validate(wire)
        except (ValueError, TypeError):
            raise RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID") from None
        drafts = obligation_drafts(question, wire.get("obligations"), wire.get("constraints", []))
        if canonical(drafts) != canonical(stored.get("requirements")):
            raise RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID")
        return drafts
    if bound_version != PLANNER_VERSION:
        raise RequirementError("REQUIREMENT_PLANNER_VERSION_INVALID")
    if stored.get("planner_contract") != PLANNER_VERSION:
        raise RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID")
    if any(binding.get(k) != v for k, v in planner_binding(mapping).items()):
        raise RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID")
    if binding.get("response_sha256") != hashlib.sha256(canonical(stored).encode()).hexdigest():
        raise RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID")
    value = replay_declaration(receipt)
    if value.get("planner_contract") != PLANNER_VERSION:
        raise RequirementError("REQUIREMENT_PLANNER_VERSION_INVALID")
    drafts = segment_drafts(question, value["requirements"])
    if canonical(drafts) != canonical(stored.get("requirements")):
        raise RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID")
    return drafts


def replay_declaration(receipt):
    """Read only a bounded schema-validated function object, never provider envelope."""
    binding = receipt.get("request_binding", {})
    raw = binding.get("planner_declaration")
    if (binding.get("planner_settlement_contract") != SETTLEMENT_VERSION
            or type(raw) is not str or len(raw.encode("utf-8")) > 65536):
        raise RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID")
    try:
        value = json.loads(raw)
        if (type(value) is not dict or canonical(value) != raw
                or binding.get("wire_response_sha256")
                != hashlib.sha256(raw.encode()).hexdigest()):
            raise ValueError("binding")
    except (ValueError, TypeError):
        raise RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID") from None
    return value

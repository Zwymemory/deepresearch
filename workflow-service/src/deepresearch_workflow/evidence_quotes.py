"""Lossless model-only paragraph references; native checks still receive exact quotes.

The model chooses the relation and paragraph range. Trusted code only resolves that
range against the hash-bound original; it never manufactures support or drops sources.
"""

from __future__ import annotations

import copy
import hashlib
import json

from .evidence_check import (
    EvidenceCheckError,
    canonical,
    checked_request,
    parse_verifier_response,
    response_schema,
)

ENCODING = "evidence-paragraph-refs/1"
KEYED_ENCODING = "evidence-paragraph-map/1"
SUPPORTED_ENCODINGS = frozenset({ENCODING, KEYED_ENCODING})


def encoding_for(request):
    return (
        KEYED_ENCODING
        if request.get("original_context", {}).get("contract_version")
        == "agent-obligation-context/2"
        else ENCODING
    )


def paragraphs(text):
    # Include empty lines: joining every row reconstructs the snapshot exactly.
    rows, start = [], 0
    for index, line in enumerate(text.split("\n")):
        rows.append({"id": index, "text": line, "start": start, "end": start + len(line)})
        start += len(line) + 1
    return rows


def model_payload(request, request_hash):
    checked_request(request, request_hash)
    payload = copy.deepcopy(request)
    for source in payload["evidence"]:
        snapshot = source["snapshot"]
        snapshot["paragraphs"] = [
            {"id": row["id"], "text": row["text"]} for row in paragraphs(snapshot.pop("text"))
        ]
    payload["quote_encoding"] = encoding_for(request)
    return payload


def model_schema(request):
    schema = response_schema(request)
    relation = schema["properties"]["claims"]["items"]["properties"]["relations"]["items"]
    relation["properties"]["quote"] = {
        "type": "object",
        "properties": {
            "start_paragraph": {"type": "integer", "minimum": 0},
            "end_paragraph": {"type": "integer", "minimum": 0},
        },
        "required": ["start_paragraph", "end_paragraph"],
        "additionalProperties": False,
    }
    if encoding_for(request) == KEYED_ENCODING:
        # Exactly one slot per current claim/source pair. Multiple supporting
        # paragraphs share a single range; they must not become duplicate sources.
        relation["properties"].pop("evidence_id")
        relation["required"].remove("evidence_id")
        claim = schema["properties"]["claims"]["items"]
        claim["properties"].pop("claim_id")
        claim["required"].remove("claim_id")
        claim["properties"]["relations"] = keyed_object(
            {row["evidence_id"]: copy.deepcopy(relation) for row in request["evidence"]}
        )
        schema["properties"]["claims"] = keyed_object(
            {row["claim_id"]: copy.deepcopy(claim) for row in request["claims"]}
        )
    return schema


def keyed_object(properties):
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


def instruction(original, request=None):
    start = original.index("Quote the COMPLETE original paragraph(s),")
    end = original.index("If language or scope cannot be established", start)
    original = (
        original[:start]
        + (
            "Choose COMPLETE original paragraphs, including qualifications, exceptions and "
            "version context. quote is an object {start_paragraph: N, end_paragraph: M}, with "
            "inclusive IDs from that evidence's snapshot.paragraphs. Never output source text "
            "or compute character offsets/hashes. The server reconstructs the exact original "
            "range and validates it independently. Select a complete relevant paragraph even "
            "for an insufficient relation. Preserve all relevant adjacent qualifications. "
        )
        + original[end:]
    )
    result = original + (
        "\nParagraph reference encoding evidence-paragraph-refs/1 is lossless: join all "
        "snapshot.paragraphs[].text with newline to recover the original. IDs are local "
        "to each evidence, never authority. Return EVERY claim and EVERY supplied source "
        "relation; do not omit counterevidence. Keep each public reason under 160 characters "
        "and limitations brief. Teaching analogies/practice questions explicitly labeled "
        "as illustrations are output constraints, not claims that an original contains "
        "those exact examples. Assess their factual basis and do not imply unexecuted "
        "code was tested. A source restriction conflict is still a real gap."
    )
    if request is not None and encoding_for(request) == KEYED_ENCODING:
        result = result.replace(ENCODING, KEYED_ENCODING)
        result += (
            "\nOUTPUT SHAPE evidence-paragraph-map/1: claims is an OBJECT keyed by the "
            "exact current claim IDs, not an array. Each claim value has relations as an "
            "OBJECT keyed by exact evidence IDs, not an array. ID keys occur once; do not "
            "add claim_id/evidence_id fields inside their values. The supplied JSON Schema "
            "enumerates every required key. Put ONE overall relation and ONE complete "
            "paragraph range in each source slot, encompassing all applicable supporting "
            "or contrary paragraphs and their qualifications. Never split a source slot "
            "into separate entries per sentence or subquestion. Missing support remains "
            "insufficient. Preserve limitations, answer_alignment, follow_up_actions and "
            "planning_alignment exactly as the schema requires."
        )
    return result


def expand(value, request, request_hash):
    checked_request(request, request_hash)
    result = copy.deepcopy(value)
    sources = {source["evidence_id"]: source for source in request["evidence"]}
    try:
        if encoding_for(request) == KEYED_ENCODING:
            from jsonschema import Draft202012Validator

            if not Draft202012Validator(model_schema(request)).is_valid(result):
                raise ValueError("keyed shape")
            result["claims"] = [
                {"claim_id": claim["claim_id"], **result["claims"][claim["claim_id"]]}
                for claim in request["claims"]
            ]
            for claim in result["claims"]:
                claim["relations"] = [
                    {"evidence_id": source, **claim["relations"][source]} for source in sources
                ]
        for claim in result["claims"]:
            for relation in claim["relations"]:
                source = sources[relation["evidence_id"]]
                quote = relation["quote"]
                if set(quote) != {"start_paragraph", "end_paragraph"}:
                    raise ValueError("shape")
                first, last = quote["start_paragraph"], quote["end_paragraph"]
                text = source["snapshot"]["text"]
                rows = paragraphs(text)
                if (
                    type(first) is not int
                    or type(last) is not int
                    or not 0 <= first <= last < len(rows)
                ):
                    raise ValueError("range")
                start, end = rows[first]["start"], rows[last]["end"]
                quoted = text[start:end]
                relation["quote"] = {
                    "start": start,
                    "end": end,
                    "text": quoted,
                    "sha256": hashlib.sha256(quoted.encode()).hexdigest(),
                }
    except (KeyError, TypeError, ValueError, IndexError):
        raise EvidenceCheckError("CHECK_QUOTE_BINDING_INVALID") from None
    # Existing independent validator still checks all IDs, relations, scope alignment,
    # complete paragraphs, Unicode offsets, content hashes, and overall size limits.
    parse_verifier_response(canonical(result), request, request_hash)
    return result


def replay_wire(result):
    binding = result.request_binding
    raw = binding.get("encoded_response")
    if (
        binding.get("evidence_quote_encoding") not in SUPPORTED_ENCODINGS
        or type(raw) is not str
        or len(raw.encode()) > 65536
    ):
        raise EvidenceCheckError("CHECK_RESPONSE_INVALID")
    try:
        value = json.loads(raw)
        if (
            type(value) is not dict
            or canonical(value) != raw
            or hashlib.sha256(raw.encode()).hexdigest() != binding.get("wire_response_sha256")
        ):
            raise ValueError("hash")
    except (ValueError, TypeError):
        raise EvidenceCheckError("CHECK_RESPONSE_INVALID") from None
    return value

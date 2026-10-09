"""Controlled verifier responses in the actual, versioned provider wire format.

These probes cite the complete synthetic original. Native expansion, hashing,
source alignment and publication validation remain enabled in the integration test.
"""

import copy


def original_text(evidence):
    snapshot = evidence["snapshot"]
    if "paragraphs" in snapshot:
        return "\n".join(row["text"] for row in snapshot["paragraphs"])
    return snapshot["text"]


def original_quote(evidence):
    snapshot = evidence["snapshot"]
    if "paragraphs" in snapshot:
        rows = snapshot["paragraphs"]
        assert rows and [row["id"] for row in rows] == list(range(len(rows)))
        return {"start_paragraph": 0, "end_paragraph": rows[-1]["id"]}
    return snapshot["text"]


def check_wire(value, payload):
    if payload.get("quote_encoding") != "evidence-paragraph-map/1":
        return value
    value = copy.deepcopy(value)
    claims = {}
    for claim in value["claims"]:
        relations = {}
        for relation in claim["relations"]:
            relations[relation.pop("evidence_id")] = relation
        claim["relations"] = relations
        claims[claim.pop("claim_id")] = claim
    value["claims"] = claims
    return value

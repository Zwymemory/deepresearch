"""Repair protocol regressions: original text and attested response bytes stay intact."""

import copy

import pytest

from deepresearch_workflow.evidence_check import (
    EvidenceCheckError,
    build_verifier_messages,
    canonical,
    parse_verifier_response,
    sha,
)
from tests.test_evidence_check import packet, parse


@pytest.mark.parametrize("space", ["\u00a0", "\u0085", "\u2007", "\u202f"])
def test_unicode_boundary_rules_preserve_response_and_still_require_qualifications(space):
    request, response = packet("标题 🧪\n\n" + space + "Limit: 10 only in legacy mode." + space)
    relation = response["claims"][0]["relations"][0]
    relation["quote"] = "Limit: 10 only in legacy mode."
    raw = canonical(response)
    parsed = parse_verifier_response(raw, request, sha(canonical(request)))
    assert parsed.response == response
    assert parsed.response_sha256 == sha(raw)
    relation["quote"] = "Limit: 10"
    with pytest.raises(EvidenceCheckError, match="CONTEXT_INCOMPLETE"):
        parse(request, response)


@pytest.mark.parametrize("space", ["\u001c", "\u001f"])
def test_python_extra_control_whitespace_is_rejected_consistently(space):
    request, response = packet(space + "Limit: 10" + space)
    response["claims"][0]["relations"][0]["quote"] = "Limit: 10"
    with pytest.raises(EvidenceCheckError, match="CONTEXT_INCOMPLETE"):
        parse(request, response)


def version_two():
    request, response = packet()
    request.update(
        protocol_version="evidence-check/2",
        investigation_id="a" * 64,
        dispute_round=1,
        parent_check_id="check-parent",
        prior_relations=[
            {
                "claim_id": "claim-one",
                "evidence_id": "evidence-one",
                "relation": "refutes",
                "quote": copy.deepcopy(response["claims"][0]["relations"][0]["quote"]),
                "decision_id": "decision-parent",
                "assessment_ref": "assessment-parent",
            }
        ],
    )
    return request, response


def test_version_two_prior_relations_remain_server_data_and_response_receipt_stays_raw():
    request, response = version_two()
    messages = build_verifier_messages(request, sha(canonical(request)))
    assert "decision-parent" in messages[1]["content"]
    assert "decision-parent" not in messages[0]["content"]
    assert parse(request, response).response_sha256 == sha(canonical(response))


@pytest.mark.parametrize(
    "mutation", ["missing", "unknown_evidence", "duplicate", "hash", "verified"]
)
def test_corrupt_or_self_verified_prior_bindings_are_rejected_before_model(mutation):
    request, response = version_two()
    if mutation == "missing":
        del request["prior_relations"]
    elif mutation == "unknown_evidence":
        request["prior_relations"][0]["evidence_id"] = "foreign"
    elif mutation == "duplicate":
        request["prior_relations"] *= 2
    elif mutation == "hash":
        request["prior_relations"][0]["quote"]["sha256"] = "0" * 64
    else:
        request["prior_relations"][0]["verified"] = True
    with pytest.raises(EvidenceCheckError):
        parse(request, response)

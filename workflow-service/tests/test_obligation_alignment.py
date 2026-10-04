"""Controlled actual-adapter/reference/gate regressions; no paid or source calls."""

import copy

import pytest

from deepresearch_workflow.agent_obligations import (
    CLAIMS_VERSION,
    CONTINUATION_VERSION,
    PLANNER_VERSION,
    alignment_gap,
    authoritative_context,
    obligation_drafts,
    resolve_claims,
    validate_check3_attestation,
)
from deepresearch_workflow.agent_protocol import ModelResult
from deepresearch_workflow.agent_question_segments import question_segments
from deepresearch_workflow.agent_requirements import RequirementError, canonical
from deepresearch_workflow.evidence_check import (
    EvidenceCheckError,
    parse_verifier_response,
    response_schema,
    sha,
)
from deepresearch_workflow.graph import ModelCallError

from .test_agent_first_planning import QUESTION, PlanningLedger, graph, state


def initial_wire(question=QUESTION):
    units = question_segments(question)["segments"]
    assert len(units) == 5
    unknown = {"status": "unknown", "value": None, "reason": "Not independently established"}
    return {
        "planner_contract": PLANNER_VERSION,
        "claims_contract": CLAIMS_VERSION,
        "action": "finish",
        "reason": "Controlled first declaration without evidence",
        "obligations": [
            {
                "text": units[i]["text"],
                "segment_ids": [units[i]["segment_id"]],
                "kind": "factual",
                "applicability": {
                    "subject": units[i]["text"],
                    "version": unknown,
                    "valid_at": unknown,
                    "conditions": [],
                },
            }
            for i in (1, 2)
        ],
        "constraints": [
            {
                "role": "source",
                "segment_ids": [units[0]["segment_id"]],
                "obligation_indices": [0, 1],
            },
            {
                "role": "output",
                "segment_ids": [u["segment_id"] for u in units[3:]],
                "obligation_indices": [0, 1],
            },
        ],
    }


class SequenceModel:
    def __init__(self, first):
        self.first, self.following, self.calls = first, None, []

    async def invoke(self, request):
        self.calls.append(request)
        return ModelResult(
            value=copy.deepcopy(self.first if len(self.calls) == 1 else self.following),
            input_tokens=40,
            output_tokens=20,
        )


async def frozen_context():
    ledger = PlanningLedger()
    model = SequenceModel(initial_wire())
    runtime = graph(model, ledger, legacy_fixture=False)
    initial = {
        **state(),
        "planner_contract": PLANNER_VERSION,
        "continuation_contract": CONTINUATION_VERSION,
        "action_sequence": 0,
    }
    initialized = {**initial, **await runtime.decide(initial)}
    frozen = {**initialized, **await runtime.act(initialized)}
    return runtime, ledger, model, frozen


async def test_structured_actual_question_roles_freeze_two_obligations_and_settled_replay_once():
    runtime, ledger, model, frozen = await frozen_context()
    manifest = frozen["original_requirements"]
    assert len(manifest["requirements"]) == len(frozen["tasks"][0]["criteria"]) == 2
    for requirement in manifest["requirements"]:
        assert "请仅依据 IANA" in "".join(requirement["applicability"]["conditions"])
        assert "请引用网页原文" in "".join(requirement["applicability"]["conditions"])
    original = {
        **state(),
        "planner_contract": PLANNER_VERSION,
        "continuation_contract": CONTINUATION_VERSION,
        "action_sequence": 0,
    }
    replay = await runtime.decide(original)
    assert replay["decision"] == initial_wire()
    assert len(model.calls) == len(ledger.settlements) == 1
    receipt = ledger.rows["model:agent:decision-1"]["result"]
    context = authoritative_context(
        QUESTION,
        manifest,
        receipt,
        [{**b, "task_id": frozen["tasks"][0]["task_id"]} for b in frozen["requirement_bindings"]],
        frozen["tasks"][0]["task_id"],
        frozen["requirement_bindings"],
    )
    assert context["question"] == QUESTION and len(context["obligations"]) == 2
    assert {c["role"] for c in context["constraints"]} == {"source", "output"}
    assert model.calls[0].max_output_tokens == 1024
    assert receipt["request_binding"]["wire_response_sha256"] == sha(canonical(initial_wire()))


@pytest.mark.parametrize(
    "mutation", ["foreign_requirement", "foreign_criterion", "cross_binding", "duplicate"]
)
async def test_invalid_references_are_settled_once_then_actionable_before_check(mutation):
    runtime, ledger, model, frozen = await frozen_context()
    refs = copy.deepcopy(frozen["requirement_bindings"])
    proposal = {**refs[0], "text": "Controlled proposed factual answer"}
    if mutation == "foreign_requirement":
        proposal["requirement_id"] = "requirement-foreign"
    elif mutation == "foreign_criterion":
        proposal["criterion_id"] = "criterion-foreign"
    elif mutation == "cross_binding":
        proposal["criterion_id"] = refs[1]["criterion_id"]
    proposals = [proposal, copy.deepcopy(proposal)] if mutation == "duplicate" else [proposal]
    model.following = {
        "planner_contract": PLANNER_VERSION,
        "claims_contract": CLAIMS_VERSION,
        "continuation_contract": CONTINUATION_VERSION,
        "requirements_ref": frozen["original_requirements"]["manifest_sha256"],
        "action": "check_claims",
        "task_id": frozen["tasks"][0]["task_id"],
        "claims": proposals,
        "reason": "Controlled invalid binding",
    }
    decided = {**frozen, **await runtime.decide(frozen)}
    result = await runtime.act(decided)
    assert result["observations"][-1]["errorCode"] == "REQUIREMENT_CLAIM_REFERENCE_INVALID"
    assert result["observations"][-1]["allowed_references"]
    assert result["no_progress"] == frozen["no_progress"] + 1
    assert len(model.calls) == len(ledger.settlements) == 2
    assert not any(key.startswith("tool-") for key in ledger.rows)
    replay = await runtime.decide(frozen)
    assert replay["decision"] == model.following and len(model.calls) == 2


async def test_valid_referenced_claim_derives_scope_and_explicit_override_fails_schema():
    runtime, ledger, model, frozen = await frozen_context()
    proposal = {
        **frozen["requirement_bindings"][0],
        "text": "An answer proposed for independent checking",
    }
    claims, bindings = resolve_claims(frozen, frozen["tasks"][0]["task_id"], [proposal])
    requirement = next(
        r
        for r in frozen["original_requirements"]["requirements"]
        if r["requirement_id"] == proposal["requirement_id"]
    )
    assert claims[0] == {
        "text": proposal["text"],
        "kind": requirement["kind"],
        "applicability": requirement["applicability"],
    }
    assert bindings == [{"criterion_id": proposal["criterion_id"], "claim_index": 0}]
    model.following = {
        "planner_contract": PLANNER_VERSION,
        "claims_contract": CLAIMS_VERSION,
        "continuation_contract": CONTINUATION_VERSION,
        "requirements_ref": frozen["original_requirements"]["manifest_sha256"],
        "action": "check_claims",
        "task_id": frozen["tasks"][0]["task_id"],
        "claims": [{**proposal, "kind": "recommendation"}],
        "reason": "Controlled forbidden override",
    }
    with pytest.raises(ModelCallError):
        await runtime.decide(frozen)
    assert ledger.rows["model:agent:decision-2"]["status"] == "UNKNOWN"
    assert len(model.calls) == 2


@pytest.mark.parametrize("bad", ["foreign", "duplicate", "unattached", "bad_role"])
def test_constraints_preserve_exact_anchors_or_fail_closed(bad):
    wire = initial_wire()
    if bad == "foreign":
        wire["constraints"][0]["segment_ids"] = ["foreign"]
    elif bad == "duplicate":
        wire["constraints"].append(copy.deepcopy(wire["constraints"][0]))
    elif bad == "unattached":
        wire["constraints"][0]["obligation_indices"] = [2]
    else:
        wire["constraints"][0]["role"] = "research"
    with pytest.raises(RequirementError):
        obligation_drafts(QUESTION, wire["obligations"], wire["constraints"])


def check_fixture():
    original = "Preview releases must not be used in customer production deployments."
    request = {
        "protocol_version": "evidence-check/3",
        "check_id": "check-controlled",
        "claims": [{"claim_id": "claim-controlled", "text": original, "kind": "factual"}],
        "evidence": [
            {
                "evidence_id": "evidence-controlled",
                "source": {"locator": {"uri": "https://docs.example.test/policy"}},
                "snapshot": {"text": original, "sha256": sha(original)},
            }
        ],
        "dispute_round": 0,
        "parent_check_id": None,
        "investigation_id": "investigation",
        "prior_relations": [],
        "original_context": {
            "contract_version": "agent-obligation-context/1",
            "question": "Use https://docs.example.test/policy",
            "manifest_sha256": "a" * 64,
            "declaration_sha256": "b" * 64,
            "obligations": [{"requirement_id": "requirement-controlled"}],
            "constraints": [
                {
                    "role": "source",
                    "question_spans": [{"start": 0, "end": 36}],
                    "requirement_ids": ["requirement-controlled"],
                }
            ],
            "claim_bindings": [
                {
                    "requirement_id": "requirement-controlled",
                    "criterion_id": "criterion-controlled",
                    "claim_index": 0,
                }
            ],
        },
    }
    request["original_context"]["constraints"][0]["question_spans"][0]["end"] = len(
        request["original_context"]["question"]
    )
    response = {
        "planning_alignment": {
            "status": "complete",
            "reason": "Controlled full question assessment",
        },
        "claims": [
            {
                "claim_id": "claim-controlled",
                "answer_alignment": "answers",
                "limitations": [],
                "relations": [
                    {
                        "evidence_id": "evidence-controlled",
                        "relation": "supports",
                        "quote": original,
                        "reason": "Complete policy paragraph",
                        "source_alignment": "qualifies",
                    }
                ],
            }
        ],
        "follow_up_actions": [],
    }
    return request, response


@pytest.mark.parametrize(
    "reason",
    [
        None,
        "partition",
        "irrelevant",
        "absence_only",
        "instruction_only",
        "wrong_source",
        "redirect",
    ],
)
def test_check3_semantic_and_final_identity_gates_preserve_truthful_negative_cases(reason):
    request, response = check_fixture()
    if reason == "partition":
        response["planning_alignment"]["status"] = "incomplete"
    elif reason in {"irrelevant", "absence_only", "instruction_only"}:
        response["claims"][0]["answer_alignment"] = reason
    elif reason == "wrong_source":
        response["claims"][0]["relations"][0]["source_alignment"] = "wrong_source"
    elif reason == "redirect":
        request["evidence"][0]["source"]["locator"]["uri"] = "https://docs.example.test/news"
    parsed = parse_verifier_response(canonical(response), request, sha(canonical(request)))
    assert parsed.semantic_truth_guaranteed is False
    assert bool(
        alignment_gap(request, response, "claim-controlled", "evidence-controlled")
    ) == bool(reason)
    assert "planning_alignment" in response_schema(request)["required"]


@pytest.mark.parametrize("missing", ["planning_alignment", "answer_alignment", "source_alignment"])
def test_check3_missing_alignment_is_invalid_not_a_truth_certificate(missing):
    request, response = check_fixture()
    if missing == "planning_alignment":
        response.pop(missing)
    elif missing == "answer_alignment":
        response["claims"][0].pop(missing)
    else:
        response["claims"][0]["relations"][0].pop(missing)
    with pytest.raises(EvidenceCheckError):
        parse_verifier_response(canonical(response), request, sha(canonical(request)))


def test_capture_attestation_rejects_missing_or_downgraded_proof_and_false_adoption():
    request, response = check_fixture()
    receipt = {"value": response}
    result = {
        "records": [
            {
                "record_type": "DecisionRecord",
                "decision_status": "supported",
                "claim_id": "claim-controlled",
                "adopted_evidence_ids": ["evidence-controlled"],
            }
        ],
        "verification": {
            "protocol_version": "evidence-check/3",
            "model_call_id": "model:check",
            "request_sha256": sha(canonical(request)),
            "response_sha256": sha(canonical(response)),
            "response": response,
        },
    }
    args = {
        "context": request["original_context"],
        "request_hash": sha(canonical(request)),
        "response_hash": sha(canonical(response)),
    }
    assert validate_check3_attestation(request, result, receipt, **args)
    wrong = copy.deepcopy(result)
    wrong.pop("verification")
    with pytest.raises(ValueError, match="ATTESTATION"):
        validate_check3_attestation(request, wrong, receipt, **args)
    request["protocol_version"] = "evidence-check/2"
    with pytest.raises(ValueError, match="CONTEXT"):
        validate_check3_attestation(request, result, receipt, **args)


@pytest.mark.parametrize("changed", ["source", "answer", "partition"])
def test_positive_capture_cannot_hide_prior_counterevidence_with_alignment_labels(changed):
    request, response = check_fixture()
    contrary = copy.deepcopy(request["evidence"][0])
    contrary["evidence_id"] = "evidence-contrary"
    request["evidence"].append(contrary)
    request["prior_relations"] = [
        {
            "claim_id": "claim-controlled",
            "evidence_id": "evidence-contrary",
            "relation": "refutes",
            "quote": {
                "start": 0,
                "end": len(contrary["snapshot"]["text"]),
                "text": contrary["snapshot"]["text"],
                "sha256": contrary["snapshot"]["sha256"],
            },
            "decision_id": "decision-prior",
            "assessment_ref": "assessment-prior",
        }
    ]
    proposal = response["claims"][0]
    relation = copy.deepcopy(proposal["relations"][0])
    relation.update(evidence_id="evidence-contrary", relation="refutes")
    proposal["relations"].append(relation)
    if changed == "source":
        relation["source_alignment"] = "wrong_source"
    elif changed == "answer":
        proposal["answer_alignment"] = "unresolved"
    else:
        response["planning_alignment"]["status"] = "uncertain"
    request_hash, response_hash = sha(canonical(request)), sha(canonical(response))
    result = {
        "records": [
            {
                "record_type": "DecisionRecord",
                "decision_status": "supported",
                "claim_id": "claim-controlled",
                "adopted_evidence_ids": ["evidence-controlled"],
            }
        ],
        "verification": {
            "protocol_version": "evidence-check/3",
            "model_call_id": "model:check",
            "request_sha256": request_hash,
            "response_sha256": response_hash,
            "response": response,
        },
    }
    with pytest.raises(ValueError, match="PRIOR_SOURCE_ALIGNMENT_UNRESOLVED"):
        validate_check3_attestation(
            request,
            result,
            {"value": response},
            context=request["original_context"],
            request_hash=request_hash,
            response_hash=response_hash,
        )


@pytest.mark.parametrize("invalid", ["missing", None, "invalid", True])
def test_initial_constraints_are_required_and_strict(invalid):
    from deepresearch_workflow.agent_obligations import ObligationDecision

    wire = initial_wire()
    if invalid == "missing":
        wire.pop("constraints")
    else:
        wire["constraints"] = invalid
    with pytest.raises(ValueError):
        ObligationDecision.model_validate(wire)


def test_unconstrained_genuine_recommendation_remains_an_obligation():
    from deepresearch_workflow.agent_obligations import ObligationDecision

    question = "Recommend a deployment strategy."
    wire = initial_wire()
    wire["constraints"] = []
    wire["obligations"] = [
        dict(
            wire["obligations"][0],
            text=question,
            kind="recommendation",
            segment_ids=[question_segments(question)["segments"][0]["segment_id"]],
        )
    ]
    parsed = ObligationDecision.model_validate(wire)
    assert "constraints" in ObligationDecision.model_json_schema()["required"]
    drafts = obligation_drafts(question, [d.model_dump() for d in parsed.requirements], [])
    assert len(drafts) == 1 and drafts[0]["kind"] == "recommendation"


@pytest.mark.parametrize("index", ["0", 0.0, False])
def test_wire_constraint_indices_reject_coercion(index):
    from deepresearch_workflow.agent_obligations import ObligationDecision
    wire = initial_wire()
    wire["constraints"][0]["obligation_indices"] = [index]
    with pytest.raises(ValueError):
        ObligationDecision.model_validate(wire)


@pytest.mark.parametrize("invalid", [None, "invalid"])
def test_optional_conditions_default_only_on_omission(invalid):
    from deepresearch_workflow.agent_obligations import ObligationDecision
    wire = initial_wire()
    wire["obligations"][0]["applicability"].pop("conditions")
    assert ObligationDecision.model_validate(wire).requirements[0].applicability.conditions == []
    wire["obligations"][0]["applicability"]["conditions"] = invalid
    with pytest.raises(ValueError):
        ObligationDecision.model_validate(wire)

"""Offline obligation preservation and current Claim/DecisionRecord proof regressions."""

import copy
import json

import pytest

from deepresearch_workflow.agent_completion import begin_attempt, bind_criteria, ensure_criteria
from deepresearch_workflow.agent_investigations import accept_check, select_investigation
from deepresearch_workflow.agent_protocol import AgentTask
from deepresearch_workflow.agent_requirements import (
    CONTRACT_VERSION,
    MAX_REQUIREMENTS,
    RequirementError,
    bind_requirements,
    evaluate_coverage,
    freeze_requirements,
    validate_manifest,
    validate_requirement_claims,
)

QUESTION = "For Atlas v2 enterprise, verify encryption defaults; verify export retention."
SPLIT = QUESTION.index("verify export")


def scope(subject, *, version="v2"):
    return {
        "subject": subject,
        "version": {"status": "known", "value": version},
        "valid_at": {"status": "unknown", "value": None, "reason": "No effective date supplied"},
        "conditions": ["enterprise"],
    }


def drafts():
    return [
        {
            "text": "Verify encryption defaults",
            "question_spans": [{"start": 0, "end": SPLIT}],
            "kind": "factual",
            "applicability": scope("Atlas encryption defaults"),
        },
        {
            "text": "Verify export retention",
            "question_spans": [{"start": SPLIT, "end": len(QUESTION)}],
            "kind": "factual",
            "applicability": scope("Atlas export retention"),
        },
    ]


def fixture():
    manifest = freeze_requirements("run-coverage", QUESTION, drafts())
    task = AgentTask(
        task_id="task-1",
        objective="Resolve requested Atlas facts",
        acceptance_criteria=["Verify encryption defaults", "Verify export retention"],
        plan_version=1,
    ).model_dump(mode="json")
    tasks = [task]
    ensure_criteria("run-coverage", tasks)
    by_text = {row["text"]: row["requirement_id"] for row in manifest["requirements"]}
    bindings = bind_requirements(
        manifest,
        tasks,
        [
            {"requirement_id": by_text[row["text"]], "criterion_id": row["criterion_id"]}
            for row in task["criteria"]
        ],
    )
    state = {
        "run_id": "run-coverage",
        "tasks": tasks,
        "investigations": {},
        "task_investigations": {},
        "packet": {},
    }
    return manifest, state, bindings


def checked(state, index, *, status="supported"):
    """Use actual existing binding/attempt/acceptance helpers, with sanitized server records."""
    draft = drafts()[index]
    claim = {
        "text": ["Encryption is on by default", "Exports expire after seven days"][index],
        "kind": "factual",
        "applicability": draft["applicability"],
    }
    task = state["tasks"][0]
    criterion = task["criteria"][index]
    selected = bind_criteria(
        task, [claim], [{"criterion_id": criterion["criterion_id"], "claim_index": 0}]
    )
    key, entry, investigations, task_bindings = select_investigation(
        state, task, [claim], criterion_scoped=True
    )
    call_id = f"check-call-{index}"
    begin_attempt(task, selected, entry, call_id, key, state["tasks"], investigations)
    evidence_id, claim_id = f"evidence-{index}", f"claim-{index}"
    packet = {
        "check_id": f"check-{index}",
        "status": "complete",
        "records": [
            {
                "record_type": "Claim",
                **claim,
                "claim_id": claim_id,
                "run_id": state["run_id"],
                "decision_status": status,
                "freshness": "fresh",
                "evidence_links": [
                    {
                        "evidence_id": evidence_id,
                        "relation": "refutes" if status == "refuted" else "supports",
                    }
                ],
            },
            {
                "record_type": "DecisionRecord",
                "decision_id": f"decision-{claim_id}",
                "claim_id": claim_id,
                "run_id": state["run_id"],
                "decision_status": status,
                "adopted_evidence_ids": [evidence_id],
                "gaps": [],
                "unresolved_evidence_ids": [],
            },
        ],
        "gaps": [],
    }
    accept_check(entry, packet)
    entry["attempt_status"] = "accepted"
    state.update(investigations=investigations, task_investigations=task_bindings, packet=packet)
    return entry


def report(manifest, state, bindings):
    return evaluate_coverage(manifest, state["tasks"], state["investigations"], bindings)


def test_original_second_requirement_cannot_be_omitted_in_anchored_declaration():
    with pytest.raises(RequirementError, match="QUESTION_REGION_UNASSIGNED"):
        freeze_requirements("run-coverage", QUESTION, drafts()[:1])


def test_task_done_and_packet_complete_with_first_claim_do_not_complete_original_question():
    manifest, state, bindings = fixture()
    checked(state, 0)
    state["tasks"][0]["status"] = "done"
    result = report(manifest, state, bindings)
    assert not result["complete"]
    assert sorted(row["status"] for row in result["requirements"]) == ["blocked", "resolved"]
    assert any("export retention" in row["text"] and row["gaps"] for row in result["requirements"])
    assert state["packet"]["status"] == "complete"


@pytest.mark.parametrize(
    "statuses", [("supported", "supported"), ("supported", "refuted"), ("refuted", "refuted")]
)
def test_correct_full_coverage_retains_refutations_provenance_and_is_pure(statuses):
    manifest, state, bindings = fixture()
    for index, status in enumerate(statuses):
        checked(state, index, status=status)
    before = copy.deepcopy((manifest, state, bindings))
    result = report(manifest, state, bindings)
    assert result["complete"] and not result["gaps"]
    assert sorted(row["decision_status"] for row in result["requirements"]) == sorted(statuses)
    assert {value for row in result["requirements"] for value in row["claim_ids"]} == {
        "claim-0",
        "claim-1",
    }
    assert all(
        row["check_ids"] and row["evidence_ids"] and row["decision_ids"]
        for row in result["requirements"]
    )
    assert (manifest, state, bindings) == before


@pytest.mark.parametrize("mutation", ["version", "subject", "conditions", "time", "kind"])
def test_other_scope_or_kind_cannot_substitute_for_original_requirement(mutation):
    manifest, state, bindings = fixture()
    checked(state, 0)
    checked(state, 1)
    entry = state["investigations"][state["tasks"][0]["criteria"][0]["investigation_key"]]
    expected = state["tasks"][0]["criteria"][0]["expected_claim"]
    if mutation == "version":
        expected["applicability"]["version"]["value"] = "v1"
    elif mutation == "subject":
        expected["applicability"]["subject"] = "A different product"
    elif mutation == "conditions":
        expected["applicability"]["conditions"] = ["free tier"]
    elif mutation == "time":
        expected["applicability"]["valid_at"] = {"status": "known", "value": "2026-10-03T00:00:00Z"}
    else:
        expected["kind"] = "recommendation"
    # Even internally consistent substituted criterion/check cannot change original scope.
    entry["packet"]["records"][0].update(copy.deepcopy(expected))
    assert not report(manifest, state, bindings)["complete"]


@pytest.mark.parametrize(
    "mutation",
    [
        "contested",
        "insufficient",
        "gap",
        "unresolved",
        "failed",
        "pending",
        "stale_call",
        "no_check",
        "duplicate_claim",
        "no_decision",
        "duplicate_decision",
        "wrong_decision",
        "wrong_run",
        "old_claim",
        "no_adopted",
        "unlinked",
        "wrong_direction",
        "cancelled",
    ],
)
def test_missing_or_invalid_current_proof_blocks_complete(mutation):
    manifest, state, bindings = fixture()
    checked(state, 0)
    checked(state, 1)
    entry = state["investigations"][state["tasks"][0]["criteria"][0]["investigation_key"]]
    packet = entry["packet"]
    claim, decision = packet["records"]
    if mutation in {"contested", "insufficient"}:
        claim["decision_status"] = decision["decision_status"] = mutation
    elif mutation == "gap":
        decision["gaps"] = ["Source does not establish condition"]
    elif mutation == "unresolved":
        decision["unresolved_evidence_ids"] = ["counterevidence-new"]
    elif mutation in {"failed", "pending"}:
        entry["attempt_status"] = mutation
    elif mutation == "stale_call":
        entry["latest_call_id"] = "new-unsettled-call"
    elif mutation == "no_check":
        packet.pop("check_id")
    elif mutation == "duplicate_claim":
        packet["records"].append(copy.deepcopy(claim))
    elif mutation == "no_decision":
        packet["records"].remove(decision)
    elif mutation == "duplicate_decision":
        packet["records"].append(copy.deepcopy(decision))
    elif mutation == "wrong_decision":
        decision["decision_status"] = "refuted"
    elif mutation == "wrong_run":
        decision["run_id"] = "another-run"
    elif mutation == "old_claim":
        claim["freshness"] = "expired"
    elif mutation == "no_adopted":
        decision["adopted_evidence_ids"] = []
    elif mutation == "unlinked":
        decision["adopted_evidence_ids"] = ["another-evidence"]
    elif mutation == "wrong_direction":
        claim["evidence_links"][0]["relation"] = "refutes"
    elif mutation == "cancelled":
        state["tasks"][0]["status"] = "cancelled"
    result = report(manifest, state, bindings)
    assert not result["complete"] and result["gaps"]


def test_stale_dependency_remains_gap_even_if_task_label_says_done():
    manifest, state, bindings = fixture()
    checked(state, 0)
    checked(state, 1)
    state["tasks"][0]["dependencies"] = ["missing-prerequisite"]
    state["tasks"][0]["status"] = "done"
    result = report(manifest, state, bindings)
    assert not result["complete"]
    assert all(row["status"] == "stale" for row in result["requirements"])


def test_bindings_are_append_only_and_replayable_with_distinct_criteria():
    manifest, state, bindings = fixture()
    first = bind_requirements(manifest, state["tasks"], bindings[:1])
    assert bind_requirements(manifest, state["tasks"], bindings[1:], existing=first) == bindings
    assert bind_requirements(manifest, state["tasks"], bindings, existing=bindings) == bindings
    assert not report(manifest, state, [])["complete"]


@pytest.mark.parametrize(
    "mutation",
    ["duplicate", "unknown", "criterion_reused", "replace", "removed_criterion", "cross_run"],
)
def test_forged_or_removed_bindings_rejected(mutation):
    manifest, state, bindings = fixture()
    proposed, existing = copy.deepcopy(bindings), None
    if mutation == "duplicate":
        proposed.append(copy.deepcopy(proposed[0]))
    elif mutation == "unknown":
        proposed[0]["requirement_id"] = "requirement-unrecognized"
    elif mutation == "criterion_reused":
        proposed[1]["criterion_id"] = proposed[0]["criterion_id"]
    elif mutation == "replace":
        existing = bindings[:1]
        proposed = [{**bindings[0], "criterion_id": bindings[1]["criterion_id"]}]
    elif mutation == "removed_criterion":
        state["tasks"] = []
    else:
        manifest = freeze_requirements("another-run", QUESTION, drafts())
    with pytest.raises(RequirementError):
        bind_requirements(manifest, state["tasks"], proposed, existing=existing)


@pytest.mark.parametrize("mutation", ["removed", "added", "text", "scope", "span"])
def test_original_requirements_never_disappear_or_change_on_replan(mutation):
    original = freeze_requirements("run-coverage", QUESTION, drafts())
    proposal = drafts()
    if mutation == "removed":
        proposal = proposal[:1]
        proposal[0]["question_spans"] = [{"start": 0, "end": len(QUESTION)}]
    elif mutation == "added":
        proposal.append({**copy.deepcopy(proposal[0]), "text": "New inferred obligation"})
    elif mutation == "text":
        proposal[0]["text"] = "A narrower interpretation"
    elif mutation == "scope":
        proposal[0]["applicability"]["conditions"] = ["free tier"]
    else:
        proposal[0]["question_spans"][0]["end"] += 1
    with pytest.raises(RequirementError, match="REQUIREMENTS_CHANGED"):
        freeze_requirements("run-coverage", QUESTION, proposal, existing=original)


def test_manifest_survives_json_restart_order_reasons_and_condition_order():
    declaration = drafts()
    declaration[0]["applicability"]["conditions"] += ["region EU", "😀"]
    original = freeze_requirements("run-coverage", QUESTION, declaration)
    restored = json.loads(json.dumps(original, ensure_ascii=False))
    declaration.reverse()
    declaration[1]["applicability"]["conditions"].reverse()
    declaration[1]["applicability"]["valid_at"]["reason"] = "Another unknown explanation"
    assert freeze_requirements("run-coverage", QUESTION, declaration, existing=restored) == original
    assert validate_manifest(restored, run_id="run-coverage", question=QUESTION) == original


@pytest.mark.parametrize(
    "mutation",
    ["duplicate_id", "hash", "wrong_question", "wrong_run", "extra", "negative_length", "version"],
)
def test_manifest_identity_and_original_binding_fail_closed(mutation):
    manifest = freeze_requirements("run-coverage", QUESTION, drafts())
    question, run_id = QUESTION, "run-coverage"
    if mutation == "duplicate_id":
        manifest["requirements"][1]["requirement_id"] = manifest["requirements"][0][
            "requirement_id"
        ]
    elif mutation == "hash":
        manifest["manifest_sha256"] = "0" * 64
    elif mutation == "wrong_question":
        question = QUESTION + " changed"
    elif mutation == "wrong_run":
        run_id = "another-run"
    elif mutation == "extra":
        manifest["complete"] = True
    elif mutation == "negative_length":
        manifest["question_length"] = -1
    else:
        manifest["contract_version"] = "unknown/2"
    with pytest.raises(RequirementError):
        validate_manifest(manifest, run_id=run_id, question=question)


@pytest.mark.parametrize(
    "mutation",
    [
        "duplicate",
        "duplicate_span",
        "outside",
        "byte_offset",
        "whitespace",
        "extra",
        "empty",
        "limit",
        "bool_offset",
    ],
)
def test_declarations_are_bounded_anchored_and_strict(mutation):
    declaration = drafts()
    if mutation == "duplicate":
        declaration.append(copy.deepcopy(declaration[0]))
    elif mutation == "duplicate_span":
        declaration[0]["question_spans"] *= 2
    elif mutation in {"outside", "byte_offset"}:
        declaration[1]["question_spans"][0]["end"] += 10
    elif mutation == "whitespace":
        declaration[0]["text"] = "   "
    elif mutation == "extra":
        declaration[0]["requirement_id"] = "model-invented-id"
    elif mutation == "empty":
        declaration = []
    elif mutation == "limit":
        declaration = [
            {**copy.deepcopy(declaration[0]), "text": f"obligation-{index}"}
            for index in range(MAX_REQUIREMENTS + 1)
        ]
    else:
        declaration[0]["question_spans"][0]["start"] = False
    with pytest.raises(RequirementError):
        freeze_requirements("run-coverage", QUESTION, declaration)


def test_unicode_anchors_are_codepoints_and_missing_legacy_manifest_is_an_honest_gap():
    question = "核查😀版本二默认加密；导出保留七天？"  # noqa: RUF001 - exact Unicode question
    declaration = drafts()
    split = question.index("导出")
    declaration[0]["question_spans"] = [{"start": 0, "end": split}]
    declaration[1]["question_spans"] = [{"start": split, "end": len(question)}]
    manifest = freeze_requirements("run-cn", question, declaration)
    assert manifest["question_length"] == len(question) < len(question.encode("utf-8"))
    declaration[1]["question_spans"][0]["end"] = len(question.encode("utf-8"))
    with pytest.raises(RequirementError, match="ANCHOR_INVALID"):
        freeze_requirements("run-cn", question, declaration)
    result = evaluate_coverage(None, [], {}, [])
    assert result["contract_version"] == CONTRACT_VERSION
    assert not result["complete"] and result["gaps"]


def test_full_question_anchor_does_not_certify_semantic_extraction_quality():
    # Deliberately bad semantic extraction passes structural anchoring. Keep that
    # limitation visible; no generic-task or ID-only semantic completeness claim.
    declaration = drafts()[:1]
    declaration[0]["question_spans"] = [{"start": 0, "end": len(QUESTION)}]
    manifest = freeze_requirements("run-coverage", QUESTION, declaration)
    assert len(manifest["requirements"]) == 1
    assert not evaluate_coverage(manifest, [], {}, [])["complete"]


@pytest.mark.parametrize("substitute", [False, True])
def test_requirement_scope_is_checked_before_an_investigation_and_does_not_mutate(substitute):
    manifest, state, bindings = fixture()
    claim = {
        "text": "Encryption defaults",
        "kind": "factual",
        "applicability": scope("Atlas encryption defaults", version="v1" if substitute else "v2"),
    }
    criterion_bindings = [
        {"criterion_id": state["tasks"][0]["criteria"][0]["criterion_id"], "claim_index": 0}
    ]
    before = copy.deepcopy(state)
    if substitute:
        with pytest.raises(RequirementError, match="CLAIM_SCOPE_CHANGED"):
            validate_requirement_claims(
                manifest, state["tasks"], [claim], criterion_bindings, bindings
            )
    else:
        validate_requirement_claims(manifest, state["tasks"], [claim], criterion_bindings, bindings)
    assert state == before


def test_one_claim_cannot_cover_two_requirements_before_check_or_in_terminal_proof():
    manifest, state, bindings = fixture()
    criterion_bindings = [
        {"criterion_id": row["criterion_id"], "claim_index": 0}
        for row in state["tasks"][0]["criteria"]
    ]
    claim = {
        "text": "Encryption defaults",
        "kind": "factual",
        "applicability": scope("Atlas encryption defaults"),
    }
    with pytest.raises(RequirementError, match="CHECK_CLAIM_REUSED"):
        validate_requirement_claims(manifest, state["tasks"], [claim], criterion_bindings, bindings)
    checked(state, 0)
    checked(state, 1)
    second = state["investigations"][state["tasks"][0]["criteria"][1]["investigation_key"]]
    second["packet"]["records"][0]["claim_id"] = "claim-0"
    second["packet"]["records"][1]["claim_id"] = "claim-0"
    assert not report(manifest, state, bindings)["complete"]


def test_declaration_models_extend_existing_budgeted_decision_schema():
    from pydantic import Field

    from deepresearch_workflow.agent_protocol import AgentDecision
    from deepresearch_workflow.agent_requirements import RequirementBinding, RequirementDraft

    class DecisionWithRequirements(AgentDecision):
        requirements: list[RequirementDraft] = Field(default_factory=list)
        requirement_bindings: list[RequirementBinding] = Field(default_factory=list)

    schema = DecisionWithRequirements.model_json_schema()
    assert schema["properties"]["requirements"]["items"]["$ref"].endswith("/RequirementDraft")
    assert schema["$defs"]["RequirementDraft"]["additionalProperties"] is False
    response = DecisionWithRequirements.model_validate(
        {
            "action": "search",
            "query": "Atlas v2",
            "tool": "web_search",
            "reason": "Read facts",
            "requirements": drafts(),
        }
    )
    assert len(response.requirements) == 2

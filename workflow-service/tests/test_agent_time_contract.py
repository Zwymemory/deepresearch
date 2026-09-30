"""Offline time-scope contract and the historical web failure boundary."""

from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError

from deepresearch_workflow.agent_protocol import AgentDecision, ClaimDraft, ClaimScope, ModelResult
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph

from .test_agent_runtime import setup

ROOT = Path(__file__).resolve().parents[2]
UNKNOWN_TIME = {
    "status": "unknown",
    "value": None,
    "reason": "The original does not declare a fact-effective time",
}


def scope(valid_at, *, version="2026-09-27 修订版"):
    return {
        "subject": "IANA example domains",
        "version": {"status": "known", "value": version},
        "valid_at": valid_at,
        "conditions": [],
    }


@pytest.mark.parametrize(
    "value",
    ["2026-09-27T08:30:45Z", "2026-09-27T16:30:45+08:00", "2026-09-27T08:30:45.123Z"],
)
def test_valid_at_accepts_real_timestamp_without_narrowing_version(value):
    parsed = ClaimScope.model_validate(scope({"status": "known", "value": value}))
    assert parsed.valid_at.value == value
    assert parsed.version.value == "2026-09-27 修订版"


def test_unknown_fact_time_retains_reason_and_does_not_reject_version_text():
    parsed = ClaimScope.model_validate(scope(UNKNOWN_TIME))
    assert parsed.valid_at.model_dump() == UNKNOWN_TIME
    assert parsed.version.status == "known"


@pytest.mark.parametrize(
    "value",
    [
        "2026-09-27 修订版",  # Historical web run wf-ac077ab9... supplied this value.
        "2026-09-27",
        "2026-09-27T08:30:45",
        "2026-02-30T08:30:45Z",
        "2026-09-27T08:30:45+18:01",
    ],
)
def test_invalid_fact_time_is_rejected_at_runtime(value):
    with pytest.raises(ValidationError):
        ClaimScope.model_validate(scope({"status": "known", "value": value}))


def test_model_schema_exposes_time_profile_but_keeps_version_generic():
    schema = AgentDecision.model_json_schema()
    time_value = schema["$defs"]["KnownValidAt"]["properties"]["value"]
    version_value = schema["$defs"]["KnownValue"]["properties"]["value"]
    assert time_value["format"] == "date-time"
    assert "T" in time_value["pattern"] and "pattern" not in version_value
    candidate = {
        "action": "check_claims",
        "reason": "Check the original",
        "claims": [
            {
                "text": "The names are reserved for documentation examples.",
                "kind": "factual",
                "applicability": scope({"status": "known", "value": "2026-09-27 修订版"}),
            }
        ],
    }
    assert not Draft202012Validator(schema).is_valid(candidate)
    candidate["claims"][0]["applicability"]["valid_at"] = UNKNOWN_TIME
    assert Draft202012Validator(schema).is_valid(candidate)


def test_historical_web_revision_and_observation_time_cannot_become_fact_time():
    reviews = json.loads(
        (ROOT / "testdata/agent-live/sources/live-run-reviews.json").read_text()
    )
    web = next(row for row in reviews["attempts"] if row["scenario"] == "web-only")
    assert web["run_id"] == "wf-ac077ab9-912c-47e4-894b-a8199e454dbc"
    assert web["original_sources"][0]["snapshot_sha256"] == (
        "9fcf59ddeda8811d0631eae204201f1b93f98b493e07015dfbfb69db7b40bbb2"
    )
    # The public review records the failed value; this minimal fixture has no remote I/O.
    record = {
        "evidence_id": "historical-web-original",
        "applicability": {"valid_at": copy.deepcopy(UNKNOWN_TIME)},
        "source": {"observed_at": "2026-09-27T00:00:00Z"},
        "snapshot": {"text": "Last revised: 2026-09-27"},
    }
    claim = ClaimDraft.model_validate(
        {
            "text": "The names are reserved for documentation examples.",
            "kind": "factual",
            "applicability": scope(
                {"status": "known", "value": "2026-09-27T00:00:00Z"}
            ),
        }
    )
    issue = AutonomousResearchGraph.time_scope_issue(
        [claim], [record], [record["evidence_id"]]
    )
    assert issue["errorCode"] == "CLAIM_VALID_AT_NOT_DECLARED"
    assert issue["fieldPath"] == "claims[0].applicability.valid_at"
    assert "revision" in issue["correction"] and "observation" in issue["correction"]
    claim = claim.model_copy(
        update={"applicability": ClaimScope.model_validate(scope(UNKNOWN_TIME))}
    )
    assert AutonomousResearchGraph.time_scope_issue(
        [claim], [record], [record["evidence_id"]]
    ) is None


def test_explicit_source_time_allows_equivalent_offset_in_claim_scope():
    record = {
        "evidence_id": "declared-time-original",
        "applicability": {"valid_at": {"status": "known", "value": "2026-09-27T00:00:00Z"}},
    }
    claim = ClaimDraft.model_validate(
        {
            "text": "The limit applies at the declared time.",
            "kind": "factual",
            "applicability": scope(
                {"status": "known", "value": "2026-09-27T08:00:00+08:00"}
            ),
        }
    )
    assert (
        AutonomousResearchGraph.time_scope_issue([claim], [record], [record["evidence_id"]])
        is None
    )


async def test_time_feedback_prevents_repeat_tool_call_and_unknown_can_still_be_checked():
    context = setup("version-difference")
    model, ledger, evidence = context[4], context[3], context[6]
    invoke = model.invoke
    first_claim = None

    async def decision_with_observation_time(request):
        nonlocal first_claim
        if request.name == "AgentDecision":
            last = (request.payload["observations"] or [{}])[-1]
            if last.get("errorCode") == "CLAIM_VALID_AT_NOT_DECLARED":
                model.requests.append(request)
                corrected = copy.deepcopy(first_claim)
                corrected["claims"][0]["applicability"]["valid_at"] = UNKNOWN_TIME
                return ModelResult(value=corrected, input_tokens=120, output_tokens=100)
        result = await invoke(request)
        if result.value.get("action") == "check_claims" and first_claim is None:
            first_claim = copy.deepcopy(result.value)
            first_claim["claims"][0]["applicability"]["valid_at"] = {
                "status": "known",
                "value": "2026-09-29T08:00:00Z",  # Fixture source observed_at, not Valid at.
            }
            return result.model_copy(update={"value": copy.deepcopy(first_claim)})
        return result

    model.invoke = decision_with_observation_time
    await context[0].run_claimed(context[1])
    assert context[7].requests[-1].status == "SUCCEEDED"
    assert len(evidence.checks) == 1
    assert evidence.checks[0][0]["applicability"]["valid_at"] == UNKNOWN_TIME
    assert len([row for row in ledger.rows.values() if row["kind"] == "TOOL"]) == 5
    feedback = next(
        row.payload["observations"][-1]
        for row in model.requests
        if row.name == "AgentDecision"
        and row.payload["observations"]
        and row.payload["observations"][-1].get("errorCode")
        == "CLAIM_VALID_AT_NOT_DECLARED"
    )
    assert feedback["fieldPath"] == "claims[0].applicability.valid_at"
    assert "correction" in feedback


async def test_repeated_unsupported_time_stops_within_existing_decision_limit():
    context = setup("version-difference")
    model, ledger, evidence = context[4], context[3], context[6]
    invoke = model.invoke
    invalid_claim = None

    async def repeat_observation_time(request):
        nonlocal invalid_claim
        if request.name == "AgentDecision":
            last = (request.payload["observations"] or [{}])[-1]
            if last.get("errorCode") == "CLAIM_VALID_AT_NOT_DECLARED":
                model.requests.append(request)
                return ModelResult(
                    value=copy.deepcopy(invalid_claim), input_tokens=120, output_tokens=100
                )
        result = await invoke(request)
        if result.value.get("action") == "check_claims" and invalid_claim is None:
            invalid_claim = copy.deepcopy(result.value)
            invalid_claim["claims"][0]["applicability"]["valid_at"] = {
                "status": "known",
                "value": "2026-09-29T08:00:00Z",
            }
            return result.model_copy(update={"value": copy.deepcopy(invalid_claim)})
        return result

    model.invoke = repeat_observation_time
    await context[0].run_claimed(context[1])
    assert context[7].requests[-1].status == "INSUFFICIENT_EVIDENCE"
    assert not evidence.checks
    assert len([row for row in ledger.rows.values() if row["kind"] == "TOOL"]) == 4
    rejected = [
        event
        for event in context[2].events
        if event.event_type == "AGENT_OBSERVATION"
        and event.safe_payload.get("errorCode") == "CLAIM_VALID_AT_NOT_DECLARED"
    ]
    assert len(rejected) == 2
    assert len(model.requests) <= 16

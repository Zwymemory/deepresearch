"""Real JSON adapter -> admission -> first planning; all I/O is offline substitutes.

The rejected fixtures here are constructed probes, never recovered live outputs.
"""

import copy
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest.mock import AsyncMock

import httpx
import pytest

from deepresearch_workflow.agent_budget import SAFE_FIELDS, AgentBudgetGateway
from deepresearch_workflow.agent_diagnostics import (
    CHECK_ERROR_CODES,
    REQUIREMENT_ERROR_CODES,
    VALIDATION_STAGES,
)
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from deepresearch_workflow.agent_requirements import RequirementError
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.graph import ModelCallError, WorkflowExecutionError
from deepresearch_workflow.runner import WorkflowRunner

from .test_agent_identity import export
from .test_agent_json_transport import envelope, json_settings
from .test_agent_runtime import LegacyFixtureGraph, setup

ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = json.loads((ROOT / "testdata/agent-live/sources/scenarios.json").read_text())
QUESTION = next(case["question"] for case in SCENARIOS["cases"] if case["id"] == "web-only")
CANARY = "PRIVATE_CANARY_QUESTION_SOURCE_OR_EXCEPTION"


def declarations(question=QUESTION, split=None):
    split = question.index("它们的 HTTP") if split is None else split
    unknown = {
        "status": "unknown",
        "value": None,
        "reason": "Fixture establishes no effective date",
    }
    return [
        {
            "text": text,
            "question_spans": [{"start": start, "end": end}],
            "kind": "factual",
            "applicability": {
                "subject": subject,
                "version": unknown,
                "valid_at": unknown,
                "conditions": ["IANA Example Domains"],
            },
        }
        for text, subject, start, end in [
            (
                "核实两域名注册与转让限制",
                "example.com/example.org registration and transfer",
                0,
                split,
            ),
            (
                "核实 HTTP 生产保障及范围",
                "example.com/example.org HTTP production guarantees",
                split,
                len(question),
            ),
        ]
    ]


class PlanningLedger:
    """In-memory reservation fence; actual SQL fence is tested in test_agent_postgres."""

    def __init__(self):
        self.rows, self.settlements, self.tasks = {}, [], []

    async def reserve(self, run, claim, key, kind, purpose, digest, *_):
        if key in self.rows:
            row = self.rows[key]
            assert row["digest"] == digest
            if row["status"] == "UNKNOWN":
                raise WorkflowExecutionError(
                    "Cannot replay", error_code="AGENT_MODEL_NOT_RETRYABLE"
                )
            return {"replay": row["result"], "attempt": 1}
        self.rows[key] = {"digest": digest, "status": "RESERVED"}
        return {"replay": None, "attempt": 1}

    async def settle(self, *args, unknown=False):
        row = self.rows[args[2]]
        assert row["status"] == "RESERVED"
        self.settlements.append((args, unknown))
        row.update(status="UNKNOWN" if unknown else "SETTLED", result=args[4], usage=args[5])

    async def summary(self, *_):
        return {
            "modelCalls": len(self.rows),
            "toolCalls": 0,
            "inputTokens": sum(
                row.get("usage", {}).get("input_tokens", 0) for row in self.rows.values()
            ),
            "outputTokens": sum(
                row.get("usage", {}).get("output_tokens", 0) for row in self.rows.values()
            ),
        }

    async def save_tasks(self, *args):
        self.tasks = copy.deepcopy(args[-1])

    async def save_requirements(self, *args):
        self.manifest, self.bindings = copy.deepcopy(args[2:4])


def state(question=QUESTION):
    return {
        "run_id": "offline-first-planning",
        "question": question,
        "deadline_at": (datetime.now(UTC) + timedelta(seconds=180)).isoformat(),
        "decision_steps": 0,
        "plan_version": 1,
        "observations": [],
        "candidates": [],
        "evidence": [],
        "tasks": [],
        "packet": {},
        "investigations": {},
        "task_investigations": {},
        "context_snapshot": {},
        "no_progress": 0,
        "conflict_rounds": 0,
        "requested_scopes": ["web_search", "read_source", "check_claims"],
    }


def graph(model, ledger, *, legacy_fixture=True):
    return (LegacyFixtureGraph if legacy_fixture else AutonomousResearchGraph)(
        model=model,
        ledger=ledger,
        tools=None,
        repository=type(
            "Repo",
            (),
            {
                "assert_active_claim": AsyncMock(return_value=(True, False)),
                "update_progress": AsyncMock(return_value=True),
            },
        )(),
        evidence=type(
            "Evidence",
            (),
            {"publish": AsyncMock(side_effect=AssertionError("Unverified publication attempted"))},
        )(),
        events=type("Events", (), {"emit": AsyncMock()})(),
        budget=AgentRunBudget(runtime="agent"),
        claim_token="offline-claim",
    )


@pytest.mark.parametrize(
    "question,split",
    [
        (QUESTION, None),
        ("核查😀注册；核查😀转让？", len("核查😀注册；")),  # noqa: RUF001 - exact Unicode fixture
        ("核查注册；核查注册？", len("核查注册；")),  # noqa: RUF001 - repeated exact text fixture
    ],
)
async def test_full_first_planning_separate_obligations_replay_and_publication_gate(
    question, split
):
    value = {
        "action": "finish",
        "reason": "Offline attempted publication",
        "requirements": declarations(question, split),
    }
    ledger, calls = PlanningLedger(), []

    def transport(wire):
        calls.append(wire)
        return httpx.Response(200, json=envelope(json.dumps(value, ensure_ascii=False)))

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        initial = state(question)
        runtime = graph(OpenAIAgentModel(json_settings(), client), ledger)
        update = await runtime.decide(initial)
        st = {**initial, **update}
        st.update(await runtime.act(st))
        assert (
            len(st["original_requirements"]["requirements"]) == len(st["requirement_bindings"]) == 2
        )
        criteria = [c for task in st["tasks"] for c in task["criteria"]]
        assert len({c["criterion_id"] for c in criteria}) == 2
        assert st["observations"][-1]["errorCode"] == "ORIGINAL_REQUIREMENTS_INCOMPLETE"
        assert not st.get("final_status") and not runtime.evidence.publish.called
        # Restart before checkpoint must validate the SETTLED response but never charge twice.
        runtime.claim_token = "offline-recovered-claim"
        await runtime.decide(json.loads(json.dumps(initial)))
    assert len(calls) == len(ledger.settlements) == 1
    assert (await ledger.summary())["inputTokens"] == 41


@pytest.mark.parametrize(
    "mutation,code,stage,path",
    [
        ("missing", "REQUIREMENTS_MISSING_OR_LIMIT", "planning_requirements", None),
        ("outside", "REQUIREMENT_ANCHOR_INVALID", "planning_requirements", None),
        ("byte_offsets", "REQUIREMENT_ANCHOR_INVALID", "planning_requirements", None),
        ("uncovered", "REQUIREMENT_QUESTION_REGION_UNASSIGNED", "planning_requirements", None),
        (
            "punctuation_gap",
            "REQUIREMENT_QUESTION_REGION_UNASSIGNED",
            "planning_requirements",
            None,
        ),
        ("duplicate_span", "REQUIREMENT_DUPLICATE_SPAN", "planning_requirements", None),
        ("bool_offset", None, "result_schema", "requirements.question_spans.start"),
        ("reversed_span", None, "planning_decision", "requirements.question_spans"),
        (
            "invalid_effective_time",
            None,
            "planning_decision",
            "requirements.applicability.valid_at.value",
        ),
        ("unknown_field", None, "result_schema", "unknown_field"),
        ("missing_query", None, "planning_decision", "unknown_field"),
    ],
)
async def test_first_planning_rejections_propagate_safe_metadata_once(
    mutation, code, stage, path, caplog
):
    value = {
        "action": "finish",
        "reason": "Offline validation probe",
        "requirements": declarations(),
    }
    requirements = value["requirements"]
    if mutation == "missing":
        value.pop("requirements")
    elif mutation in {"outside", "byte_offsets"}:
        requirements[1]["question_spans"][0]["end"] = (
            len(QUESTION) + 1 if mutation == "outside" else len(QUESTION.encode("utf-8"))
        )
    elif mutation == "uncovered":
        value["requirements"] = requirements[:1]
    elif mutation == "punctuation_gap":
        requirements[0]["question_spans"][0]["end"] -= 1
    elif mutation == "duplicate_span":
        requirements[0]["question_spans"] *= 2
    elif mutation == "bool_offset":
        requirements[0]["question_spans"][0]["start"] = False
    elif mutation == "reversed_span":
        requirements[0]["question_spans"][0] = {"start": 5, "end": 4}
    elif mutation == "invalid_effective_time":
        requirements[0]["applicability"]["valid_at"] = {"status": "known", "value": CANARY}
    elif mutation == "unknown_field":
        value[CANARY] = CANARY
    else:
        value = {"action": "search", "reason": "Missing action field probe"}
    ledger, calls = PlanningLedger(), []

    def transport(wire):
        calls.append(wire)
        return httpx.Response(200, json=envelope(json.dumps(value, ensure_ascii=False)))

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        runtime = graph(OpenAIAgentModel(json_settings(), client), ledger)
        with pytest.raises(ModelCallError) as captured:
            await runtime.decide(state())
        failure = captured.value
        assert failure.error_code == "MODEL_SCHEMA_INVALID" and failure.retryable is False
        assert failure.validation_stage == stage and failure.domain_error_code == code
        assert failure.error_class == ("requirement_validation" if code else "schema_validation")
        if path:
            assert path in failure.validation_issue_codes
        _, unknown = ledger.settlements[0]
        receipt = ledger.rows["model:agent:decision-1"]
        assert unknown and receipt["status"] == "UNKNOWN"
        assert receipt["usage"]["input_tokens"] == 41 and receipt["usage"]["output_tokens"] == 7
        metadata = receipt["usage"]["model_failure"]
        assert metadata["validation_stage"] == stage
        assert metadata.get("domain_error_code") == code
        assert export.safe_model_failure(metadata) == metadata
        WorkflowRunner._log_failure("offline-first-planning", failure)
        status, error_code = WorkflowRunner._failure_status(failure)
        assert status == "FAILED" and error_code == "MODEL_SCHEMA_INVALID"
        public_message = WorkflowRunner._safe_failure_message(failure)
        assert stage in public_message and (code is None or code in public_message)
        assert stage in caplog.text
        assert CANARY not in json.dumps(metadata) + public_message + caplog.text
        assert QUESTION not in json.dumps(metadata) + public_message + caplog.text
        with pytest.raises(WorkflowExecutionError, match="Cannot replay"):
            await runtime.decide(state())
    assert len(calls) == len(ledger.settlements) == 1


async def test_unexpected_planning_application_error_is_internal_and_safe(monkeypatch, caplog):
    def broken_freeze(*_, **__):
        raise RuntimeError(CANARY)

    monkeypatch.setattr("deepresearch_workflow.agent_runtime.freeze_requirements", broken_freeze)
    model = type(
        "Model",
        (),
        {
            "invoke": AsyncMock(
                return_value=ModelResult(
                    value={"action": "finish", "reason": "Fixture", "requirements": declarations()},
                    input_tokens=19,
                    output_tokens=3,
                )
            )
        },
    )()
    ledger = PlanningLedger()
    with pytest.raises(ModelCallError) as captured:
        await graph(model, ledger).decide(state())
    failure = captured.value
    assert failure.failure_kind == "INTERNAL" and failure.error_class == "application_internal"
    assert failure.error_code == "AGENT_MODEL_INTERNAL_ERROR"
    assert failure.validation_stage == "planning_requirements" and failure.domain_error_code is None
    usage = ledger.settlements[0][0][-1]
    assert (usage["input_tokens"], usage["output_tokens"]) == (19, 3)
    assert export.safe_model_failure(usage["model_failure"]) == usage["model_failure"]
    WorkflowRunner._log_failure("offline-first-planning", failure)
    assert CANARY not in caplog.text + str(failure) + json.dumps(usage)
    assert failure.__cause__ is None and model.invoke.await_count == 1


@pytest.mark.parametrize("code", [*sorted(REQUIREMENT_ERROR_CODES), CANARY, [], True])
async def test_domain_codes_are_allowlisted_and_unknown_codes_never_escape(code):
    ledger = PlanningLedger()
    model = type(
        "Model",
        (),
        {
            "invoke": AsyncMock(
                return_value=ModelResult(
                    value={},
                    input_tokens=19,
                    output_tokens=3,
                )
            )
        },
    )()
    gw = AgentBudgetGateway(
        run_id="offline",
        claim_token="offline",
        budget=AgentRunBudget(runtime="agent"),
        ledger=ledger,
        model=model,
        guard=AsyncMock(),
    )

    def invalid(_):
        raise RequirementError(code)

    with pytest.raises(ModelCallError) as captured:
        await gw.model_call(
            "model:domain",
            "DECISION",
            ModelRequest(
                name="AgentDecision", instruction="offline", payload={}, schema={"type": "object"}
            ),
            invalid,
        )
    failure = captured.value
    known = type(code) is str and code in REQUIREMENT_ERROR_CODES
    assert failure.error_class == ("requirement_validation" if known else "application_internal")
    assert failure.domain_error_code == (code if known else None)
    usage = ledger.settlements[0][0][-1]
    assert export.safe_model_failure(usage["model_failure"]) == usage["model_failure"]
    assert CANARY not in str(failure) + json.dumps(usage)


def test_export_metadata_vocabularies_and_redaction_match_production():
    assert export.MODEL_REQUIREMENT_CODES == REQUIREMENT_ERROR_CODES
    assert export.MODEL_CHECK_CODES == CHECK_ERROR_CODES
    assert export.MODEL_VALIDATION_STAGES == VALIDATION_STAGES
    assert export.MODEL_ISSUE_FIELDS == SAFE_FIELDS
    unsafe = {
        "failure_kind": "INTERNAL",
        "error_class": "application_internal",
        "retryable": False,
        "domain_error_code": CANARY,
        "validation_stage": CANARY,
        "validation_issue_codes": ["requirements.question_spans.start", CANARY],
        "raw_body": CANARY,
    }
    assert export.safe_model_failure(unsafe) == {
        "failure_kind": "INTERNAL",
        "error_class": "application_internal",
        "retryable": False,
        "validation_issue_codes": ["requirements.question_spans.start"],
    }


async def test_actual_runner_finalizes_safe_planning_code_and_known_usage(caplog):
    calls = []

    def transport(wire):
        calls.append(wire)
        return httpx.Response(
            200,
            json=envelope(
                json.dumps(
                    {
                        "action": "finish",
                        "reason": "Offline empty declaration probe",
                        "planner_contract": "agent-planning-segments/2",
                    }
                )
            ),
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        context = setup("version-difference", model=OpenAIAgentModel(json_settings(), client))
        run = context[1].model_copy(update={"question": QUESTION})
        await context[0].run_claimed(run)
    finalized = context[7].requests[-1]
    assert finalized.status == "FAILED" and finalized.errorCode == "MODEL_SCHEMA_INVALID"
    assert "planning_requirements" in finalized.errorMessage
    assert "REQUIREMENTS_MISSING_OR_LIMIT" in finalized.errorMessage
    assert finalized.usage["modelCalls"] == 1 and finalized.usage["totalTokens"] == 48
    assert len(calls) == 1 and not context[5].calls and not context[6].publications
    assert "domain_error_code=REQUIREMENTS_MISSING_OR_LIMIT" in caplog.text

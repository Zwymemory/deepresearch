"""Offline manifest/report checker tests; reports below are synthetic, not real runs."""

import importlib.util
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "evidence_acceptance", ROOT / "testdata/agent-round1/evidence/prepare_real_acceptance.py"
)
acceptance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(acceptance)


def prepared(monkeypatch):
    monkeypatch.setattr(acceptance.subprocess, "check_output", lambda *args, **kwargs: "a" * 40)
    return acceptance.manifest("a" * 40)


def reported(plan):
    return {
        "candidate_sha": plan["candidate_sha"],
        "executed": True,
        "runs": [
            {
                "case_id": case["id"],
                "attempts": 1,
                "receipts_collected": True,
                "real_model": "expected_statuses" in case,
                "real_network": "expected_statuses" not in case,
                "usage": {
                    "decisions": 1,
                    "model_calls": 1 if "expected_statuses" in case else 0,
                    "external_tool_calls": 1,
                    "elapsed_seconds": 3,
                    "input_tokens": {"status": "unknown", "reason": "synthetic report"},
                    "output_tokens": {"status": "unknown", "reason": "synthetic report"},
                },
                "decision_statuses": case.get("expected_statuses"),
                "manual_semantic_review": "pending",
            }
            for case in plan["cases"]
        ],
    }


def test_finite_plan_preserves_fixture_hashes_and_does_not_execute(monkeypatch):
    plan = prepared(monkeypatch)
    assert plan["executed"] is False
    assert plan["maximum_runs"] == 6
    assert plan["whole_batch_caps"]["model_calls"] == 96
    assert all(
        len(source["sha256"]) == 64 for case in plan["cases"] for source in case.get("sources", [])
    )
    result = acceptance.check_report(plan, reported(plan))
    assert result["receipt_authenticity_verified"] is False
    assert result["semantic_truth_guaranteed"] is False


@pytest.mark.parametrize("count", [True, -1, 17])
def test_over_budget_or_noninteger_report_cannot_pass(monkeypatch, count):
    plan = prepared(monkeypatch)
    report = reported(plan)
    report["runs"][0]["usage"]["model_calls"] = count
    with pytest.raises(ValueError, match="OVER_BUDGET"):
        acceptance.check_report(plan, report)


def test_wrong_candidate_repeated_case_missing_real_model_and_unknown_tokens_fail(monkeypatch):
    plan = prepared(monkeypatch)
    with pytest.raises(ValueError, match="FIXED_CANDIDATE"):
        acceptance.manifest("b" * 40)
    for change in ("candidate", "duplicate", "mock", "tokens"):
        report = reported(plan)
        if change == "candidate":
            report["candidate_sha"] = "b" * 40
        elif change == "duplicate":
            report["runs"][1]["case_id"] = report["runs"][0]["case_id"]
        elif change == "mock":
            report["runs"][0]["real_model"] = False
        else:
            report["runs"][0]["usage"]["input_tokens"] = {"status": "unknown"}
        with pytest.raises(ValueError):
            acceptance.check_report(plan, report)

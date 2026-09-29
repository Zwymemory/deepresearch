#!/usr/bin/env python3
"""Prepare a finite acceptance manifest on a fixed HEAD; never execute providers.

Main runs the collected cases through the integrated A budget/authority entry point.
--check-report checks reported scope/count consistency, not the authenticity or truth of a report.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CAPS = {
    "decisions": 8,
    "model_calls": 16,
    "external_tool_calls": 16,
    "elapsed_seconds": 180,
    "input_tokens": 64000,
    "output_tokens": 16384,
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def manifest(candidate: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", candidate):
        raise ValueError("FULL_CANDIDATE_SHA_REQUIRED")
    head = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip()
    if candidate != head:
        raise ValueError("CHECKOUT_MUST_MATCH_FIXED_CANDIDATE")
    freeze = json.loads((ROOT / "contracts/agent/v0/freeze.json").read_text())
    for name, expected in freeze["file_sha256"].items():
        if digest(ROOT / name) != expected:
            raise ValueError("FROZEN_CONTRACT_DRIFT")
    fixture_file = ROOT / "testdata/agent-round1/evidence/scenarios.json"
    fixtures = json.loads(fixture_file.read_text())
    cases = []
    for case in fixtures["cases"]:
        cases.append(
            {
                "id": case["id"],
                "source_mode": "isolated synthetic transport / authorized controlled observation",
                "model_mode": "real model through A persistent shared budget",
                "claims": case["claims"],
                "sources": [
                    {**s, "sha256": digest(ROOT / s["path"])} for s in case["sources"]
                ],
                "expected_statuses": case["expected_statuses"],
                "maximum_supplement_rounds": 2,
                "run_caps": CAPS,
            }
        )
    if len(cases) != 4:
        raise ValueError("EXACTLY_FOUR_RESEARCH_CASES_REQUIRED")
    cases.extend(
        [
            {
                "id": "live_managed_kb_original",
                "source_mode": "existing allowlisted active KB; do not write the knowledge base",
                "model_mode": "none requested; source/receipt validation only",
                "selection": "A search receipt selects source_id; no URL/dataset permission in request body",
                "assertions": [
                    "exact live document/chunk identity",
                    "original chunk hash and completed receipt",
                    "publish validation requires an A SQL ledger permit",
                ],
                "run_caps": CAPS,
            },
            {
                "id": "live_public_https_original",
                "source_mode": "public HTTPS original selected by completed authorized search receipt",
                "model_mode": "none requested; source/receipt validation only",
                "suggested_query": "IANA IPv4 Special-Purpose Address Registry",
                "assertions": [
                    "production DNS/address/redirect policy remains enabled",
                    "native hostname certificate validation",
                    "snapshot/raw hash and completed receipt",
                ],
                "run_caps": CAPS,
            },
        ]
    )
    return {
        "protocol": "round1-finite-acceptance/1",
        "candidate_sha": candidate,
        "prepared_only": True,
        "executed": False,
        "fixture_manifest_sha256": digest(fixture_file),
        "repeat_per_case": 1,
        "maximum_runs": len(cases),
        "whole_batch_caps": {key: value * len(cases) for key, value in CAPS.items()},
        "budget_rule": "All attempts, verifier/generation/retry and nested publish reads share A SQL ledger; reserve before execution",
        "cost": {
            "status": "unknown",
            "reason": "No model or provider calls have been executed",
        },
        "requires": [
            "reviewed integrated candidate",
            "A EvidenceAuthority and new tool scopes wired",
            "isolated test instance and fixture adapter retaining production read policy",
            "captured A ledger/read/check/publication receipts",
            "manual semantic review of original paragraphs",
        ],
        "stop_rules": [
            "no new run or batch retry after error",
            "stop on exhausted budget or 180-second run deadline",
            "round 2 unresolved conflict stays contested/insufficient",
            "failed expected status is a failed case, never rewritten",
        ],
        "cases": cases,
    }


def check_report(plan: dict, report: dict) -> dict:
    if (
        report.get("candidate_sha") != plan["candidate_sha"]
        or report.get("executed") is not True
    ):
        raise ValueError("REPORT_CANDIDATE_OR_EXECUTION_MISMATCH")
    runs = report.get("runs")
    if not isinstance(runs, list) or len(runs) != len(plan["cases"]):
        raise ValueError("ONE_RUN_PER_CASE_REQUIRED")
    remaining = {case["id"]: case for case in plan["cases"]}
    for run in runs:
        identity = run.get("case_id")
        if not isinstance(identity, str) or identity not in remaining:
            raise ValueError("UNKNOWN_OR_REPEATED_CASE")
        case = remaining.pop(identity)
        if (
            type(run.get("attempts")) is not int
            or run["attempts"] != 1
            or run.get("receipts_collected") is not True
        ):
            raise ValueError("ATTEMPTS_OR_RECEIPTS_MISSING")
        counts = run.get("usage", {})
        for key in ("decisions", "model_calls", "external_tool_calls"):
            value = counts.get(key)
            if type(value) is not int or not 0 <= value <= case["run_caps"][key]:
                raise ValueError("CALL_COUNT_MISSING_OR_OVER_BUDGET")
        seconds = counts.get("elapsed_seconds")
        if (
            type(seconds) not in (int, float)
            or not math.isfinite(seconds)
            or not 0 <= seconds <= CAPS["elapsed_seconds"]
        ):
            raise ValueError("ELAPSED_TIME_MISSING_OR_OVER_BUDGET")
        for key in ("input_tokens", "output_tokens"):
            tokens = counts.get(key)
            if not isinstance(tokens, dict):
                raise ValueError("TOKEN_STATUS_REQUIRED")
            if tokens.get("status") == "known":
                value = tokens.get("value")
                if type(value) is not int or not 0 <= value <= CAPS[key]:
                    raise ValueError("TOKEN_COUNT_INVALID_OR_OVER_BUDGET")
            elif (
                tokens.get("status") != "unknown"
                or not isinstance(tokens.get("reason"), str)
                or not tokens["reason"].strip()
            ):
                raise ValueError("UNKNOWN_TOKEN_REASON_REQUIRED")
        if "expected_statuses" in case:
            if run.get("real_model") is not True or counts["model_calls"] == 0:
                raise ValueError("REAL_VERIFIER_CALL_MISSING")
            if run.get("decision_statuses") != case["expected_statuses"]:
                raise ValueError("EXPECTED_STATUS_MISMATCH")
        elif run.get("real_network") is not True or counts["external_tool_calls"] == 0:
            raise ValueError("REAL_SOURCE_READ_MISSING")
        if run.get("manual_semantic_review") not in ("passed", "failed", "pending"):
            raise ValueError("SEMANTIC_REVIEW_STATUS_REQUIRED")
    return {
        "status": "reported_counts_and_scope_consistent",
        "candidate_sha": plan["candidate_sha"],
        "runs": len(runs),
        "receipt_authenticity_verified": False,
        "semantic_truth_guaranteed": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check-report", type=Path)
    args = parser.parse_args()
    plan = manifest(args.candidate_sha)
    value = (
        check_report(plan, json.loads(args.check_report.read_text()))
        if args.check_report
        else plan
    )
    raw = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(raw)
    else:
        print(raw, end="")


if __name__ == "__main__":
    main()

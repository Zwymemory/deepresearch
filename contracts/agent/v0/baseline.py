"""Recompute the existing frozen Dify cohort baseline; never invoke a provider."""
from __future__ import annotations

import hashlib
import json
import math
import statistics
import subprocess
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASELINE_SHA = "60e0290c0670ad3f2f8da303b0b1513e056670c8"
SOURCE = "integrations/dify/evidence-v15-quality-live-2026-09-29.json"
TARGET = "testdata/agent-foundation/runtime/archived-dify-baseline.json"


def describe(values):
    ordered = sorted(values)
    return {"count": len(values), "sum": sum(values), "min": min(values), "max": max(values),
            "median": statistics.median(values), "p95_nearest_rank": ordered[math.ceil(.95 * len(values)) - 1]}


def build():
    def archived_bytes(path):
        return subprocess.check_output(["git", "show", f"{BASELINE_SHA}:{path}"], cwd=ROOT)

    capture_bytes = archived_bytes(SOURCE)
    capture = json.loads(capture_bytes)
    attempts = capture["attempts"]
    rows = []
    for attempt in attempts:
        assert len(attempt["native"]) == 1 and attempt["singleRemoteRun"]
        native = attempt["native"][0]
        nodes = native["llmNodes"]
        prompt = sum(node["usage"]["prompt_tokens"] for node in nodes)
        completion = sum(node["usage"]["completion_tokens"] for node in nodes)
        assert prompt + completion == native["totalTokens"]
        rows.append({"case_id": attempt["id"], "run_id": attempt["runId"],
            "expected_status": attempt["expectedStatus"], "actual_status": attempt["status"],
            "acceptance_passed": attempt["acceptancePassed"], "error_code": attempt["errorCode"],
            "model_calls": len(nodes), "tool_calls": native["toolCalls"],
            "prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": native["totalTokens"],
            "native_elapsed_seconds": native["elapsedSeconds"], "capture_latency_ms": attempt["latencyMs"],
            "all_finish_stop": all(node["finishReason"] == "stop" for node in nodes)})
    summary, conditions = capture["summary"], capture["conditions"]
    tokens = describe([row["total_tokens"] for row in rows])
    elapsed = describe([row["native_elapsed_seconds"] for row in rows])
    assert tokens["median"] == summary["totalTokensMedian"]
    assert math.isclose(elapsed["median"], summary["nativeElapsedSecondsMedian"])
    assert math.isclose(elapsed["p95_nearest_rank"], summary["nativeElapsedSecondsP95"])
    return {"schema_version": "0.1.0", "origin": "existing_saved_native_runs_not_new_sampling",
        "baseline_source_commit": BASELINE_SHA,
        "sources": [{"path": SOURCE, "sha256": hashlib.sha256(capture_bytes).hexdigest()},
            {"path": "integrations/dify/WEB_QUALITY_REPAIR_2026-09-28.md",
             "sha256": hashlib.sha256(archived_bytes("integrations/dify/WEB_QUALITY_REPAIR_2026-09-28.md")).hexdigest()}],
        "tested_implementation_sha": conditions["implementationSha"],
        "archived_dsl_sha256": conditions["dslSha256"], "archived_publication": conditions["publication"],
        "model": conditions["model"], "sample": {"total": len(rows), "original_consecutive": 10,
            "variants": 6, "frozen_cases_sha256": conditions["frozenCasesSha256"],
            "source_scope_sha256": conditions["frozenSourceScopeSha256"]},
        "quality": {"status_counts": dict(Counter(row["actual_status"] for row in rows)),
            "expected_terminal_count": sum(row["actual_status"] == row["expected_status"] for row in rows),
            "published_record_acceptance_count": sum(row["acceptance_passed"] for row in rows),
            "reviewed_candidate_claims": summary["manualClaimsReviewed"],
            "reviewed_published_claims": summary["manualPublishedClaimsReviewed"],
            "method": "finite saved own-source/context Codex record and cross-chat review; not independent human adjudication"},
        "usage": {"model_calls": sum(row["model_calls"] for row in rows),
            "tool_calls": sum(row["tool_calls"] for row in rows),
            "prompt_tokens": describe([row["prompt_tokens"] for row in rows]),
            "completion_tokens": describe([row["completion_tokens"] for row in rows]), "total_tokens": tokens,
            "cost": {"status": "unknown", "value": None, "reason": "Native plugin price fields unconfigured; no verified currency/rate/charged amount."}},
        "latency": {"native_elapsed_seconds": elapsed,
            "capture_latency_ms": describe([row["capture_latency_ms"] for row in rows]),
            "definition": "Native elapsed is workflow execution; capture also includes saved-result/source/idempotency readbacks."},
        "configured_limits": {key: conditions[key] for key in ["maxModelCalls", "completionBudget", "maxWorkers", "javaDeadlineSeconds", "retries"]},
        "rows": rows,
        "missing_or_unverified": ["Real currency/charged cost", "Model consumption of selected memory",
            "Not an independent held-out set: the same frozen cases were used across versioned repair cohorts",
            "Agent autonomy, multi-agent collaboration and project memory not implemented by this cohort",
            "Full-page source truth, current-version universality and independent human quality labels",
            "New fault injection, cold-cache reproducibility and restart memory behavior"],
        "historical_failures": {"preserved_paths": ["integrations/dify/model-output-failure-2026-09-28.json",
            "integrations/dify/evidence-v13-review-correction-2026-09-29.json",
            "integrations/dify/evidence-v14-quality-live-2026-09-29.json"],
            "v13_content_count_after_correction": 15, "v14_expected_terminal_count": 15,
            "note": "Earlier failures and all versioned cohorts remain; this extractor does not relabel, replace or rerun them."}}


if __name__ == "__main__":
    destination = ROOT / TARGET
    destination.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n")
    print(destination)

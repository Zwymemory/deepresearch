#!/usr/bin/env python3
"""Bounded autonomous real-model acceptance, with verified local build identity."""
import argparse
import copy
import fcntl
import json
import os
from pathlib import Path
import re
import time
from uuid import uuid4

from agent_live_common import LIMITS, TERMINAL, http_json, read_private, verify_runtime, write_private

CASES = {"knowledge-only", "web-only", "mixed", "version-conditions",
         "contradictory-material", "insufficient-evidence"}
MODEL_FAILURE_KINDS = {"TIMEOUT", "RATE_LIMIT", "SCHEMA", "PROVIDER"}
MODEL_ERROR_CLASSES = {
    "request_encoding", "transport_timeout", "transport_error", "http_auth",
    "http_rate_limit", "http_upstream", "http_other", "response_json",
    "response_shape", "output_truncated", "function_count", "function_name",
    "function_arguments", "function_oversized", "function_json", "function_shape",
    "schema_validation", "validator_rejected", "model_unclassified",
}
MODEL_ISSUE_FIELDS = {
    "action", "answer", "applicability", "claims", "conditions", "criterion_bindings",
    "criterion_id", "evidence_ids", "gaps", "investigation_id", "kind", "query",
    "reason", "source_id", "status", "subject", "task_id", "tool", "valid_at",
    "value", "version",
}


def validate_sources(sources):
    if not sources.get("ready") or not re.fullmatch(r"[a-f0-9]{40}", sources.get("final_sha", "")):
        raise ValueError("B sources require a ready exact committed version")
    cases = sources.get("cases", [])
    if len(cases) != 6 or {case.get("id") for case in cases} != CASES:
        raise ValueError("All six distinct live validation cases are required")
    for case in cases:
        if not isinstance(case.get("question"), str) or not 1 <= len(case["question"]) <= 4000:
            raise ValueError("Scenario question is missing or oversized")
        if not case.get("requested_tools") or not set(case["requested_tools"]) <= {"kb_search", "web_search"}:
            raise ValueError("Scenario needs an explicit allowed search channel")
        if case.get("source_classification") not in {"real-public", "real-public-curated", "synthetic",
                "real-public+synthetic", "real-public-and-real-public-curated"}:
            raise ValueError("Only reviewed public or synthetic scenarios are allowed")
        if any(k in case for k in ["decisions", "actions", "action_sequence", "model_responses"]):
            raise ValueError("Scenario may not prescribe the autonomous model action sequence")
    return {case["id"]: case for case in cases}


def admit(journal, scenario, build_sha, retry_of, fix_description):
    rows = journal.get("runs", [])
    if len(rows) >= 8:
        raise ValueError("Global eight-run validation limit reached")
    if any(row.get("status") not in TERMINAL and not row.get("request_failed") for row in rows):
        raise ValueError("An earlier run remains active or its request result is unknown; reconcile first")
    if len(rows) >= 2 and all(row.get("status") == "FAILED" for row in rows[-2:]):
        left, right = rows[-2:]
        if left.get("errorCode") and left["errorCode"] == right.get("errorCode"):
            raise ValueError("Repeated identical failure: stop real calls and diagnose the wiring")
    if retry_of:
        original = next((row for row in rows if row.get("runId") == retry_of), None)
        if not original or original["scenario"] != scenario:
            raise ValueError("Retry must refer to a recorded run of the same scenario")
        if len([row for row in rows if row.get("retry_of")]) >= 2:
            raise ValueError("Only two targeted wiring retries are allowed")
        if build_sha == original["build_sha"] or not fix_description or len(fix_description) < 10:
            raise ValueError("A retry needs a committed changed build and a diagnosed wiring fix")
    elif any(row["scenario"] == scenario and not row.get("retry_of") for row in rows):
        raise ValueError("Initial scenario was already attempted; preserve it and use an authorized retry")
    elif len([row for row in rows if not row.get("retry_of")]) >= 6:
        raise ValueError("All six initial runs are already reserved")


def scrub(value, secrets):
    if isinstance(value, dict):
        return {k: scrub(v, secrets) for k, v in value.items()
                if k.lower().replace("_", "") not in {
                    "token", "accesstoken", "authorization", "apikey", "password", "claimtoken"}}
    if isinstance(value, list):
        return [scrub(v, secrets) for v in value]
    if isinstance(value, str):
        for secret in secrets:
            if secret and len(secret) >= 12:
                value = value.replace(secret, "[redacted]")
        value = re.sub(r"(?:sk|tvly)-[A-Za-z0-9_-]{20,}", "[redacted]", value)
        value = re.sub(r"eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}", "[redacted]", value)
        value = re.sub(r"/(?:Users|home)/[^\s\"'<>]+", "[local-path]", value)
    return value


def capture_database(ready, credentials, run):
    import psycopg
    from psycopg.rows import dict_row
    queries = {
        "run": "SELECT run_id,status,stage,budget,usage,error_code,error_message,deadline_at,created_at,updated_at FROM agent_workflow_run WHERE run_id=%s",
        "operations": "SELECT operation_key,attempt,kind,purpose,status,input_reserved,output_reserved,actual_usage,safe_result,created_at,settled_at FROM agent_research_operation WHERE run_id=%s ORDER BY created_at,operation_key,attempt",
        "tasks": "SELECT task_id,objective,status,acceptance_criteria,dependencies,plan_version,task_json FROM agent_research_task WHERE run_id=%s ORDER BY task_id",
        "criteria": "SELECT task_id,criterion_id,criterion_text,expected_claim,expected_hash,investigation,last_call_id,dependency_snapshot FROM agent_research_criterion WHERE run_id=%s ORDER BY task_id,criterion_index",
        "checks": "SELECT check_id,task_id,call_id,investigation,dispute_round,parent_check_id,request_sha256,response_sha256,status,request,result FROM agent_evidence_check WHERE run_id=%s ORDER BY created_at,check_id",
        "read_receipts": "SELECT receipt_id,source_id,parent_receipt_id,status,error_code,record_json,metadata FROM agent_evidence_read_receipt WHERE run_id=%s ORDER BY created_at,receipt_id",
        "records": "SELECT record_type,record_id,version,payload_sha256,payload FROM agent_evidence_record WHERE run_id=%s ORDER BY record_type,record_id,version",
        "publications": "SELECT call_id,status,answer_hash,result,proof FROM agent_research_publication WHERE run_id=%s ORDER BY completed_at,call_id",
        "tool_receipts": "SELECT call_id,task_id,tool_name,status,safe_result,error_code,mcp_execution_status,mcp_safe_result FROM agent_workflow_tool_receipt WHERE run_id=%s ORDER BY call_id",
    }
    output = {}
    with psycopg.connect(host="127.0.0.1", port=ready["database_port"], dbname="deepresearch",
                          user="deepresearch", password=credentials["POSTGRES_PASSWORD"], row_factory=dict_row) as conn:
        conn.execute("SET TRANSACTION READ ONLY")
        for key, sql in queries.items():
            output[key] = conn.execute(sql, (run,)).fetchall()
    if len(output["run"]) != 1:
        raise ValueError("Isolated database does not contain the actual HTTP run")
    budget = output["run"][0]["budget"]
    expected = {"maxDecisionSteps": 8, "maxModelCalls": 16, "maxToolCalls": 16,
                "maxInputTokens": 64000, "maxOutputTokens": 16384}
    if any(budget.get(k) != v for k, v in expected.items()):
        raise ValueError("Actual persisted run budget differs from the authorized limits")
    return output


def measured_tokens(value):
    return value if type(value) is int and 0 <= value <= 2**63 - 1 else None


def safe_model_failure(value):
    if not isinstance(value, dict):
        return None
    kind, error_class, retryable = (
        value.get("failure_kind"), value.get("error_class"), value.get("retryable")
    )
    if (type(kind) is not str or kind not in MODEL_FAILURE_KINDS
            or type(error_class) is not str or error_class not in MODEL_ERROR_CLASSES
            or type(retryable) is not bool):
        return None
    safe = {"failure_kind": kind, "error_class": error_class, "retryable": retryable}
    status = value.get("status_code")
    if type(status) is int and 100 <= status <= 599:
        safe["status_code"] = status
    count = value.get("tool_call_count")
    if type(count) is int and 0 <= count <= 100:
        safe["tool_call_count"] = count
    paths = value.get("validation_issue_codes")
    if isinstance(paths, list):
        safe_paths = []
        for path in paths[:4]:
            if path == "unknown_field":
                safe_paths.append(path)
            elif (type(path) is str and 1 <= len(path.split(".")) <= 4
                  and all(part in MODEL_ISSUE_FIELDS for part in path.split("."))):
                safe_paths.append(path)
        if safe_paths:
            safe["validation_issue_codes"] = safe_paths
    return safe


def safe_model_usage(value):
    if not isinstance(value, dict):
        return {}
    safe = {}
    for name in ("input_tokens", "output_tokens"):
        amount = measured_tokens(value.get(name))
        if amount is not None:
            safe[name] = amount
    failure = safe_model_failure(value.get("model_failure"))
    if failure is not None:
        safe["model_failure"] = failure
    return safe


def model_receipts(database):
    receipts = []
    for row in database["operations"]:
        if row.get("kind") != "MODEL":
            continue
        usage = safe_model_usage(row.get("actual_usage"))
        status = row.get("status")
        purpose = row.get("purpose")
        receipt = {
            "model_sequence": len(receipts) + 1,
            "attempt": row.get("attempt") if type(row.get("attempt")) is int else None,
            "purpose": purpose if type(purpose) is str and purpose in {"DECISION", "CHECK"} else None,
            "status": status if type(status) is str and status in {"SETTLED", "UNKNOWN", "RESERVED"} else "UNRECOGNIZED",
            "result_usable": status == "SETTLED",
            "input_tokens": usage.get("input_tokens"),
            "output_tokens": usage.get("output_tokens"),
            "input_usage_known": "input_tokens" in usage,
            "output_usage_known": "output_tokens" in usage,
        }
        if "model_failure" in usage:
            receipt["failure"] = usage["model_failure"]
        receipts.append(receipt)
    return receipts


def audit_database(database):
    """Keep old audit evidence, but never export unclassified model failure payloads."""
    safe = copy.deepcopy(database)
    for row in safe["operations"]:
        if row.get("kind") != "MODEL":
            continue
        if row.get("status") != "SETTLED":
            row["safe_result"] = None
            row["actual_usage"] = safe_model_usage(row.get("actual_usage"))
        elif isinstance(row.get("actual_usage"), dict):
            row["actual_usage"].pop("model_failure", None)
    return safe


def usage_summary(database):
    models = [row for row in database["operations"] if row["kind"] == "MODEL"]
    tools = [row for row in database["operations"] if row["kind"] == "TOOL"]
    actual = [safe_model_usage(row.get("actual_usage")) for row in models]
    return {"model_admissions": len(models), "tool_admissions": len(tools),
            "decision_admissions": len({row["operation_key"] for row in models if row["purpose"] == "DECISION"}),
            "model_settled": sum(row["status"] == "SETTLED" for row in models),
            "model_unknown_or_inflight": sum(row["status"] != "SETTLED" for row in models),
            "model_result_unavailable": sum(row["status"] != "SETTLED" for row in models),
            "model_unknown_receipts": sum(row["status"] == "UNKNOWN" for row in models),
            "model_inflight_receipts": sum(row["status"] == "RESERVED" for row in models),
            "actual_input_tokens": sum(row.get("input_tokens") or 0 for row in actual),
            "actual_output_tokens": sum(row.get("output_tokens") or 0 for row in actual),
            "input_usage_missing": sum(row.get("input_tokens") is None for row in actual),
            "output_usage_missing": sum(row.get("output_tokens") is None for row in actual),
            "actual_bill_or_cost": None, "cost_note": "unknown; admission estimates are not provider bills"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--runtime-ready", type=Path, required=True)
    parser.add_argument("--sources-ready", type=Path, required=True)
    parser.add_argument("--scenario", choices=sorted(CASES))
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--retry-of")
    parser.add_argument("--fix-description")
    args = parser.parse_args()
    ready = read_private(args.runtime_ready)
    sources = json.loads(args.sources_ready.read_text())
    cases = validate_sources(sources)
    plan = {"cases": list(cases), "maximum_runs": 8, "maximum_initial_runs": 6,
            "maximum_targeted_fix_retries": 2, "per_run": LIMITS,
            "model_execution": "not_started", "build_sha": ready["build_sha"],
            "peer_sha": sources["final_sha"], "autonomous_decisions": True}
    if not args.execute:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return
    if not args.scenario:
        raise ValueError("Choose one case; research runs are deliberately serial")
    if ready.get("tested_peer_sha") != sources["final_sha"] or not ready.get("registry_configured"):
        raise ValueError("B exact source manifest has not been registered in this isolated environment")
    credentials = read_private(ready["credential_access"]["path"])
    token = read_private(ready["token_access"]["path"])["token"]
    secrets = [v for k, v in credentials.items() if any(part in k for part in ["KEY", "SECRET", "PASSWORD"])] + [token]
    args.state_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(args.state_dir, 0o700)
    with (args.state_dir / "run-journal.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        journal_path = args.state_dir / "run-journal.json"
        journal = read_private(journal_path) if journal_path.exists() else {"runs": []}
        admit(journal, args.scenario, ready["build_sha"], args.retry_of, args.fix_description)
        identity = verify_runtime(ready, token)
        case = cases[args.scenario]
        row = {"scenario": args.scenario, "build_sha": ready["build_sha"], "peer_sha": sources["final_sha"],
               "retry_of": args.retry_of, "fix_description": args.fix_description,
               "idempotency_key": "live-" + uuid4().hex, "status": "REQUEST_RESERVED",
               "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        journal["runs"].append(row)
        write_private(journal_path, journal)
        try:
            accepted = http_json(ready["app_base_url"], "/api/research/agents", token, body={
                "question": case["question"], "requestedTools": case["requested_tools"]}, key=row["idempotency_key"])
            row.update({"runId": accepted["runId"], "status": accepted["status"]})
            write_private(journal_path, journal)
            deadline = time.monotonic() + 195
            while True:
                view = http_json(ready["app_base_url"], "/api/research/workflows/" + row["runId"], token)
                if view["status"] in TERMINAL:
                    break
                if time.monotonic() >= deadline:
                    http_json(ready["app_base_url"], "/api/research/workflows/" + row["runId"] + "/cancel", token, body={})
                    raise ValueError("Run exceeded acceptance time window; cancellation requested, reconcile before continuing")
                time.sleep(1)
            row.update({"status": view["status"], "errorCode": view.get("errorCode"),
                        "ended_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
            write_private(journal_path, journal)
            database = capture_database(ready, credentials, row["runId"])
            usage = usage_summary(database)
            diagnostics = model_receipts(database)
            if usage["decision_admissions"] > 8 or usage["model_admissions"] > 16 or usage["tool_admissions"] > 16:
                raise ValueError("Actual operation ledger exceeded the authorized run limits")
            audit = scrub({"case": case, "run": row, "build_verification": identity,
                           "view": view, "database": audit_database(database), "usage": usage,
                           "model_receipts": diagnostics,
                           "model": ready["model"], "source_classification": case["source_classification"],
                           "manual_review_status": "pending", "fixtures": False}, secrets)
            path = args.state_dir / (row["runId"] + ".json")
            write_private(path, audit)
            row.update({"audit_path": str(path), "usage": usage})
            write_private(journal_path, journal)
            print(json.dumps({"scenario": args.scenario, "runId": row["runId"], "status": row["status"],
                              "errorCode": row["errorCode"], "usage": usage,
                              "model_receipts": diagnostics}, ensure_ascii=False), flush=True)
        except Exception as error:
            # A lost POST response can still have started a run: never mark it safe to rerun.
            row["validation_error_type"] = type(error).__name__
            write_private(journal_path, journal)
            raise


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        raise SystemExit(type(error).__name__ + ": acceptance stopped; inspect the protected run journal") from None

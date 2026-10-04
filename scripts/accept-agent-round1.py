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

from agent_live_common import LIMITS, TERMINAL, file_sha, http_json, read_private, verify_runtime, write_private
from agent_json_diagnostics import FINISH_REASONS, safe_json_diagnostic
import agent_retest_batch as retest
from agent_acceptance_v22 import CAPTURE_LIMITS, CAPTURE_VERSION, finalize_audit, validate_saved_audit

CASES = {"knowledge-only", "web-only", "mixed", "version-conditions",
         "contradictory-material", "insufficient-evidence"}
MODEL_FAILURE_KINDS = {"TIMEOUT", "RATE_LIMIT", "SCHEMA", "PROVIDER", "INTERNAL"}
MODEL_ERROR_CLASSES = {
    "request_encoding", "transport_timeout", "transport_error", "identity_validation", "http_auth",
    "http_rate_limit", "http_upstream", "http_other", "response_json",
    "response_shape", "output_truncated", "function_count", "function_name",
    "function_arguments", "function_oversized", "function_json", "function_shape",
    "capability_config", "choice_count", "unexpected_tools", "result_content",
    "result_oversized", "result_json", "result_shape",
    "schema_validation", "validator_rejected", "model_unclassified",
    "requirement_validation", "application_internal",
}
MODEL_ISSUE_FIELDS = {
    "action", "answer", "applicability", "claims", "conditions", "criterion_bindings",
    "criterion_id", "evidence_ids", "gaps", "investigation_id", "kind", "query",
    "reason", "source_id", "status", "subject", "task_id", "tool", "valid_at",
    "value", "version",
    "requirements", "requirement_bindings", "question_spans", "start", "end", "text",
    "requirement_id",
    "segment_ids", "planner_contract", "continuation_contract", "requirements_ref",
}
# Keep offline receipts readable without importing the running workflow package.
# A focused parity regression checks these fixed vocabularies against production.
MODEL_VALIDATION_STAGES = {
    "result_schema", "domain_validation", "planning_decision", "planning_requirements",
}
MODEL_REQUIREMENT_CODES = {
    "REQUIREMENTS_CHANGED", "REQUIREMENTS_MISSING_OR_LIMIT", "REQUIREMENT_ANCHOR_INVALID",
    "REQUIREMENT_BINDING_CHANGED", "REQUIREMENT_BINDING_DUPLICATE", "REQUIREMENT_BINDING_INVALID",
    "REQUIREMENT_CHECK_BINDING_INVALID", "REQUIREMENT_CHECK_CLAIM_REUSED",
    "REQUIREMENT_CHECK_STATE_INVALID", "REQUIREMENT_CLAIM_SCOPE_CHANGED",
    "REQUIREMENT_CRITERION_DUPLICATE", "REQUIREMENT_CRITERION_INVALID",
    "REQUIREMENT_CRITERION_MISSING", "REQUIREMENT_CRITERION_REUSED", "REQUIREMENT_DUPLICATE",
    "REQUIREMENT_DUPLICATE_SPAN", "REQUIREMENT_EXPECTED_CLAIM_INVALID", "REQUIREMENT_IDENTITY_CHANGED",
    "REQUIREMENT_INVALID", "REQUIREMENT_MANIFEST_INVALID", "REQUIREMENT_ORIGINAL_BINDING_CHANGED",
    "REQUIREMENT_QUESTION_INVALID", "REQUIREMENT_QUESTION_REGION_UNASSIGNED", "REQUIREMENT_RUN_INVALID",
    "REQUIREMENT_TASK_INVALID", "REQUIREMENT_TASK_LIMIT", "REQUIREMENT_UNKNOWN",
    "REQUIREMENT_QUESTION_LIMIT", "REQUIREMENT_PLANNER_VERSION_INVALID",
    "REQUIREMENT_SEGMENT_INVALID", "REQUIREMENT_SEGMENT_DUPLICATE",
    "REQUIREMENT_SEGMENT_UNKNOWN", "REQUIREMENT_SEGMENT_BINDING_INVALID",
    "REQUIREMENT_DECLARATION_LIMIT",
}
MODEL_CHECK_CODES = {
    "CHECK_TOO_LARGE", "CHECK_JSON_INVALID", "CHECK_RESPONSE_INVALID",
    "CHECK_REQUEST_BINDING_INVALID", "CHECK_REQUEST_INVALID", "CHECK_SNAPSHOT_CHANGED",
    "CHECK_QUOTE_BINDING_INVALID", "CHECK_QUOTE_INVALID", "CHECK_QUOTE_CONTEXT_INCOMPLETE",
    "CHECK_DUPLICATE_JSON_KEY", "CHECK_NONFINITE_JSON", "CHECK_CLAIM_BINDING_INVALID",
    "CHECK_EVIDENCE_BINDING_INVALID", "CHECK_ACTION_INVALID",
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
    if journal.get("authorized_batches"):
        raise ValueError("Legacy allowance closed; use explicit pinned batch admission")
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


SERVER_IDENTIFIER = re.compile(
    r"(?:task-req-[a-f0-9]{40}|task-[1-9][0-9]*-[1-9][0-9]*|"
    r"criterion-[a-f0-9]{48}|source-[a-f0-9]{64}|requirement-[a-f0-9]{48})"
)


def audit_identifiers(database):
    """Narrow tokens from authoritative capture, never arbitrary model string fields."""
    candidates = [row.get("task_id") for row in database.get("tasks", [])]
    candidates += [row.get("criterion_id") for row in database.get("criteria", [])]
    candidates += [row.get("source_id") for row in database.get("read_receipts", [])]
    return frozenset(value for value in candidates if type(value) is str
                     and SERVER_IDENTIFIER.fullmatch(value))


def scrub(value, secrets, *, identifiers=frozenset()):
    if isinstance(value, dict):
        return {k: scrub(v, secrets, identifiers=identifiers) for k, v in value.items()
                if k.lower().replace("_", "") not in {
                    "token", "accesstoken", "authorization", "apikey", "password", "claimtoken"}}
    if isinstance(value, list):
        return [scrub(v, secrets, identifiers=identifiers) for v in value]
    if isinstance(value, str):
        # Explicit secrets always win, even when short or equal to a genuine identity.
        explicit = sorted({x for x in secrets if type(x) is str and x}, key=len, reverse=True)
        if explicit:
            value = re.sub("|".join(re.escape(x) for x in explicit), "[redacted]", value)

        def redact_key(match):
            start, end = match.span()
            while start and (value[start - 1].isalnum() or value[start - 1] in "_-"):
                start -= 1
            while end < len(value) and (value[end].isalnum() or value[end] in "_-"):
                end += 1
            token = value[start:end]
            if token in identifiers and SERVER_IDENTIFIER.fullmatch(token):
                return match.group(0)
            return "[redacted]"

        value = re.sub(r"(?:sk|tvly)-[A-Za-z0-9_-]{20,}", redact_key, value)
        value = re.sub(r"eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}",
                       "[redacted]", value)
        value = re.sub(r"/(?:Users|home)/[^\s\"'<>]+", "[local-path]", value)
    return value


def capture_database(ready, credentials, run):
    import psycopg
    from psycopg.rows import dict_row
    queries = {
        "run": "SELECT run_id,question,status,stage,budget,usage,error_code,error_message,deadline_at,created_at,updated_at FROM agent_workflow_run WHERE run_id=%s",
        "operations": "SELECT operation_key,attempt,kind,purpose,status,input_reserved,output_reserved,actual_usage,safe_result,created_at,settled_at FROM agent_research_operation WHERE run_id=%s ORDER BY created_at,operation_key,attempt",
        "tasks": "SELECT task_id,objective,status,acceptance_criteria,dependencies,plan_version,task_json FROM agent_research_task WHERE run_id=%s ORDER BY task_id",
        "criteria": "SELECT task_id,criterion_id,criterion_index,criterion_text,expected_claim,expected_hash,investigation,last_call_id,dependency_snapshot FROM agent_research_criterion WHERE run_id=%s ORDER BY task_id,criterion_index",
        "checks": "SELECT check_id,task_id,call_id,investigation,dispute_round,parent_check_id,request_sha256,response_sha256,status,request,result FROM agent_evidence_check WHERE run_id=%s ORDER BY created_at,check_id",
        "read_receipts": "SELECT receipt_id,source_id,parent_receipt_id,status,error_code,record_json,metadata FROM agent_evidence_read_receipt WHERE run_id=%s ORDER BY created_at,receipt_id",
        "records": "SELECT record_type,record_id,version,payload_sha256,payload FROM agent_evidence_record WHERE run_id=%s ORDER BY record_type,record_id,version",
        "publications": "SELECT call_id,status,answer_hash,result,proof FROM agent_research_publication WHERE run_id=%s ORDER BY completed_at,call_id",
        "tool_receipts": "SELECT call_id,task_id,tool_name,status,safe_result,error_code,mcp_execution_status,mcp_safe_result FROM agent_workflow_tool_receipt WHERE run_id=%s ORDER BY call_id",
        "requirements": "SELECT run_id,manifest,declaration_key,declaration_attempt FROM agent_research_requirements WHERE run_id=%s ORDER BY run_id",
        "requirement_bindings": "SELECT run_id,requirement_id,task_id,criterion_id FROM agent_research_requirement_binding WHERE run_id=%s ORDER BY requirement_id",
        "investigation_progress": "SELECT investigation,current_call_id FROM agent_research_investigation_progress WHERE run_id=%s ORDER BY investigation",
    }
    limits = CAPTURE_LIMITS
    output = {}
    counts = {}
    key = None
    try:
        with psycopg.connect(host="127.0.0.1", port=ready["database_port"], dbname="deepresearch",
                              user="deepresearch", password=credentials["POSTGRES_PASSWORD"], row_factory=dict_row) as conn:
            conn.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
            conn.execute("SET LOCAL statement_timeout='10s'")
            counts = {}
            total_bytes = 0
            for key, sql in queries.items():
                measured = conn.execute("SELECT count(*) AS n,coalesce(sum(octet_length(row_to_json(x)::text)),0) AS bytes,coalesce(max(octet_length(row_to_json(x)::text)),0) AS largest FROM (" + sql + ") x", (run,)).fetchone()
                counts[key] = {"rows": measured["n"], "bytes": measured["bytes"]}
                total_bytes += measured["bytes"]
                if (measured["n"] > limits[key] or measured["largest"] > 1048576
                        or total_bytes > 8388608):
                    return {"capture": {"contract_version": CAPTURE_VERSION, "run_id": run,
                        "status": "incomplete", "error_code": "CAPTURE_BOUND_EXCEEDED",
                        "table": key, "counts": counts, "row_limits": limits,
                        "max_row_bytes": 1048576, "max_total_bytes": 8388608},
                        "operations": [], "records": []}
                output[key] = conn.execute(sql + " LIMIT " + str(limits[key] + 1), (run,)).fetchall()
                if len(output[key]) != measured["n"]:
                    raise ValueError("Run capture changed within the read-only snapshot")
    except psycopg.Error as error:
        return {"capture": {"contract_version": CAPTURE_VERSION, "run_id": run,
            "status": "incomplete", "error_code": "CAPTURE_SCHEMA_UNAVAILABLE"
            if error.sqlstate in {"42P01", "42703"} else "CAPTURE_SQL_UNAVAILABLE",
            "table": key, "counts": counts, "row_limits": limits,
            "max_row_bytes": 1048576, "max_total_bytes": 8388608},
            "operations": [], "records": []}
    if len(output["run"]) != 1:
        raise ValueError("Isolated database does not contain the actual HTTP run")
    budget = output["run"][0]["budget"]
    expected = {"maxDecisionSteps": 8, "maxModelCalls": 16, "maxToolCalls": 16,
                "maxInputTokens": 64000, "maxOutputTokens": 16384}
    if any(budget.get(k) != v for k, v in expected.items()):
        raise ValueError("Actual persisted run budget differs from the authorized limits")
    output["capture"] = {"contract_version": CAPTURE_VERSION, "run_id": run,
        "status": "complete", "counts": counts, "row_limits": limits,
        "max_row_bytes": 1048576, "max_total_bytes": 8388608}
    return output


def persisted_request_binding(database, run_id, question, view_run_id):
    """Bind the submitted scenario to the original request saved by the service."""
    run_rows = database.get("run", [])
    actual = run_rows[0] if len(run_rows) == 1 else {}
    return {"expected_run_id": run_id, "view_run_id": view_run_id,
            "actual_run_id": actual.get("run_id"),
            "expected_question": question, "actual_question": actual.get("question"),
            "matches": actual.get("run_id") == run_id == view_run_id
            and actual.get("question") == question}


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
    stage, domain_code = value.get("validation_stage"), value.get("domain_error_code")
    if type(stage) is str and stage in MODEL_VALIDATION_STAGES:
        safe["validation_stage"] = stage
    if type(domain_code) is str and domain_code in MODEL_REQUIREMENT_CODES | MODEL_CHECK_CODES:
        safe["domain_error_code"] = domain_code
    if type(domain_code) is str and domain_code in MODEL_REQUIREMENT_CODES:
        diagnostic = safe_segment_diagnostic(value.get("question_segments"))
        if diagnostic is not None:
            safe["question_segments"] = diagnostic
    diagnostic = safe_json_diagnostic(value.get("json_diagnostic"))
    if diagnostic is not None:
        safe["json_diagnostic"] = diagnostic
    finish = value.get("finish_reason")
    if type(finish) is str and finish in FINISH_REASONS:
        safe["finish_reason"] = finish
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
    identity = safe_identity_failure(value.get("identity"))
    if identity is not None:
        safe["identity"] = identity
    return safe


def safe_segment_diagnostic(value):
    # Standalone exporter: fixed parity-tested vocabulary, no workflow runtime import.
    if type(value) is not dict or set(value) != {
        "mapping_version", "mapping_sha256", "missing_count", "missing_ranges"
    }:
        return None
    digest, count, ranges = value["mapping_sha256"], value["missing_count"], value["missing_ranges"]
    if (value["mapping_version"] != "agent-question-segments/1"
            or type(digest) is not str or len(digest) != 64
            or any(c not in "0123456789abcdef" for c in digest)
            or type(count) is not int or not 1 <= count <= 16
            or type(ranges) is not list or len(ranges) != count):
        return None
    previous = -1
    for span in ranges:
        if (type(span) is not dict or set(span) != {"start", "end"}
                or type(span["start"]) is not int or type(span["end"]) is not int
                or not 0 <= span["start"] < span["end"] <= 4000
                or span["start"] < previous):
            return None
        previous = span["end"]
    return {"mapping_version": value["mapping_version"], "mapping_sha256": digest,
            "missing_count": count, "missing_ranges": [dict(span) for span in ranges]}


def safe_identity_failure(value):
    """Export only the fixed identity-policy schema; arbitrary identifier text is excluded."""
    reasons = {"accepted_canonical", "accepted_legacy_route", "endpoint_mismatch",
               "request_model_mismatch", "request_model_unsupported", "request_method_mismatch",
               "request_json_invalid", "request_oversized", "response_oversized",
               "response_json_invalid", "response_shape", "response_model_missing",
               "response_model_type_invalid", "response_model_oversized", "response_model_unsafe",
               "response_model_mismatch", "response_model_unrecognized", "response_redirect"}
    if (type(value) is not dict or value.get("policy_version") != "deepseek-flash-2026-09-10/1"
            or type(value.get("reason")) is not str or value["reason"] not in reasons
            or value.get("decision") != ("accept" if value["reason"].startswith("accepted_") else "reject")):
        return None
    safe = {key: value[key] for key in ("policy_version", "decision", "reason")}
    for key in ("endpoint_matches", "response_endpoint_matches", "request_model_matches",
                "requested_model_present", "response_model_present"):
        if type(value.get(key)) is bool:
            safe[key] = value[key]
    enums = {"request_model_kind": {"canonical", "retired_alias", "unsupported"}}
    for prefix in ("requested_model", "response_model"):
        enums[prefix + "_type"] = {"missing", "null", "string", "boolean", "number", "array", "object"}
        identifier = value.get(prefix + "_identifier")
        if identifier is None or (type(identifier) is str and identifier in {
                "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4-pro"}):
            safe[prefix + "_identifier"] = identifier
        length = value.get(prefix + "_length")
        if type(length) is int and 0 <= length <= 524288:
            safe[prefix + "_length"] = length
        digest = value.get(prefix + "_sha256")
        if type(digest) is str and re.fullmatch(r"[a-f0-9]{64}", digest):
            safe[prefix + "_sha256"] = digest
    for key, allowed in enums.items():
        if type(value.get(key)) is str and value[key] in allowed:
            safe[key] = value[key]
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


def batch_prerequisites(ready, source_path, review_path, state):
    if not ready.get("ready") or not ready.get("registry_configured"):
        raise ValueError("Exact isolated runtime and source registry must be ready")
    if Path(ready.get("historical_state_dir", "")).resolve() != state.resolve():
        raise ValueError("Use the original protected campaign state directory")
    ci = ready.get("ci") or {}
    required_jobs = {"showcase-offline", "java-unit", "python-unit", "integration",
                     "workflow-postgres-integration", "secret-scan"}
    jobs = ci.get("jobs", [])
    if (ci.get("head_sha") != ready["build_sha"] or ci.get("conclusion") != "success"
            or {job.get("name") for job in jobs} != required_jobs
            or any(job.get("conclusion") != "success" for job in jobs)):
        raise ValueError("Exact-candidate remote CI prerequisites missing or failed")
    review = json.loads(review_path.read_text())
    if (review.get("ready") is not True or
            review.get("candidate_binding", {}).get("approved_for_live") is not True or
            review.get("candidate_binding", {}).get("observed_candidate_sha") != ready["build_sha"]):
        raise ValueError("B has not approved the exact candidate for live execution")
    if (review.get("batch_id") not in retest.BATCHES
            or review.get("batch_id") != ready.get("batch_id")
            or ready.get("batch_id") == retest.POST_IDENTITY_BATCH
            and ready.get("model_identity", {}).get("name") != "deepseek-flash"):
        raise ValueError("Exact selected batch and canonical model readiness required")
    if ready.get("batch_id") in retest.JSON_WEB_BATCHES:
        model = ready.get("model_identity", {})
        binding = review.get("candidate_binding", {})
        if (model.get("name") != "deepseek-flash"
                or model.get("endpoint") != "https://api.deepseek.com"
                or model.get("result_transport") != "deepseek_json_object"
                or model.get("transport_contract_version") != "agent-result-wire/1"
                or binding.get("result_transport") != model.get("result_transport")
                or binding.get("transport_contract_version") != model.get("transport_contract_version")):
            raise ValueError("Actual runtime and B-approved JSON transport binding required")
    if ready.get("batch_id") in {retest.SEGMENTS_WEB_BATCH, retest.JSON_DIAGNOSTICS_WEB_BATCH}:
        expected = {"planner_contract": "agent-planning-segments/2",
                    "question_mapping_version": "agent-question-segments/1",
                    "planner_settlement_contract": "agent-planner-settlement/1"}
        binding = review.get("candidate_binding", {})
        if ready.get("planner_identity") != expected or any(
                binding.get(key) != value for key, value in expected.items()):
            raise ValueError("Actual runtime and independent v2 planner approval required")
    if ready.get("batch_id") == retest.JSON_DIAGNOSTICS_WEB_BATCH and (
            ready.get("json_diagnostic_identity", {}).get("version") != "agent-json-diagnostic/1"
            or review.get("candidate_binding", {}).get("json_diagnostic_version")
            != "agent-json-diagnostic/1"):
        raise ValueError("Actual strict JSON diagnostic implementation approval required")
    manifest = Path(ready["scenario_manifest_path"])
    if review.get("source_manifest_sha256") != file_sha(manifest) or ready.get("scenario_manifest_sha256") != file_sha(manifest):
        raise ValueError("B did not approve this exact source manifest")
    scenario_manifest = json.loads(manifest.read_text())
    source = json.loads(source_path.read_text())
    if source["cases"] != scenario_manifest["cases"]:
        raise ValueError("Runtime scenario questions differ from the reviewed manifest")
    return review


def publish_batch_manifest(state, ready, batch_id=retest.BATCH):
    with retest.journal_lock(state) as journal:
        batch, rows = retest.validate_history(journal, batch_id)
        manifest = {"batch_id": batch_id, "candidate_sha": ready["build_sha"],
                    "source_manifest_sha256": batch["source_manifest_sha256"],
                    "historical_journal_sha256": batch["original_journal_sha256"],
                    "batch_status": batch["status"], "stop_reason": batch["stop_reason"],
                    "maximum_new_runs": batch["maximum_runs"], "maximum_research_reruns": 0,
                    "runs": [{"scenario": row["scenario"], "run_id": row.get("runId"),
                              "build_sha": row["build_sha"], "status": row["status"],
                              "error_code": row.get("errorCode"), "audit_path": row.get("audit_path"),
                              "audit_sha256": row.get("audit_sha256"), "source_hashes": row.get("source_hashes", {}),
                              "usage": row.get("usage"), "validation_error_type": row.get("validation_error_type")}
                             for row in rows]}
        write_private(ready["run_manifest_path"], manifest)


def execute_batch(args, ready, sources, cases):
    if args.retry_of or args.fix_description or not args.review_state:
        raise ValueError("New batch allows no research rerun and requires independent review state")
    review = batch_prerequisites(ready, args.sources_ready, args.review_state, args.state_dir)
    credentials = read_private(ready["credential_access"]["path"])
    token = read_private(ready["token_access"]["path"])["token"]
    identity = verify_runtime(ready, token)
    if identity.get("model_identity") != ready.get("model_identity"):
        raise ValueError("Actual runtime research model mismatch")
    secrets = [v for k, v in credentials.items() if any(part in k for part in ["KEY", "SECRET", "PASSWORD"])] + [token]
    before_model_calls = len(read_private(ready["model_identity_receipts_path"])["receipts"])
    row = retest.reserve(args.state_dir, args.scenario, ready["build_sha"], sources["final_sha"], review, args.batch)
    # Reservation is durable before the only POST. Exceptions consume the slot and stop.
    try:
        publish_batch_manifest(args.state_dir, ready, args.batch)
        case = cases[args.scenario]
        accepted = http_json(ready["app_base_url"], "/api/research/agents", token, body={
            "question": case["question"], "requestedTools": case["requested_tools"]}, key=row["idempotency_key"])
        row.update(runId=accepted["runId"], status=accepted["status"])
        retest.update(args.state_dir, row, args.batch)
        deadline = time.monotonic() + 195
        while True:
            view = http_json(ready["app_base_url"], "/api/research/workflows/" + row["runId"], token)
            if view["status"] in TERMINAL:
                break
            if time.monotonic() >= deadline:
                http_json(ready["app_base_url"], "/api/research/workflows/" + row["runId"] + "/cancel", token, body={})
                raise ValueError("Run outcome uncertain after cancellation; stop batch")
            time.sleep(1)
        row.update(status=view["status"], errorCode=view.get("errorCode"),
                   ended_at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
        retest.update(args.state_dir, row, args.batch)
        database = capture_database(ready, credentials, row["runId"])
        identifiers = audit_identifiers(database)
        request_binding = persisted_request_binding(database, row["runId"], case["question"], view.get("runId"))
        usage = usage_summary(database)
        diagnostics = model_receipts(database)
        actual_model_identity = read_private(ready["model_identity_receipts_path"])["receipts"][before_model_calls:]
        if (usage["decision_admissions"] > 8 or usage["model_admissions"] > 16 or usage["tool_admissions"] > 16
                or usage["actual_input_tokens"] > 64000 or usage["actual_output_tokens"] > 16384
                or usage["model_result_unavailable"]):
            row["validation_error_type"] = "UnavailableModelResultOrBudgetViolation"
        if not actual_model_identity or any(call["request_model_matches"] is not True or
                call["identity_matches"] is not True for call in actual_model_identity):
            row["validation_error_type"] = "ActualModelIdentityUnavailableOrMismatch"
        if args.batch in retest.JSON_WEB_BATCHES and any(
                call.get("wire", {}).get("result_transport") != "deepseek_json_object"
                or call.get("wire", {}).get("transport_matches") is not True
                or call.get("wire", {}).get("configured_result_transport") != "deepseek_json_object"
                for call in actual_model_identity):
            row["validation_error_type"] = "ActualResultTransportMismatch"
        if not request_binding["matches"]:
            row["validation_error_type"] = "PersistedRequestMismatch"
        audit = finalize_audit(scrub({"batch_id": args.batch, "case": case, "run": row, "build_verification": identity,
                       "view": view, "database": audit_database(database),
                       "persisted_request_binding": request_binding, "usage": usage,
                       "model_receipts": diagnostics, "model": ready["model"],
                       "actual_model_identity_receipts": actual_model_identity,
                       "source_classification": case["source_classification"],
                       "manual_review_status": "pending", "fixtures": False}, secrets,
                                           identifiers=identifiers))
        native = validate_saved_audit(audit)
        if (native["status"] == "invalid" or row["status"] == "SUCCEEDED"
                and not native["eligible_for_complete_review"]):
            row.setdefault("validation_error_type", "NativeRequirementAuditIncomplete")
            audit["run"] = scrub(row, secrets)
        path = args.state_dir / (row["runId"] + ".json")
        if path.exists():
            raise ValueError("Existing run audit cannot be overwritten")
        write_private(path, audit)
        hashes = {record["payload"]["evidence_id"]: record["payload"]["snapshot"]["sha256"]
                  for record in database["records"] if record["record_type"] == "Evidence"}
        row.update(audit_path=str(path.resolve()), audit_sha256=file_sha(path), usage=usage, source_hashes=hashes)
        retest.update(args.state_dir, row, args.batch)
        publish_batch_manifest(args.state_dir, ready, args.batch)
        if not request_binding["matches"]:
            raise ValueError("Persisted research request differs from the submitted scenario")
        print(json.dumps({"batch_id": args.batch, "scenario": row["scenario"], "run_id": row["runId"],
                          "status": row["status"], "error_code": row.get("errorCode"),
                          "usage": usage, "model_receipts": diagnostics}, ensure_ascii=False), flush=True)
    except Exception as error:
        row.setdefault("validation_error_type", type(error).__name__)
        retest.update(args.state_dir, row, args.batch)
        publish_batch_manifest(args.state_dir, ready, args.batch)
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--runtime-ready", type=Path, required=True)
    parser.add_argument("--sources-ready", type=Path, required=True)
    parser.add_argument("--scenario", choices=sorted(CASES))
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--retry-of")
    parser.add_argument("--fix-description")
    parser.add_argument("--batch", choices=retest.BATCHES)
    parser.add_argument("--review-state", type=Path)
    parser.add_argument("--authorize-batch", action="store_true")
    parser.add_argument("--authority", type=Path)
    parser.add_argument("--historical-ready", type=Path)
    parser.add_argument("--apply-reviews", action="store_true")
    args = parser.parse_args()
    ready = read_private(args.runtime_ready)
    sources = json.loads(args.sources_ready.read_text())
    cases = validate_sources(sources)
    if args.batch and ready.get("batch_id") != args.batch:
        raise ValueError("Ready record belongs to another batch")
    if args.batch and args.authorize_batch:
        if not args.authority or not args.historical_ready or not args.review_state:
            raise ValueError("Explicit authority, immutable stop history and B readiness required")
        batch_prerequisites(ready, args.sources_ready, args.review_state, args.state_dir)
        retest.authorize(args.state_dir, ready["build_sha"], ready["scenario_manifest_sha256"], args.authority, args.historical_ready, args.batch)
        publish_batch_manifest(args.state_dir, ready, args.batch)
        print(json.dumps({"batch_id": args.batch, "authorization_registered": True, "new_runs": 0}))
        return
    if args.batch and args.apply_reviews:
        batch_prerequisites(ready, args.sources_ready, args.review_state, args.state_dir)
        retest.apply_reviews(args.state_dir, json.loads(args.review_state.read_text()), args.batch)
        publish_batch_manifest(args.state_dir, ready, args.batch)
        return
    if args.batch:
        if not args.execute:
            print(json.dumps({"batch_id": args.batch, "order": retest.scenario_order(args.batch), "new_run_limit": retest.maximum_runs(args.batch),
                              "research_reruns": 0, "model_execution": "not_started", "per_run": LIMITS}))
            return
        if not args.scenario or ready.get("tested_peer_sha") != sources["final_sha"] or not ready.get("registry_configured"):
            raise ValueError("One reviewed scenario and registered source manifest required")
        execute_batch(args, ready, sources, cases)
        return
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
            identifiers = audit_identifiers(database)
            usage = usage_summary(database)
            diagnostics = model_receipts(database)
            if usage["decision_admissions"] > 8 or usage["model_admissions"] > 16 or usage["tool_admissions"] > 16:
                raise ValueError("Actual operation ledger exceeded the authorized run limits")
            audit = finalize_audit(scrub({"case": case, "run": row, "build_verification": identity,
                           "view": view, "database": audit_database(database), "usage": usage,
                           "model_receipts": diagnostics,
                           "model": ready["model"], "source_classification": case["source_classification"],
                           "manual_review_status": "pending", "fixtures": False}, secrets,
                                           identifiers=identifiers))
            native = validate_saved_audit(audit)
            if (native["status"] == "invalid" or row["status"] == "SUCCEEDED"
                    and not native["eligible_for_complete_review"]):
                row["validation_error_type"] = "NativeRequirementAuditIncomplete"
                audit["run"] = scrub(row, secrets)
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

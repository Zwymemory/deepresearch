"""Paid-run guardrails fail before HTTP submission; no model/network calls here."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("accept_live", SCRIPTS / "accept-agent-round1.py")
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)
common = sys.modules["agent_live_common"]


class AdmissionTests(unittest.TestCase):
    def row(self, scenario="knowledge-only", **extra):
        return {"scenario": scenario, "runId": "wf-test", "status": "SUCCEEDED",
                "build_sha": "a" * 40, **extra}

    def test_global_limit_and_pending_response_prevent_more_paid_runs(self):
        for rows in [[self.row()] * 8, [self.row(status="REQUEST_RESERVED")]]:
            with self.subTest(rows=len(rows)), self.assertRaises(ValueError):
                live.admit({"runs": rows}, "web-only", "b" * 40, None, None)

    def test_duplicate_initial_case_and_unchanged_retry_are_denied(self):
        journal = {"runs": [self.row()]}
        with self.assertRaises(ValueError):
            live.admit(journal, "knowledge-only", "b" * 40, None, None)
        with self.assertRaises(ValueError):
            live.admit(journal, "knowledge-only", "a" * 40, "wf-test", "Diagnosed wiring fix")
        live.admit(journal, "knowledge-only", "b" * 40, "wf-test", "Diagnosed wiring fix")

    def test_two_same_infrastructure_failures_stop_before_next_submission(self):
        journal = {"runs": [self.row(status="FAILED", errorCode="AGENT_MODEL_INVALID"),
                            self.row("web-only", status="FAILED", errorCode="AGENT_MODEL_INVALID")]}
        with self.assertRaises(ValueError):
            live.admit(journal, "mixed", "a" * 40, None, None)

    def test_sources_require_six_cases_without_prescribed_actions(self):
        source = {"ready": True, "final_sha": "a" * 40,
                  "cases": [{"id": name, "question": "A public research question",
                             "requested_tools": ["web_search"], "source_classification": "real-public"}
                            for name in live.CASES]}
        self.assertEqual(len(live.validate_sources(source)), 6)
        source["cases"][0]["actions"] = ["search"]
        with self.assertRaises(ValueError):
            live.validate_sources(source)

    def test_secrets_and_local_paths_are_removed_from_export(self):
        secret = "live-validation-secret-value"
        cleaned = live.scrub({"token": secret, "claim_token": secret,
                              "trace": ["Bearer " + secret, "/" + "home/validation/private.json"]}, [secret])
        self.assertNotIn(secret, str(cleaned))
        self.assertNotIn("private.json", str(cleaned))
        self.assertNotIn("claim_token", cleaned)

    def test_identity_rejects_tampered_artifact_before_contacting_service(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "service.jar"
            jar.write_bytes(b"different artifact")
            ready = {"ready": True, "formal_services_unchanged": True,
                     "app_base_url": "http://127.0.0.1:18080", "build_sha": "a" * 40,
                     "jar_path": str(jar), "jar_sha256": "0" * 64}
            with patch.object(common, "http_json") as request, self.assertRaises(ValueError):
                common.verify_runtime(ready, "test-token")
            request.assert_not_called()

    def test_reported_usage_distinguishes_missing_usage_from_zero(self):
        summary = live.usage_summary({"operations": [
            {"kind": "MODEL", "purpose": "DECISION", "operation_key": "decision1",
             "status": "SETTLED", "actual_usage": {"input_tokens": 20, "output_tokens": 0}},
            {"kind": "MODEL", "purpose": "CHECK", "operation_key": "check1",
             "status": "UNKNOWN", "actual_usage": None},
            {"kind": "TOOL", "status": "SETTLED"}]})
        self.assertEqual(summary["actual_input_tokens"], 20)
        self.assertEqual(summary["output_usage_missing"], 1)
        self.assertEqual(summary["model_unknown_or_inflight"], 1)
        self.assertEqual(summary["model_result_unavailable"], 1)
        self.assertEqual(summary["model_unknown_receipts"], 1)
        self.assertEqual(summary["input_usage_missing"], 1)
        self.assertIsNone(summary["actual_bill_or_cost"])

    def test_unknown_model_result_can_have_known_usage_and_safe_failure_metadata(self):
        private = "PRIVATE-RAW-PROVIDER-RESPONSE"
        database = {"operations": [
            {"kind": "MODEL", "purpose": "DECISION", "operation_key": "decision-1",
             "attempt": 1, "status": "UNKNOWN", "safe_result": {"raw": private},
             "actual_usage": {
                 "input_tokens": 41, "output_tokens": 7, "raw_response": private,
                 "model_failure": {
                     "failure_kind": "SCHEMA", "error_class": "schema_validation",
                     "retryable": False,
                     "validation_issue_codes": ["claims.applicability.valid_at", private],
                     "raw_exception": private,
                 },
             }},
            {"kind": "MODEL", "purpose": "CHECK", "operation_key": "check-1",
             "attempt": 1, "status": "UNKNOWN", "safe_result": None,
             "actual_usage": None},
        ]}
        summary = live.usage_summary(database)
        receipts = live.model_receipts(database)
        exported = live.audit_database(database)
        self.assertEqual(summary["model_result_unavailable"], 2)
        self.assertEqual(summary["actual_input_tokens"], 41)
        self.assertEqual(summary["actual_output_tokens"], 7)
        self.assertEqual(summary["input_usage_missing"], 1)
        self.assertEqual(summary["output_usage_missing"], 1)
        self.assertFalse(receipts[0]["result_usable"])
        self.assertTrue(receipts[0]["input_usage_known"])
        self.assertEqual(receipts[0]["failure"], {
            "failure_kind": "SCHEMA", "error_class": "schema_validation",
            "retryable": False,
            "validation_issue_codes": ["claims.applicability.valid_at"],
        })
        self.assertFalse(receipts[1]["input_usage_known"])
        self.assertIsNone(exported["operations"][0]["safe_result"])
        self.assertNotIn(private, json.dumps({"receipts": receipts, "database": exported}))
        self.assertEqual(database["operations"][0]["safe_result"], {"raw": private})

    def test_provider_status_is_numeric_and_unrecognized_metadata_is_not_exported(self):
        private = "PRIVATE-RAW-ERROR"
        database = {"operations": [
            {"kind": "MODEL", "purpose": "DECISION", "operation_key": "decision-1",
             "attempt": 1, "status": "UNKNOWN", "safe_result": None,
             "actual_usage": {"model_failure": {
                 "failure_kind": "RATE_LIMIT", "error_class": "http_rate_limit",
                 "retryable": True, "status_code": 429, "tool_call_count": 1,
                 "provider_error": private,
             }}},
            {"kind": "MODEL", "purpose": "DECISION", "operation_key": "decision-2",
             "attempt": 1, "status": "UNKNOWN", "safe_result": None,
             "actual_usage": {"input_tokens": True, "model_failure": {
                 "failure_kind": [private], "error_class": private,
                 "retryable": True, "status_code": private,
             }}},
        ]}
        receipts = live.model_receipts(database)
        self.assertEqual(receipts[0]["failure"], {
            "failure_kind": "RATE_LIMIT", "error_class": "http_rate_limit",
            "retryable": True, "status_code": 429, "tool_call_count": 1,
        })
        self.assertNotIn("failure", receipts[1])
        self.assertFalse(receipts[1]["input_usage_known"])
        self.assertNotIn(private, json.dumps({
            "receipts": receipts, "database": live.audit_database(database)
        }))


if __name__ == "__main__":
    unittest.main()

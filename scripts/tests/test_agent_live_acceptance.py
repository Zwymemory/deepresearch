"""Paid-run guardrails fail before HTTP submission; no model/network calls here."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import ModuleType, SimpleNamespace
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

    def test_database_capture_reads_the_original_persisted_question(self):
        saved = {"run_id": "wf-test", "question": "Actual saved question",
                 "budget": {"maxDecisionSteps": 8, "maxModelCalls": 16,
                            "maxToolCalls": 16, "maxInputTokens": 64000,
                            "maxOutputTokens": 16384}}
        queries = []

        class Connection:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def execute(self, sql, parameters=None):
                queries.append((sql, parameters))
                self.rows = [saved] if "FROM agent_workflow_run" in sql else []
                return self

            def fetchall(self):
                return self.rows

        psycopg = ModuleType("psycopg")
        rows = ModuleType("psycopg.rows")
        rows.dict_row = object()
        psycopg.connect = lambda **_: Connection()
        with patch.dict(sys.modules, {"psycopg": psycopg, "psycopg.rows": rows}):
            result = live.capture_database({"database_port": 15432},
                                           {"POSTGRES_PASSWORD": "test"}, "wf-test")
        self.assertEqual(result["run"][0]["question"], "Actual saved question")
        self.assertIn("question", queries[1][0].split(" FROM ")[0])
        self.assertEqual(queries[1][1], ("wf-test",))

    def test_request_binding_requires_saved_question_and_matching_run_ids(self):
        database = {"run": [{"run_id": "wf-test", "question": "Actual saved question"}]}
        self.assertTrue(live.persisted_request_binding(
            database, "wf-test", "Actual saved question", "wf-test")["matches"])
        for run_id, question, view_run_id in [
            ("wf-test", "Different fixture question", "wf-test"),
            ("wf-other", "Actual saved question", "wf-other"),
            ("wf-test", "Actual saved question", "wf-other"),
        ]:
            with self.subTest(run_id=run_id, question=question, view_run_id=view_run_id):
                self.assertFalse(live.persisted_request_binding(
                    database, run_id, question, view_run_id)["matches"])

    def test_unready_or_unverified_candidate_cannot_authorize_batch(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            ready = {"ready": False, "historical_state_dir": str(state),
                     "build_sha": "a" * 40, "ci": None}
            with self.assertRaisesRegex(ValueError, "runtime and source registry"):
                live.batch_prerequisites(ready, state / "sources.json", state / "review.json", state)
            ready.update(ready=True, registry_configured=True)
            with self.assertRaisesRegex(ValueError, "remote CI prerequisites"):
                live.batch_prerequisites(ready, state / "sources.json", state / "review.json", state)

    def test_mismatched_saved_question_preserves_actual_audit_and_stops_batch(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            args = SimpleNamespace(retry_of=None, fix_description=None,
                                   review_state=state / "review.json",
                                   sources_ready=state / "sources.json",
                                   state_dir=state, scenario="web-only")
            row = {"scenario": "web-only", "runId": None, "status": "REQUEST_RESERVED",
                   "build_sha": "a" * 40, "idempotency_key": "request-key"}
            database = {"run": [{"run_id": "wf-test", "question": "Actual saved question"}],
                        "operations": [], "records": [], "tasks": [], "criteria": [],
                        "checks": [], "read_receipts": [], "publications": [], "tool_receipts": []}
            ready = {"credential_access": {"path": "credentials"},
                     "token_access": {"path": "token"},
                     "model_identity_receipts_path": "identities",
                     "build_sha": "a" * 40, "app_base_url": "http://127.0.0.1:18080",
                     "model_identity": {"name": "deepseek-v4-flash"},
                     "model": {"name": "deepseek-v4-flash"}}
            case = {"id": "web-only", "question": "Expected fixture question",
                    "requested_tools": ["web_search"], "source_classification": "real-public"}
            contents = {"credentials": {}, "token": {"token": "test-token"},
                        "identities": {"receipts": [{"request_model_matches": True,
                                                     "identity_matches": True}]}}
            updates = []
            with (patch.object(live, "batch_prerequisites", return_value={}),
                  patch.object(live, "read_private", side_effect=lambda path: contents[str(path)]),
                  patch.object(live, "verify_runtime", return_value={"model_identity": ready["model_identity"]}),
                  patch.object(live, "http_json", side_effect=[
                      {"runId": "wf-test", "status": "QUEUED"},
                      {"runId": "wf-test", "status": "SUCCEEDED"}]),
                  patch.object(live, "capture_database", return_value=database),
                  patch.object(live.retest, "reserve", return_value=row),
                  patch.object(live.retest, "update", side_effect=lambda _, value: updates.append(value.copy())),
                  patch.object(live, "publish_batch_manifest")):
                with self.assertRaisesRegex(ValueError, "Persisted research request differs"):
                    live.execute_batch(args, ready, {"final_sha": "b" * 40}, {"web-only": case})
            audit = json.loads((state / "wf-test.json").read_text())
            self.assertEqual(audit["database"]["run"][0]["question"], "Actual saved question")
            self.assertEqual(audit["persisted_request_binding"]["expected_question"],
                             "Expected fixture question")
            self.assertFalse(audit["persisted_request_binding"]["matches"])
            self.assertEqual(audit["run"]["validation_error_type"], "PersistedRequestMismatch")
            self.assertEqual(updates[-1]["validation_error_type"], "PersistedRequestMismatch")
            self.assertEqual(updates[-1]["audit_path"], str((state / "wf-test.json").resolve()))


if __name__ == "__main__":
    unittest.main()

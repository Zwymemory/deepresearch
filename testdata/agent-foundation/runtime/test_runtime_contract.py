"""Deterministic synthetic protocol checks, not Agent/model evaluation."""
import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "contracts/agent/v0"))
from validate_runtime import ContractError, load_json, validate_bundle, validate_record, validator


class RuntimeContractTest(unittest.TestCase):
    def setUp(self):
        self.bundle = load_json(Path(__file__).with_name("runtime-foundation.json"))

    def row(self, kind):
        return next(row for row in self.bundle["records"] if row["record_type"] == kind)

    def validate(self):
        return validate_bundle(self.bundle, authorized_scope=self.bundle["authorized_scope"])

    def reject(self, code):
        with self.assertRaises(ContractError) as error:
            self.validate()
        self.assertEqual(error.exception.code, code)

    def test_positive_fixture_discloses_no_semantic_or_runtime_proof(self):
        result = self.validate()
        self.assertEqual(result["runtime_records_checked"], 7)
        self.assertFalse(result["runtime_execution"])
        self.assertFalse(result["semantic_verification"])

    def test_unknown_usage_is_not_zero(self):
        self.row("Budget")["usage"]["cost"]["value"] = 0
        self.reject("SCHEMA_INVALID")

    def test_unknown_token_usage_is_not_zero(self):
        self.row("Budget")["usage"]["input_tokens"] = {"status": "unknown", "value": 0, "reason": "not measured"}
        self.reject("SCHEMA_INVALID")

    def test_missing_timezone_is_rejected(self):
        self.row("ResearchProject")["created_at"] = "2026-09-29T00:00:00"
        self.reject("SCHEMA_INVALID")

    def test_calendar_invalid_date_with_timezone_is_rejected(self):
        self.row("ResearchProject")["created_at"] = "2026-02-30T00:00:00Z"
        self.reject("SCHEMA_INVALID")

    def test_malformed_timezone_offset_is_rejected(self):
        self.row("ResearchProject")["created_at"] = "2026-09-29T00:00:00+01:99"
        self.reject("SCHEMA_INVALID")

    def test_absolute_uri_format_is_checked_without_optional_packages(self):
        check = validator({"type": "string", "format": "uri"})
        for value in ["relative/path", "https://", "https://example.org/a b", "https://example.org/%GG"]:
            with self.subTest(value=value):
                self.assertFalse(check.is_valid(value))
        for value in ["https://example.org/a%20b", "urn:fixture:evidence-1"]:
            with self.subTest(value=value):
                self.assertTrue(check.is_valid(value))

    def test_unregistered_format_cannot_silently_pass(self):
        with self.assertRaises(ContractError) as error:
            validator({"type": "string", "format": "unknown-format"})
        self.assertEqual(error.exception.code, "UNSUPPORTED_SCHEMA_FORMAT")

    def test_unknown_fields_are_rejected(self):
        self.row("AgentContext")["allowed_tools"] = ["file_write"]
        self.reject("SCHEMA_INVALID")

    def test_scope_is_independent_from_context_content(self):
        self.row("AgentContext")["owner_id"] = "other-user"
        self.reject("AUTHORIZED_SCOPE_MISMATCH")

    def test_project_reference_scope_mismatch_is_rejected(self):
        self.bundle["external_refs"][0]["tenant_id"] = "other-tenant"
        self.reject("REFERENCE_SCOPE_MISMATCH")

    def test_task_dependency_must_exist(self):
        self.row("Task")["dependencies"] = ["missing"]
        self.reject("REFERENCE_MISSING")

    def test_task_dependency_cycle_is_rejected(self):
        self.row("Task")["dependencies"] = ["fixture-task"]
        self.reject("TASK_CYCLE")

    def test_parent_cycle_is_rejected(self):
        self.row("Task")["parent_task_id"] = "fixture-task"
        self.reject("TASK_CYCLE")

    def test_tools_cannot_be_granted_by_task_or_memory_text(self):
        self.row("Task")["allowed_tools"] = ["read_source"]
        self.reject("TOOL_SCOPE_DENIED")

    def test_actor_cannot_be_changed_by_context(self):
        self.row("Task")["agent_id"] = "foreign-agent"
        self.reject("AGENT_SCOPE_DENIED")

    def test_active_reservations_count_before_dispatch(self):
        self.row("Budget")["reservations"][0]["amount"] = 5
        self.reject("BUDGET_LIMIT_EXCEEDED")

    def test_duplicate_reservations_are_rejected(self):
        self.row("Budget")["reservations"].append(copy.deepcopy(self.row("Budget")["reservations"][0]))
        self.reject("DUPLICATE_RESERVATION")

    def test_overdrawn_settlement_is_rejected(self):
        reservation = self.row("Budget")["reservations"][0]
        reservation.update(state="settled", settled_amount=2)
        self.reject("RESERVATION_OVERDRAWN")

    def test_injected_requires_selected(self):
        self.row("AgentContext")["entries"][0]["selected"] = False
        self.reject("SCHEMA_INVALID")

    def test_injected_is_not_used(self):
        self.row("AgentContext")["entries"][0]["used"] = {"status": "confirmed", "verification_refs": []}
        self.reject("SCHEMA_INVALID")

    def test_injected_without_trace_cannot_be_declared_not_used(self):
        self.row("AgentContext")["entries"][0]["used"]["status"] = "not_used"
        self.reject("SCHEMA_INVALID")

    def test_use_confirmation_requires_real_trace_reference(self):
        self.row("AgentContext")["entries"][0]["used"] = {"status": "confirmed", "verification_refs": ["missing-trace"]}
        self.reject("REFERENCE_MISSING")

    def test_use_trace_for_another_entry_is_rejected(self):
        context = self.row("AgentContext")
        context["entries"][0]["used"] = {"status": "confirmed", "verification_refs": ["trace"]}
        self.bundle["external_refs"].append({**self.bundle["authorized_scope"], "record_type": "Assessment",
            "assessment_id": "trace", "status": "confirmed", "observed_model_usage": True,
            "run_id": "fixture-run", "context_id": "fixture-context", "entry_id": "different-entry"})
        self.reject("USE_TRACE_CONTEXT_MISMATCH")

    def test_use_trace_presence_alone_is_not_confirmation(self):
        context = self.row("AgentContext")
        context["entries"][0]["used"] = {"status": "confirmed", "verification_refs": ["trace"]}
        self.bundle["external_refs"].append({**self.bundle["authorized_scope"], "record_type": "Assessment",
            "assessment_id": "trace", "status": "confirmed", "observed_model_usage": False})
        self.reject("MODEL_USE_UNVERIFIED")

    def test_legacy_context_cannot_acquire_project_memory_identity(self):
        context = self.row("AgentContext")
        context.update(project_id=None, legacy_unscoped=True)
        context["entries"][2]["source_memory_id"] = "project-memory"
        with self.assertRaises(ContractError):
            validate_record(context)

    def test_memory_reference_is_not_fabricated_from_text(self):
        self.row("AgentContext")["entries"][2]["source_memory_id"] = "missing-memory"
        self.row("AgentContext")["entries"][2]["source_memory_version"] = 1
        self.reject("REFERENCE_MISSING")

    def test_legacy_string_cannot_be_marked_as_a_provenance_bound_result(self):
        self.row("AgentContext")["entries"][2]["purpose"] = "result_reuse"
        self.reject("SCHEMA_INVALID")

    def test_terminal_task_cannot_restart(self):
        self.bundle["records"][-1].update(before_state="done", after_state="running")
        self.reject("TASK_TRANSITION_INVALID")

    def test_repeated_sequence_cannot_replay_as_new_action(self):
        self.bundle["records"][-1]["sequence"] = 2
        self.reject("EVENT_REPLAY_CONFLICT")

    def test_events_must_join_continuously(self):
        self.bundle["records"][-1]["before_state"] = "pending"
        self.reject("EVENT_STATE_DISCONTINUITY")

    def test_final_snapshot_matches_last_event(self):
        self.row("Task")["status"] = "pending"
        self.reject("TASK_EVENT_STATE_MISMATCH")

    def test_duplicate_json_keys_are_rejected_before_validation(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.json"
            path.write_text('{"status":"unknown","status":"known"}')
            with self.assertRaises(ContractError) as error:
                load_json(path)
            self.assertEqual(error.exception.code, "DUPLICATE_JSON_KEY")


if __name__ == "__main__":
    unittest.main()

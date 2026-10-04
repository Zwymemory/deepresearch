"""New one-run server segment acceptance policy; disposable offline history fixtures."""

import copy
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_retest_batch as batch  # noqa: E402
from agent_live_common import file_sha, read_private, write_private  # noqa: E402
import test_agent_diagnostics_web_batch as predecessor  # noqa: E402


class SegmentsWebTests(unittest.TestCase):
    def setUp(self):
        seed = predecessor.DiagnosticsWebTests()
        seed.setUp()
        self.addCleanup(seed.doCleanups)
        seed.authorize()
        row = seed.reserve()
        audit = seed.state / "old-v22-failure.json"
        write_private(audit, {"fixture": "offline predecessor policy receipt"})
        row.update(runId="old-v22", status="FAILED", errorCode="MODEL_SCHEMA_INVALID",
                   audit_path=str(audit), audit_sha256=file_sha(audit), source_hashes={})
        batch.update(seed.state, row, batch.DIAGNOSTICS_WEB_BATCH)
        seed.review["reviews"] = [{
            "run_id": row["runId"], "scenario": "web-only", "build_sha": row["build_sha"],
            "audit_sha256": row["audit_sha256"], "source_hashes": {}, "decision": "incomplete",
            "reviewed_at": "fixture-time",
        }]
        batch.apply_reviews(seed.state, seed.review, batch.DIAGNOSTICS_WEB_BATCH)
        self.state, self.old_ready = seed.state, seed.old_ready
        self.original = read_private(self.state / "run-journal.json")
        self.original_bytes = (self.state / "run-journal.json").read_bytes()
        self.authority = self.state / "diagnostics-authority.md"
        self.authority.write_text("Explicit one original-question run, zero reruns; fixture only")
        self.review = copy.deepcopy(seed.review)
        self.review.update(batch_id=batch.SEGMENTS_WEB_BATCH, reviews=[])
        self.review["candidate_binding"].update(observed_candidate_sha="f" * 40,
            planner_contract="agent-planning-segments/2", question_mapping_version="agent-question-segments/1",
            planner_settlement_contract="agent-planner-settlement/1")
        binding = patch.object(batch, "SEGMENTS_WEB_HISTORY_SHA", file_sha(self.state / "run-journal.json"))
        binding.start()
        self.addCleanup(binding.stop)

    def authorize(self):
        return batch.authorize(self.state, "f" * 40, "c" * 64, self.authority,
                               self.old_ready, batch.SEGMENTS_WEB_BATCH)

    def reserve(self, scenario="web-only", review=None):
        return batch.reserve(self.state, scenario, "f" * 40, "a" * 40,
                             self.review if review is None else review, batch.SEGMENTS_WEB_BATCH)

    def assert_history(self):
        journal = read_private(self.state / "run-journal.json")
        self.assertEqual(journal["runs"][:11], self.original["runs"])
        for name in batch.BATCHES[:batch.BATCHES.index(batch.SEGMENTS_WEB_BATCH)]:
            self.assertEqual(journal["authorized_batches"][name], self.original["authorized_batches"][name])
            old, rows = batch.validate_history(journal, name)
            self.assertEqual(old["status"], "STOPPED")
            self.assertEqual(len(rows), 1)

    def test_exclusive_history_and_one_slot_preserve_all_five_stopped_batches(self):
        auth = self.authorize()
        self.assertEqual((auth["maximum_runs"], auth["maximum_research_reruns"], auth["scenario_order"]),
                         (1, 0, ["web-only"]))
        self.assertEqual(Path(auth["history_snapshot_path"]).read_bytes(), self.original_bytes)
        with self.assertRaises(ValueError):
            self.authorize()
        self.assert_history()
        row = self.reserve()
        self.assertTrue(row["idempotency_key"].startswith("live-segments-web-"))
        self.assertIsNone(row["retry_of"])
        self.assert_history()
        for scenario in batch.ORDER:
            with self.subTest(scenario=scenario), self.assertRaises(ValueError):
                self.reserve(scenario)

    def test_exact_closed_history_candidate_and_review_required_before_reservation(self):
        with patch.object(batch, "SEGMENTS_WEB_HISTORY_SHA", "0" * 64), self.assertRaises(ValueError):
            self.authorize()
        self.authorize()
        for mutate in (
            lambda r: r.update(ready=False),
            lambda r: r["candidate_binding"].pop("planner_contract"),
            lambda r: r["candidate_binding"].update(question_mapping_version="unknown"),
            lambda r: r["candidate_binding"].update(planner_settlement_contract=None),
            lambda r: r.update(source_manifest_sha256="0" * 64),
            lambda r: r["candidate_binding"].update(approved_for_live=False),
            lambda r: r["candidate_binding"].update(observed_candidate_sha="0" * 40),
            lambda r: r["candidate_binding"].update(result_transport="function_call"),
            lambda r: r["candidate_binding"].update(transport_contract_version="unknown"),
        ):
            review = copy.deepcopy(self.review)
            mutate(review)
            with self.assertRaises(ValueError):
                self.reserve(review=review)
        journal = read_private(self.state / "run-journal.json")
        for mutate in (
            lambda j: j["runs"][10].update(status="SUCCEEDED"),
            lambda j: j["authorized_batches"][batch.DIAGNOSTICS_WEB_BATCH].update(status="AUTHORIZED"),
            lambda j: j["authorized_batches"][batch.SEGMENTS_WEB_BATCH].update(maximum_runs=2),
            lambda j: j["authorized_batches"][batch.SEGMENTS_WEB_BATCH].update(maximum_research_reruns=1),
        ):
            altered = copy.deepcopy(journal)
            mutate(altered)
            with self.assertRaises(ValueError):
                batch.admit(altered, "web-only", "f" * 40, self.review, batch.SEGMENTS_WEB_BATCH)
        for case, sha in (("mixed", "f" * 40), ("web-only", "e" * 40)):
            with self.assertRaises(ValueError):
                batch.admit(journal, case, sha, self.review, batch.SEGMENTS_WEB_BATCH)
        self.assertEqual(len(read_private(self.state / "run-journal.json")["runs"]), 11)

    def test_concurrent_and_ambiguous_submission_consume_exactly_one_nonreopenable_slot(self):
        self.authorize()
        def attempt():
            try:
                return self.reserve()
            except (ValueError, BlockingIOError):
                return None
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: attempt(), range(2)))
        self.assertEqual(sum(r is not None for r in results), 1)
        row = next(r for r in results if r is not None)
        row["validation_error_type"] = "AmbiguousPostOutcome"
        batch.update(self.state, row, batch.SEGMENTS_WEB_BATCH)
        self.assertEqual(read_private(self.state / "run-journal.json")["authorized_batches"][batch.SEGMENTS_WEB_BATCH]["status"], "STOPPED")
        with self.assertRaises(ValueError):
            self.reserve()
        self.assert_history()

    def test_semantic_identity_required_and_completed_slot_cannot_reopen(self):
        self.authorize()
        row = self.reserve()
        audit = self.state / "diagnostics-audit.json"
        write_private(audit, {"fixture": "offline policy test, not native semantic proof"})
        row.update(runId="fixture-diagnostics", status="SUCCEEDED", audit_path=str(audit),
                   audit_sha256=file_sha(audit), source_hashes={"fixture": "d" * 64})
        batch.update(self.state, row, batch.SEGMENTS_WEB_BATCH)
        with self.assertRaises(ValueError):
            batch.apply_reviews(self.state, self.review, batch.SEGMENTS_WEB_BATCH)
        decision = {"run_id":row["runId"], "scenario":"web-only", "build_sha":row["build_sha"],
                    "audit_sha256":row["audit_sha256"], "source_hashes":row["source_hashes"],
                    "decision":"pass", "reviewed_at":"fixture-time"}
        self.review["reviews"] = [decision]
        for key, wrong in (("audit_sha256", "0"*64), ("build_sha", "0"*40), ("run_id", "other"), ("source_hashes", {})):
            altered = copy.deepcopy(self.review)
            altered["reviews"][0][key] = wrong
            with self.assertRaises(ValueError):
                batch.apply_reviews(self.state, altered, batch.SEGMENTS_WEB_BATCH)
        self.assertEqual(batch.apply_reviews(self.state, self.review, batch.SEGMENTS_WEB_BATCH)["status"], "COMPLETED")
        with self.assertRaises(ValueError):
            self.reserve()
        self.assert_history()

    def test_actual_prerequisites_reject_missing_independent_approval_or_wrong_ci(self):
        live = predecessor.predecessor.predecessor.load("accept-agent-round1")
        manifest = self.state / "scenarios.json"
        manifest.write_text(json.dumps({"cases":[{"id":"web-only"}]}))
        source = self.state / "sources.json"
        source.write_bytes(manifest.read_bytes())
        review_path = self.state / "review.json"
        review = copy.deepcopy(self.review)
        review["source_manifest_sha256"] = file_sha(manifest)
        review_path.write_text(json.dumps(review))
        ready = {"ready":True, "registry_configured":True, "historical_state_dir":str(self.state),
                 "build_sha":"f"*40, "batch_id":batch.SEGMENTS_WEB_BATCH,
                 "scenario_manifest_path":str(manifest), "scenario_manifest_sha256":file_sha(manifest),
                 "planner_identity":{"planner_contract":"agent-planning-segments/2", "question_mapping_version":"agent-question-segments/1", "planner_settlement_contract":"agent-planner-settlement/1"},
                 "model_identity":{"name":"deepseek-flash", "endpoint":"https://api.deepseek.com", "result_transport":"deepseek_json_object", "transport_contract_version":"agent-result-wire/1"},
                 "ci":{"head_sha":"f"*40, "conclusion":"success", "jobs":[{"name":n,"conclusion":"success"} for n in ["showcase-offline","java-unit","python-unit","integration","workflow-postgres-integration","secret-scan"]]}}
        live.batch_prerequisites(ready, source, review_path, self.state)
        absent = copy.deepcopy(ready)
        absent.pop("planner_identity")
        with self.assertRaises(ValueError):
            live.batch_prerequisites(absent, source, review_path, self.state)
        changed = copy.deepcopy(ready)
        changed["ci"]["head_sha"] = "e"*40
        with self.assertRaises(ValueError):
            live.batch_prerequisites(changed, source, review_path, self.state)
        review["candidate_binding"]["approved_for_live"] = False
        review_path.write_text(json.dumps(review))
        with self.assertRaises(ValueError):
            live.batch_prerequisites(ready, source, review_path, self.state)
        prepare = predecessor.predecessor.predecessor.load("prepare-agent-live-runtime")
        with patch.object(prepare, "candidate_schema_policy", return_value={}), patch.object(prepare.subprocess, "run") as command:
            with self.assertRaises(ValueError):
                prepare.start(self.state, {}, {}, Path("python"), self.state/"ready.json", {},
                              "deepseek-flash", batch.SEGMENTS_WEB_BATCH, "function_call")
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()

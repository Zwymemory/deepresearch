"""One V22 original-question research allowance; disposable offline journals only."""

import copy
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import agent_retest_batch as batch  # noqa: E402
from agent_live_common import file_sha, read_private, write_private  # noqa: E402
import test_agent_json_web_batch as predecessor  # noqa: E402


class V22WebTests(unittest.TestCase):
    def setUp(self):
        seed = predecessor.JsonWebTests()
        seed.setUp()
        self.addCleanup(seed.doCleanups)
        seed.authorize()
        row = seed.finish(seed.reserve(), "BUDGET_EXCEEDED")
        seed.approve(row, "incomplete")
        batch.apply_reviews(seed.state, seed.review, batch.JSON_WEB_BATCH)
        self.state, self.old_ready = seed.state, seed.old_ready
        self.original = read_private(self.state / "run-journal.json")
        self.original_bytes = (self.state / "run-journal.json").read_bytes()
        self.authority = self.state / "v22-authority.md"
        self.authority.write_text("One original V22 web-only research; zero reruns")
        self.review = copy.deepcopy(seed.review)
        self.review.update(batch_id=batch.V22_WEB_BATCH, reviews=[])
        self.review["candidate_binding"]["observed_candidate_sha"] = "e" * 40
        binding = patch.object(
            batch, "V22_WEB_HISTORY_SHA", file_sha(self.state / "run-journal.json")
        )
        binding.start()
        self.addCleanup(binding.stop)

    def authorize(self):
        return batch.authorize(
            self.state,
            "e" * 40,
            "c" * 64,
            self.authority,
            self.old_ready,
            batch.V22_WEB_BATCH,
        )

    def reserve(self, scenario="web-only"):
        return batch.reserve(
            self.state, scenario, "e" * 40, "a" * 40, self.review, batch.V22_WEB_BATCH
        )

    def assert_history(self):
        journal = read_private(self.state / "run-journal.json")
        self.assertEqual(journal["runs"][:9], self.original["runs"])
        for name in batch.BATCHES[:batch.BATCHES.index(batch.V22_WEB_BATCH)]:
            self.assertEqual(
                journal["authorized_batches"][name],
                self.original["authorized_batches"][name],
            )
            self.assertEqual(len(batch.validate_history(journal, name)[1]), 1)
            self.assertEqual(
                batch.validate_history(journal, name)[0]["status"], "STOPPED"
            )

    def test_exact_single_slot_preserves_three_stops_and_exclusive_snapshot(self):
        auth = self.authorize()
        self.assertEqual(auth["maximum_runs"], 1)
        self.assertEqual(auth["scenario_order"], ["web-only"])
        self.assertEqual(auth["maximum_research_reruns"], 0)
        self.assertEqual(
            Path(auth["history_snapshot_path"]).read_bytes(), self.original_bytes
        )
        self.assert_history()
        with self.assertRaises(ValueError):
            self.authorize()
        self.reserve()
        self.assert_history()
        for case in batch.ORDER:
            with self.subTest(case=case), self.assertRaises(ValueError):
                self.reserve(case)

    def test_exact_history_candidate_case_and_independent_json_mode_required(self):
        with (
            patch.object(batch, "V22_WEB_HISTORY_SHA", "0" * 64),
            self.assertRaises(ValueError),
        ):
            self.authorize()
        self.authorize()
        journal = read_private(self.state / "run-journal.json")
        for mutate in (
            lambda j: j["runs"][8].update(status="SUCCEEDED"),
            lambda j: j["authorized_batches"][batch.JSON_WEB_BATCH].update(
                status="AUTHORIZED"
            ),
            lambda j: j["authorized_batches"][batch.V22_WEB_BATCH].update(
                maximum_runs=5
            ),
            lambda j: j["authorized_batches"][batch.V22_WEB_BATCH].update(
                result_transport="function_call"
            ),
        ):
            changed = copy.deepcopy(journal)
            mutate(changed)
            with self.assertRaises(ValueError):
                batch.admit(
                    changed, "web-only", "e" * 40, self.review, batch.V22_WEB_BATCH
                )
        for case, sha in (("mixed", "e" * 40), ("web-only", "f" * 40)):
            with self.assertRaises(ValueError):
                batch.admit(journal, case, sha, self.review, batch.V22_WEB_BATCH)
        for field, wrong in (
            ("approved_for_live", False),
            ("observed_candidate_sha", "f" * 40),
            ("result_transport", "function_call"),
            ("transport_contract_version", "unknown"),
        ):
            review = copy.deepcopy(self.review)
            review["candidate_binding"][field] = wrong
            with self.assertRaises(ValueError):
                batch.admit(journal, "web-only", "e" * 40, review, batch.V22_WEB_BATCH)
        self.assertEqual(len(read_private(self.state / "run-journal.json")["runs"]), 9)

    def test_concurrent_reservation_unknown_outcome_and_budget_failure_never_reopen(
        self,
    ):
        self.authorize()

        def attempt():
            try:
                return self.reserve()
            except (ValueError, BlockingIOError):
                return None

        with ThreadPoolExecutor(max_workers=2) as pool:
            rows = list(pool.map(lambda _: attempt(), range(2)))
        self.assertEqual(sum(row is not None for row in rows), 1)
        row = next(row for row in rows if row is not None)
        row["validation_error_type"] = "UnknownPostOutcome"
        batch.update(self.state, row, batch.V22_WEB_BATCH)
        self.assertEqual(
            read_private(self.state / "run-journal.json")["authorized_batches"][
                batch.V22_WEB_BATCH
            ]["status"],
            "STOPPED",
        )
        with self.assertRaises(ValueError):
            self.reserve()
        self.assert_history()

    def test_exact_bound_verdict_completes_or_stops_without_new_slot(self):
        self.authorize()
        row = self.reserve()
        path = self.state / "v22-audit.json"
        write_private(path, {"fixture": "offline admission audit"})
        row.update(
            runId="fixture-v22",
            status="SUCCEEDED",
            audit_path=str(path),
            audit_sha256=file_sha(path),
            source_hashes={"ev": "d" * 64},
        )
        batch.update(self.state, row, batch.V22_WEB_BATCH)
        decision = {
            "run_id": row["runId"],
            "scenario": "web-only",
            "build_sha": row["build_sha"],
            "audit_sha256": row["audit_sha256"],
            "source_hashes": row["source_hashes"],
            "decision": "pass",
            "reviewed_at": "fixture-time",
        }
        self.review["reviews"] = [decision]
        for field, wrong in (
            ("run_id", "other"),
            ("audit_sha256", "0" * 64),
            ("build_sha", "0" * 40),
            ("source_hashes", {}),
        ):
            changed = copy.deepcopy(self.review)
            changed["reviews"][0][field] = wrong
            with self.assertRaises(ValueError):
                batch.apply_reviews(self.state, changed, batch.V22_WEB_BATCH)
        self.assertEqual(
            batch.apply_reviews(self.state, self.review, batch.V22_WEB_BATCH)["status"],
            "COMPLETED",
        )
        with self.assertRaises(ValueError):
            self.reserve()
        decision["decision"] = "incomplete"
        with self.assertRaises(ValueError):
            batch.apply_reviews(self.state, self.review, batch.V22_WEB_BATCH)
        self.assert_history()

    def test_actual_preparation_and_prerequisites_refuse_other_model_mode_or_ci(self):
        prepare = predecessor.load("prepare-agent-live-runtime")
        for model, mode in (
            ("deepseek-flash", "function_call"),
            ("deepseek-v4-flash", "deepseek_json_object"),
        ):
            with (
                patch.object(prepare, "candidate_schema_policy", return_value={}),
                patch.object(prepare.subprocess, "run") as command,
            ):
                with self.assertRaises(ValueError):
                    prepare.start(
                        self.state,
                        {},
                        {},
                        Path("python"),
                        self.state / "ready.json",
                        {},
                        model,
                        batch.V22_WEB_BATCH,
                        mode,
                    )
                command.assert_not_called()
        live = predecessor.load("accept-agent-round1")
        import json

        manifest = self.state / "scenarios.json"
        manifest.write_text(json.dumps({"cases": [{"id": "web-only"}]}))
        source = self.state / "sources.json"
        source.write_bytes(manifest.read_bytes())
        review_path = self.state / "review.json"
        review = copy.deepcopy(self.review)
        review["source_manifest_sha256"] = file_sha(manifest)
        review_path.write_text(json.dumps(review))
        ready = {
            "ready": True,
            "registry_configured": True,
            "historical_state_dir": str(self.state),
            "build_sha": "e" * 40,
            "batch_id": batch.V22_WEB_BATCH,
            "scenario_manifest_path": str(manifest),
            "scenario_manifest_sha256": file_sha(manifest),
            "model_identity": {
                "name": "deepseek-flash",
                "endpoint": "https://api.deepseek.com",
                "result_transport": "deepseek_json_object",
                "transport_contract_version": "agent-result-wire/1",
            },
            "ci": {
                "head_sha": "e" * 40,
                "conclusion": "success",
                "jobs": [
                    {"name": name, "conclusion": "success"}
                    for name in (
                        "showcase-offline",
                        "java-unit",
                        "python-unit",
                        "integration",
                        "workflow-postgres-integration",
                        "secret-scan",
                    )
                ],
            },
        }
        live.batch_prerequisites(ready, source, review_path, self.state)
        for field, wrong in (
            ("result_transport", "function_call"),
            ("name", "other"),
            ("endpoint", "https://wrong.invalid"),
        ):
            changed = copy.deepcopy(ready)
            changed["model_identity"][field] = wrong
            with self.assertRaises(ValueError):
                live.batch_prerequisites(changed, source, review_path, self.state)
        changed = copy.deepcopy(ready)
        changed["ci"]["head_sha"] = "f" * 40
        with self.assertRaises(ValueError):
            live.batch_prerequisites(changed, source, review_path, self.state)

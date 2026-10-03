"""Offline admission and immutable history tests; zero research/provider calls."""

from concurrent.futures import ThreadPoolExecutor
import copy
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_retest_batch as batch
from agent_live_common import file_sha, read_private, write_private


class BatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name)
        self.authority = self.state / "authority.md"
        self.authority.write_text("Explicit bounded test batch authorization")
        self.old_ready = self.state / "historical-runtime.json"
        write_private(
            self.old_ready,
            {
                "implementation_stopped": True,
                "stop_reason": "Historical model stop retained",
            },
        )
        self.old = {
            "runs": [
                {
                    "scenario": "knowledge-only" if i < 3 else batch.ORDER[i - 3],
                    "runId": "old-" + str(i),
                    "status": "SUCCEEDED" if i == 2 else "FAILED",
                    "errorCode": "AGENT_MODEL_INVALID",
                    "build_sha": "a" * 40,
                    "retry_of": "old-" + str(i - 1) if i in {1, 2} else None,
                }
                for i in range(6)
            ]
        }
        write_private(self.state / "run-journal.json", self.old)
        self.old_bytes = (self.state / "run-journal.json").read_bytes()
        self.review = {
            "ready": True,
            "batch_id": batch.BATCH,
            "source_manifest_sha256": "c" * 64,
            "reviews": [],
        }

    def authorize(self):
        return batch.authorize(
            self.state, "b" * 40, "c" * 64, self.authority, self.old_ready
        )

    def reserve(self, scenario):
        return batch.reserve(self.state, scenario, "b" * 40, "a" * 40, self.review)

    def finish(self, row, status="SUCCEEDED"):
        path = self.state / (row["scenario"] + ".json")
        write_private(path, {"test": "bounded synthetic offline capture"})
        row.update(
            runId="new-" + row["scenario"],
            status=status,
            audit_path=str(path),
            audit_sha256=file_sha(path),
            source_hashes={"ev-" + row["scenario"]: "d" * 64},
        )
        batch.update(self.state, row)
        return row

    def approve(self, row, decision="pass"):
        review = {
            "run_id": row["runId"],
            "scenario": row["scenario"],
            "build_sha": row["build_sha"],
            "audit_sha256": row["audit_sha256"],
            "source_hashes": row["source_hashes"],
            "decision": decision,
            "reviewed_at": "2026-10-03T00:00:00Z",
        }
        self.review["reviews"].append(review)
        return review

    def test_unauthorized_new_directory_duplicate_authorization_and_old_stop_remain_closed(
        self,
    ):
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[0])
        with tempfile.TemporaryDirectory() as other, self.assertRaises(ValueError):
            batch.reserve(Path(other), batch.ORDER[0], "b" * 40, "a" * 40, self.review)
        auth = self.authorize()
        self.assertEqual(
            Path(auth["history_snapshot_path"]).read_bytes(), self.old_bytes
        )
        self.assertEqual(
            read_private(self.state / "run-journal.json")["runs"], self.old["runs"]
        )
        self.assertEqual(
            auth["historical_stop"]["reason"], "Historical model stop retained"
        )
        with self.assertRaises(ValueError):
            self.authorize()

    def test_history_rows_snapshot_authority_and_original_stop_are_integrity_bound(
        self,
    ):
        auth = self.authorize()
        journal = read_private(self.state / "run-journal.json")
        changed = copy.deepcopy(journal)
        changed["runs"][0]["status"] = "SUCCEEDED"
        with self.assertRaises(ValueError):
            batch.admit(changed, batch.ORDER[0], "b" * 40, self.review)
        with self.assertRaises(ValueError):
            batch.admit(
                {**journal, "old_stop_cleared": True},
                batch.ORDER[0],
                "b" * 40,
                self.review,
            )
        Path(auth["history_snapshot_path"]).write_text("{}")
        with self.assertRaises(ValueError):
            batch.admit(journal, batch.ORDER[0], "b" * 40, self.review)

    def test_changed_authority_and_historical_stop_are_rejected(self):
        self.authorize()
        self.authority.write_text("different scope")
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[0])
        self.authority.write_text("Explicit bounded test batch authorization")
        write_private(
            self.old_ready, {"implementation_stopped": False, "stop_reason": None}
        )
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[0])

    def test_duplicate_and_concurrent_admission_reserve_exactly_once(self):
        self.authorize()

        def attempt():
            try:
                return self.reserve(batch.ORDER[0])
            except (ValueError, BlockingIOError):
                return None

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: attempt(), range(2)))
        self.assertEqual(sum(result is not None for result in results), 1)
        self.assertEqual(len(read_private(self.state / "run-journal.json")["runs"]), 7)
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[0])
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[1])

    def test_lost_response_consumes_slot_and_stops_without_any_rerun(self):
        self.authorize()
        row = self.reserve(batch.ORDER[0])
        row["validation_error_type"] = "LostPostResponse"
        batch.update(self.state, row)
        journal = read_private(self.state / "run-journal.json")
        self.assertEqual(
            journal["authorized_batches"][batch.BATCH]["status"], "STOPPED"
        )
        self.assertEqual(journal["runs"][-1]["status"], "REQUEST_RESERVED")
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[0])
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[1])

    def test_strict_order_and_exact_independent_semantic_binding_before_advancing(self):
        self.authorize()
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[1])
        row = self.finish(self.reserve(batch.ORDER[0]))
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[1])
        review = self.approve(row)
        for field, wrong in [
            ("build_sha", "f" * 40),
            ("audit_sha256", "f" * 64),
            ("source_hashes", {}),
            ("decision", "incomplete"),
        ]:
            original = review[field]
            review[field] = wrong
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.reserve(batch.ORDER[1])
            review[field] = original
        second = self.reserve(batch.ORDER[1])
        self.assertEqual(second["scenario"], "mixed")
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[2])
        self.finish(second)
        self.approve(second)
        review["decision"] = "fail"
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[2])

    def test_semantic_failure_and_terminal_or_budget_errors_stop_immediately(self):
        self.authorize()
        row = self.finish(self.reserve(batch.ORDER[0]))
        self.approve(row, "fail")
        result = batch.apply_reviews(self.state, self.review)
        self.assertEqual(result["status"], "STOPPED")
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[1])

    def test_noncomplete_first_scenario_cannot_be_counted_as_honest_insufficiency_pass(
        self,
    ):
        self.authorize()
        row = self.finish(self.reserve(batch.ORDER[0]), "INSUFFICIENT_EVIDENCE")
        self.approve(row)
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[1])
        self.assertEqual(
            read_private(self.state / "run-journal.json")["authorized_batches"][
                batch.BATCH
            ]["status"],
            "STOPPED",
        )

    def test_all_five_reviewed_runs_exhaust_only_new_allowance_and_preserve_old_rows(
        self,
    ):
        self.authorize()
        for i, scenario in enumerate(batch.ORDER):
            row = self.finish(
                self.reserve(scenario),
                "SUCCEEDED" if i < 3 else "INSUFFICIENT_EVIDENCE",
            )
            self.approve(row)
        result = batch.apply_reviews(self.state, self.review)
        self.assertEqual(result["status"], "COMPLETED")
        journal = read_private(self.state / "run-journal.json")
        self.assertEqual(len(journal["runs"]), 11)
        self.assertEqual(journal["runs"][:6], self.old["runs"])
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[0])

    def test_changed_prior_audit_or_changed_candidate_is_rejected(self):
        self.authorize()
        with self.assertRaises(ValueError):
            batch.reserve(self.state, batch.ORDER[0], "d" * 40, "a" * 40, self.review)
        row = self.finish(self.reserve(batch.ORDER[0]))
        self.approve(row)
        write_private(row["audit_path"], {"changed": True})
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[1])


if __name__ == "__main__":
    unittest.main()

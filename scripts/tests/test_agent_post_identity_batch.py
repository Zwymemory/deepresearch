"""New authorization extends an immutable stopped predecessor; no live requests."""

import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_retest_batch as batch
from agent_live_common import file_sha, read_private, write_private
import test_agent_retest_batch as fixtures


class PostIdentityTests(unittest.TestCase):
    def setUp(self):
        fixtures.BatchTests.setUp(self)
        fixtures.BatchTests.authorize(self)
        prior = fixtures.BatchTests.reserve(self, batch.ORDER[0])
        fixtures.BatchTests.finish(self, prior, "FAILED")
        fixtures.BatchTests.approve(self, prior, "fail")
        batch.apply_reviews(self.state, self.review)
        self.original_bytes = (self.state / "run-journal.json").read_bytes()
        self.original = read_private(self.state / "run-journal.json")
        self.authority = self.state / "new-authority.md"
        self.authority.write_text("Explicit five-run post-identity test authorization")
        self.review = {
            "ready": True,
            "batch_id": batch.POST_IDENTITY_BATCH,
            "source_manifest_sha256": "c" * 64,
            "reviews": [],
        }
        # Independent test history has its own exact bytes; production pins the real journal.
        binding = patch.object(
            batch,
            "POST_IDENTITY_HISTORY_SHA",
            file_sha(self.state / "run-journal.json"),
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
            batch.POST_IDENTITY_BATCH,
        )

    def reserve(self, scenario):
        return batch.reserve(
            self.state,
            scenario,
            "e" * 40,
            "a" * 40,
            self.review,
            batch.POST_IDENTITY_BATCH,
        )

    def finish(self, row, status="SUCCEEDED"):
        path = self.state / ("post-" + row["scenario"] + ".json")
        write_private(path, {"fixture": "new immutable audit"})
        row.update(
            runId="post-" + row["scenario"],
            status=status,
            audit_path=str(path),
            audit_sha256=file_sha(path),
            source_hashes={"ev": "d" * 64},
        )
        batch.update(self.state, row, batch.POST_IDENTITY_BATCH)
        return row

    def approve(self, row, decision="pass"):
        return fixtures.BatchTests.approve(self, row, decision)

    def test_appended_authorization_preserves_seven_rows_stopped_batch_and_original_bytes(
        self,
    ):
        auth = self.authorize()
        journal = read_private(self.state / "run-journal.json")
        self.assertEqual(
            Path(auth["history_snapshot_path"]).read_bytes(), self.original_bytes
        )
        self.assertEqual(journal["runs"], self.original["runs"])
        self.assertEqual(
            journal["authorized_batches"][batch.BATCH],
            self.original["authorized_batches"][batch.BATCH],
        )
        self.assertEqual(batch.validate_history(journal)[0]["status"], "STOPPED")
        self.assertEqual(
            batch.validate_history(journal, batch.POST_IDENTITY_BATCH)[1], []
        )
        with self.assertRaises(ValueError):
            batch.reserve(self.state, batch.ORDER[0], "b" * 40, "a" * 40, self.review)
        with self.assertRaises(ValueError):
            self.authorize()

    def test_inexact_predecessor_hash_or_unstopped_predecessor_cannot_authorize(self):
        with patch.object(batch, "POST_IDENTITY_HISTORY_SHA", "0" * 64):
            with self.assertRaises(ValueError):
                self.authorize()
        changed = copy.deepcopy(self.original)
        changed["authorized_batches"][batch.BATCH].update(
            status="AUTHORIZED", stop_reason=None
        )
        write_private(self.state / "run-journal.json", changed)
        with self.assertRaises(ValueError):
            self.authorize()

    def test_prior_batch_mutations_limits_metadata_and_extra_authorizations_fail_closed(
        self,
    ):
        self.authorize()
        original = read_private(self.state / "run-journal.json")
        for mutate in [
            lambda j: j["runs"][6].update(status="SUCCEEDED"),
            lambda j: j["authorized_batches"][batch.BATCH].update(
                stop_reason="changed"
            ),
            lambda j: j["authorized_batches"][batch.POST_IDENTITY_BATCH].update(
                maximum_runs=6
            ),
            lambda j: j.update(old_metadata_removed=True),
            lambda j: j["authorized_batches"].update(unreviewed={}),
        ]:
            changed = copy.deepcopy(original)
            mutate(changed)
            with self.assertRaises(ValueError):
                batch.admit(
                    changed,
                    batch.ORDER[0],
                    "e" * 40,
                    self.review,
                    batch.POST_IDENTITY_BATCH,
                )

    def test_concurrent_reservation_is_once_and_uncertain_result_consumes_slot_and_stops(
        self,
    ):
        self.authorize()

        def attempt():
            try:
                return self.reserve(batch.ORDER[0])
            except (ValueError, BlockingIOError):
                return None

        with ThreadPoolExecutor(max_workers=2) as pool:
            rows = list(pool.map(lambda _: attempt(), range(2)))
        self.assertEqual(sum(row is not None for row in rows), 1)
        row = next(row for row in rows if row is not None)
        row["validation_error_type"] = "LostPostResponse"
        batch.update(self.state, row, batch.POST_IDENTITY_BATCH)
        for scenario in batch.ORDER:
            with self.assertRaises(ValueError):
                self.reserve(scenario)
        journal = read_private(self.state / "run-journal.json")
        self.assertEqual(journal["runs"][:7], self.original["runs"])
        self.assertEqual(len(journal["runs"]), 8)
        self.assertEqual(
            journal["authorized_batches"][batch.POST_IDENTITY_BATCH]["status"],
            "STOPPED",
        )

    def test_strict_order_exact_run_audit_source_and_immutable_verdict_gates(self):
        self.authorize()
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[1])
        row = self.finish(self.reserve(batch.ORDER[0]))
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[1])
        review = self.approve(row)
        for field, wrong in [
            ("run_id", "other"),
            ("build_sha", "f" * 40),
            ("audit_sha256", "f" * 64),
            ("source_hashes", {}),
            ("decision", "incomplete"),
        ]:
            saved = review[field]
            review[field] = wrong
            with self.assertRaises(ValueError):
                self.reserve(batch.ORDER[1])
            review[field] = saved
        second = self.finish(self.reserve(batch.ORDER[1]))
        self.approve(second)
        review["decision"] = "fail"
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[2])
        with self.assertRaises(ValueError):
            batch.apply_reviews(self.state, self.review, batch.POST_IDENTITY_BATCH)

    def test_five_semantic_passes_complete_new_allowance_without_unlocking_old_stop(
        self,
    ):
        self.authorize()
        for i, scenario in enumerate(batch.ORDER):
            row = self.finish(
                self.reserve(scenario),
                "SUCCEEDED" if i < 3 else "INSUFFICIENT_EVIDENCE",
            )
            self.approve(row)
            batch.apply_reviews(self.state, self.review, batch.POST_IDENTITY_BATCH)
        journal = read_private(self.state / "run-journal.json")
        self.assertEqual(len(journal["runs"]), 12)
        self.assertEqual(journal["runs"][:7], self.original["runs"])
        self.assertEqual(
            journal["authorized_batches"][batch.POST_IDENTITY_BATCH]["status"],
            "COMPLETED",
        )
        self.assertEqual(
            journal["authorized_batches"][batch.BATCH],
            self.original["authorized_batches"][batch.BATCH],
        )
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[0])

    def test_semantic_fail_or_incomplete_stops_new_batch(self):
        self.authorize()
        row = self.finish(self.reserve(batch.ORDER[0]))
        self.approve(row, "incomplete")
        batch.apply_reviews(self.state, self.review, batch.POST_IDENTITY_BATCH)
        with self.assertRaises(ValueError):
            self.reserve(batch.ORDER[1])

    def test_legacy_nonbatch_admission_cannot_use_stopped_allowances(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "post_live", Path(batch.__file__).with_name("accept-agent-round1.py")
        )
        live = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(live)
        self.authorize()
        with self.assertRaises(ValueError):
            live.admit(
                read_private(self.state / "run-journal.json"),
                "insufficient-evidence",
                "e" * 40,
                None,
                None,
            )

    def preparation(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "post_prepare",
            Path(batch.__file__).with_name("prepare-agent-live-runtime.py"),
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_new_preparation_requires_canonical_model_before_operating_resources(self):
        module = self.preparation()
        with patch.object(module.subprocess, "run") as command:
            with self.assertRaises(ValueError):
                module.start(
                    self.state,
                    {},
                    {},
                    Path("python"),
                    self.state / "new-ready.json",
                    {},
                    "deepseek-v4-flash",
                    batch.POST_IDENTITY_BATCH,
                )
            command.assert_not_called()

    def test_transport_capability_is_rejected_before_resource_operations(self):
        module = self.preparation()
        for mode in ["unsupported", "deepseek_json_object"]:
            with self.subTest(mode=mode), patch.object(module.subprocess, "run") as command:
                with self.assertRaises(ValueError):
                    module.start(
                        self.state, {}, {}, Path("python"), self.state / "ready.json",
                        {}, "deepseek-v4-flash", batch.BATCH, mode,
                    )
                command.assert_not_called()

    def test_existing_new_runtime_or_build_metadata_is_preserved_before_side_effects(
        self,
    ):
        module = self.preparation()
        ready = self.state / "new-ready.json"
        write_private(ready, {"old": "immutable partial preparation"})
        original = ready.read_bytes()
        with patch.object(module.subprocess, "run") as command:
            with self.assertRaises(ValueError):
                module.start(
                    self.state,
                    {},
                    {},
                    Path("python"),
                    ready,
                    {},
                    "deepseek-flash",
                    batch.POST_IDENTITY_BATCH,
                )
            command.assert_not_called()
        self.assertEqual(ready.read_bytes(), original)
        record = self.state / (batch.POST_IDENTITY_BATCH + "-build.json")
        write_private(record, {"old": "immutable candidate"})
        with patch.object(module, "git") as git:
            with self.assertRaises(ValueError):
                module.build(
                    self.state, self.state, "e" * 40, batch.POST_IDENTITY_BATCH
                )
            git.assert_not_called()
        self.assertEqual(read_private(record), {"old": "immutable candidate"})


if __name__ == "__main__":
    unittest.main()

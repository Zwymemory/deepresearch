"""One-slot history/readiness support; all campaign mutations use disposable fixtures."""
import copy
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_retest_batch as batch
from agent_live_common import file_sha, read_private
import test_agent_obligation_web_batch as predecessor


class DecisionWebTests(unittest.TestCase):
    def setUp(self):
        seed = predecessor.ObligationWebTests()
        seed.setUp()
        self.addCleanup(seed.doCleanups)
        seed.authorize()
        seed.finish(seed.reserve(), "FAILED", batch.OBLIGATION_ALIGNMENT_WEB_BATCH)
        self.seed, self.state = seed, seed.state
        self.review = copy.deepcopy(seed.review)
        self.review.update(batch_id=batch.DECISION_CONTRACT_WEB_BATCH, reviews=[])
        self.review["candidate_binding"].update(instruction_policy="agent-obligation-instruction/1",
                                               schema_diagnostic_version="agent-schema-diagnostic/1")
        self.original = read_private(self.state / "run-journal.json")
        self.original_bytes = (self.state / "run-journal.json").read_bytes()
        gate = patch.object(batch, "DECISION_CONTRACT_WEB_HISTORY_SHA", file_sha(self.state / "run-journal.json"))
        gate.start()
        self.addCleanup(gate.stop)

    def authorize(self):
        return batch.authorize(self.state, "f" * 40, "c" * 64, self.seed.authority,
                               self.seed.old_ready, batch.DECISION_CONTRACT_WEB_BATCH)

    def reserve(self, review=None):
        return batch.reserve(self.state, "web-only", "f" * 40, "a" * 40,
                             self.review if review is None else review, batch.DECISION_CONTRACT_WEB_BATCH)

    def test_fifteen_row_prefix_unique_slot_and_stopped_predecessors(self):
        value = self.authorize()
        self.assertEqual((value["history_count"], value["maximum_runs"], value["maximum_research_reruns"]), (15, 1, 0))
        self.assertEqual(Path(value["history_snapshot_path"]).read_bytes(), self.original_bytes)
        with self.assertRaises(ValueError):
            self.authorize()
        row = self.reserve()
        self.assertTrue(row["idempotency_key"].startswith("live-decision-contract-web-"))
        with self.assertRaises(ValueError):
            self.reserve()
        row["validation_error_type"] = "AmbiguousPostOutcome"
        batch.update(self.state, row, batch.DECISION_CONTRACT_WEB_BATCH)
        with self.assertRaises(ValueError):
            self.reserve()
        journal = read_private(self.state / "run-journal.json")
        self.assertEqual(journal["runs"][:15], self.original["runs"])
        for name in batch.BATCHES[:9]:
            self.assertEqual(journal["authorized_batches"][name], self.original["authorized_batches"][name])
            old, rows = batch.validate_history(journal, name)
            self.assertEqual(old["status"], "STOPPED")
            self.assertEqual(len(rows), 1)

    def test_exact_history_and_both_new_protocol_admissions_required(self):
        with patch.object(batch, "DECISION_CONTRACT_WEB_HISTORY_SHA", "0" * 64), self.assertRaises(ValueError):
            self.authorize()
        self.authorize()
        for field in ("instruction_policy", "schema_diagnostic_version"):
            bad = copy.deepcopy(self.review)
            bad["candidate_binding"].pop(field)
            with self.assertRaises(ValueError):
                self.reserve(bad)
        self.assertEqual(len(read_private(self.state / "run-journal.json")["runs"]), 15)


class LoadedDecisionTests(unittest.TestCase):
    def test_actual_loaded_modules_instruction_hashes_and_binding_are_mandatory(self):
        from deepresearch_workflow import agent_runtime
        scripts = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location("decision_sidecar", scripts / "agent-live-sidecar.py")
        side = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(side)
        source = Path(agent_runtime.__file__).resolve().parents[1]
        proof = side.loaded_decision_identity(source)
        self.assertEqual(proof["instruction_policy"], "agent-obligation-instruction/1")
        self.assertEqual(proof["schema_diagnostic_version"], "agent-schema-diagnostic/1")
        self.assertNotEqual(proof["initial_instruction_sha256"], proof["continuation_instruction_sha256"])
        for row in proof["modules"].values():
            self.assertEqual(file_sha(row["path"]), row["sha256"])
        with self.assertRaises(ValueError):
            side.loaded_decision_identity(source / "foreign")
        with patch.object(agent_runtime, "POLICY_VERSION", "foreign"), self.assertRaises(ValueError):
            side.loaded_decision_identity(source)
        spec = importlib.util.spec_from_file_location("decision_accept", scripts / "accept-agent-round1.py")
        accept = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(accept)
        ready = {"decision_identity": proof}
        binding = {"decision_identity": copy.deepcopy(proof),
                   "instruction_policy": proof["instruction_policy"],
                   "schema_diagnostic_version": proof["schema_diagnostic_version"]}
        review = {"candidate_binding": binding}
        accept.validate_decision_readiness(ready, review)
        for field in ("instruction_builder_sha256", "failure_classifier_sha256",
                      "initial_instruction_sha256", "continuation_instruction_sha256"):
            bad = copy.deepcopy(review)
            bad["candidate_binding"]["decision_identity"][field] = "0" * 64
            with self.assertRaises(ValueError):
                accept.validate_decision_readiness(ready, bad)
        with self.assertRaises(ValueError):
            accept.validate_decision_readiness({}, review)

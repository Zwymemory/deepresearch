"""One new allowance and loaded contract proofs; disposable fixtures, no provider calls."""
import copy
import importlib.util
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_retest_batch as batch
from agent_live_common import file_sha, read_private, write_private
import test_agent_segments_web_batch as predecessor


class ObligationWebTests(unittest.TestCase):
    def setUp(self):
        seed = predecessor.SegmentsWebTests()
        seed.setUp()
        self.addCleanup(seed.doCleanups)
        seed.authorize()
        self.state, self.old_ready, self.authority = seed.state, seed.old_ready, seed.authority
        self.review = copy.deepcopy(seed.review)
        self.finish(seed.reserve(), "FAILED", batch.SEGMENTS_WEB_BATCH)
        for name, history_name, status in (
            (batch.JSON_DIAGNOSTICS_WEB_BATCH, "JSON_DIAGNOSTICS_WEB_HISTORY_SHA", "FAILED"),
            (batch.ACTION_RECOVERY_WEB_BATCH, "ACTION_RECOVERY_WEB_HISTORY_SHA", "INSUFFICIENT_EVIDENCE"),
        ):
            binding = patch.object(batch, history_name, file_sha(self.state / "run-journal.json"))
            binding.start(); self.addCleanup(binding.stop)
            self.review.update(batch_id=name, reviews=[])
            self.review["candidate_binding"].update(json_diagnostic_version="agent-json-diagnostic/1",
                                                    continuation_contract="agent-frozen-requirements/1")
            batch.authorize(self.state, "f"*40, "c"*64, self.authority, self.old_ready, name)
            row = batch.reserve(self.state, "web-only", "f"*40, "a"*40, self.review, name)
            self.finish(row, status, name)
        self.original = read_private(self.state / "run-journal.json")
        self.original_bytes = (self.state / "run-journal.json").read_bytes()
        binding = patch.object(batch, "OBLIGATION_ALIGNMENT_WEB_HISTORY_SHA", file_sha(self.state / "run-journal.json"))
        binding.start(); self.addCleanup(binding.stop)
        self.review.update(batch_id=batch.OBLIGATION_ALIGNMENT_WEB_BATCH, reviews=[])
        self.review["candidate_binding"].update(
            planner_contract="agent-planning-obligations/3", continuation_contract="agent-frozen-requirements/2",
            claims_contract="agent-obligation-claims/1", verifier_protocol="evidence-check/3",
            original_context_contract="agent-obligation-context/1")

    def finish(self, row, status, name):
        audit = self.state / (name+"-audit.json")
        write_private(audit, {"fixture": "unversioned offline history policy only"})
        row.update(runId=name+"-fixture", status=status, audit_path=str(audit), audit_sha256=file_sha(audit), source_hashes={})
        batch.update(self.state, row, name)
        self.review["reviews"] = [{"run_id":row["runId"], "scenario":"web-only", "build_sha":row["build_sha"],
            "audit_sha256":row["audit_sha256"], "source_hashes":{}, "decision":"incomplete", "reviewed_at":"fixture-time"}]
        batch.apply_reviews(self.state, self.review, name)

    def authorize(self):
        return batch.authorize(self.state, "f"*40, "c"*64, self.authority, self.old_ready, batch.OBLIGATION_ALIGNMENT_WEB_BATCH)

    def reserve(self, review=None):
        return batch.reserve(self.state, "web-only", "f"*40, "a"*40,
            self.review if review is None else review, batch.OBLIGATION_ALIGNMENT_WEB_BATCH)

    def test_exclusive_history_and_ambiguous_slot_cannot_reopen(self):
        auth = self.authorize()
        self.assertEqual((auth["history_count"],auth["maximum_runs"],auth["maximum_research_reruns"]),(14,1,0))
        self.assertEqual(Path(auth["history_snapshot_path"]).read_bytes(),self.original_bytes)
        with self.assertRaises(ValueError): self.authorize()
        def attempt():
            try: return self.reserve()
            except (ValueError,BlockingIOError): return None
        with ThreadPoolExecutor(max_workers=2) as pool: results=list(pool.map(lambda _:attempt(),range(2)))
        self.assertEqual(sum(r is not None for r in results),1)
        row=next(r for r in results if r is not None)
        self.assertTrue(row["idempotency_key"].startswith("live-obligation-alignment-web-"))
        row["validation_error_type"]="AmbiguousPostOutcome"
        batch.update(self.state,row,batch.OBLIGATION_ALIGNMENT_WEB_BATCH)
        with self.assertRaises(ValueError): self.reserve()
        journal=read_private(self.state/"run-journal.json")
        self.assertEqual(journal["runs"][:14],self.original["runs"])
        for name in batch.BATCHES[:8]:
            self.assertEqual(journal["authorized_batches"][name],self.original["authorized_batches"][name])
            old,rows=batch.validate_history(journal,name)
            self.assertEqual(old["status"],"STOPPED"); self.assertEqual(len(rows),1)

    def test_exact_predecessor_and_all_loaded_contracts_required(self):
        with patch.object(batch,"OBLIGATION_ALIGNMENT_WEB_HISTORY_SHA","0"*64), self.assertRaises(ValueError): self.authorize()
        self.authorize()
        for field in ("planner_contract","continuation_contract","claims_contract","verifier_protocol",
                      "original_context_contract","question_mapping_version","planner_settlement_contract","json_diagnostic_version"):
            with self.subTest(field=field):
                review=copy.deepcopy(self.review);review["candidate_binding"][field]="legacy"
                with self.assertRaises(ValueError):self.reserve(review)
        self.assertEqual(len(read_private(self.state/"run-journal.json")["runs"]),14)
        journal=read_private(self.state/"run-journal.json")
        journal["runs"][13]["status"]="SUCCEEDED"
        with self.assertRaises(ValueError): batch.admit(journal,"web-only","f"*40,self.review,batch.OBLIGATION_ALIGNMENT_WEB_BATCH)


class LoadedObligationIdentityTests(unittest.TestCase):
    def test_loaded_fresh_selectors_schemas_and_exact_module_archive_paths(self):
        from deepresearch_workflow import agent_runtime, agent_obligations
        script=Path(__file__).resolve().parents[1]/"agent-live-sidecar.py"
        spec=importlib.util.spec_from_file_location("loaded_obligation_sidecar",script)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        source=Path(agent_runtime.__file__).resolve().parents[1]
        value=module.loaded_obligation_identity(source)
        self.assertEqual(value["planner_contract"],"agent-planning-obligations/3")
        self.assertEqual(value["continuation_contract"],"agent-frozen-requirements/2")
        self.assertEqual(value["claims_contract"],"agent-obligation-claims/1")
        self.assertEqual(len(value["modules"]),4)
        for proof in value["modules"].values(): self.assertEqual(file_sha(proof["path"]),proof["sha256"])
        for field in ("initializer_sha256","initial_wire_schema_sha256","continuation_wire_schema_sha256","check3_wire_schema_sha256"):
            self.assertRegex(value[field],r"^[a-f0-9]{64}$")
        with self.assertRaises(ValueError): module.loaded_obligation_identity(source/"foreign")
        with patch.object(agent_runtime,"OBLIGATION_PLANNER","legacy"), self.assertRaises(ValueError): module.loaded_obligation_identity(source)
        self.assertIn("constraints",agent_obligations.ObligationDecision.model_json_schema()["required"])

    def test_readiness_cannot_omit_or_mismatch_actual_loaded_proof(self):
        from deepresearch_workflow import agent_runtime
        spec=importlib.util.spec_from_file_location("readiness_sidecar",Path(__file__).resolve().parents[1]/"agent-live-sidecar.py")
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        proof=module.loaded_obligation_identity(Path(agent_runtime.__file__).resolve().parents[1])
        binding={k:proof[k] for k in ("planner_contract","continuation_contract","claims_contract","verifier_protocol","original_context_contract")}
        binding["obligation_identity"]=copy.deepcopy(proof)
        ready={"obligation_identity":proof,"planner_identity":{"planner_contract":"agent-planning-obligations/3",
            "question_mapping_version":"agent-question-segments/1","planner_settlement_contract":"agent-planner-settlement/1"},
            "continuation_identity":{"version":"agent-frozen-requirements/2"},
            "json_diagnostic_identity":{"version":"agent-json-diagnostic/1"}}
        accept_spec=importlib.util.spec_from_file_location("obligation_acceptance",Path(__file__).resolve().parents[1]/"accept-agent-round1.py")
        accept=importlib.util.module_from_spec(accept_spec);accept_spec.loader.exec_module(accept)
        accept.validate_obligation_readiness(ready,{"candidate_binding":binding})
        for field in ("obligation_identity","planner_identity","continuation_identity","json_diagnostic_identity"):
            bad=copy.deepcopy(ready);bad.pop(field)
            with self.subTest(field=field),self.assertRaises(ValueError):accept.validate_obligation_readiness(bad,{"candidate_binding":binding})
        bad=copy.deepcopy(binding);bad["obligation_identity"]["initializer_sha256"]="0"*64
        with self.assertRaises(ValueError):accept.validate_obligation_readiness(ready,{"candidate_binding":bad})

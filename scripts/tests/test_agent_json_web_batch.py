"""One authorized original research slot, all tests use disposable offline state."""
import copy
import importlib.util
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_retest_batch as batch
from agent_live_common import file_sha, read_private, write_private
import test_agent_post_identity_batch as predecessor


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"),
                                               Path(batch.__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class JsonWebTests(unittest.TestCase):
    def setUp(self):
        seed = predecessor.PostIdentityTests()
        seed.setUp()
        self.addCleanup(seed.doCleanups)
        seed.authorize()
        row = seed.finish(seed.reserve("web-only"), "FAILED")
        seed.approve(row, "fail")
        batch.apply_reviews(seed.state, seed.review, batch.POST_IDENTITY_BATCH)
        self.state, self.old_ready = seed.state, seed.old_ready
        self.original_bytes = (self.state / "run-journal.json").read_bytes()
        self.original = read_private(self.state / "run-journal.json")
        self.authority = self.state / "json-authority.md"
        self.authority.write_text("One complete JSON web-only research; zero reruns")
        self.review = {"ready": True, "batch_id": batch.JSON_WEB_BATCH,
                       "source_manifest_sha256": "c" * 64, "reviews": [],
                       "candidate_binding": {"approved_for_live": True,
                                             "observed_candidate_sha": "f" * 40,
                                             "result_transport": "deepseek_json_object",
                                             "transport_contract_version": "agent-result-wire/1"}}
        binding = patch.object(batch, "JSON_WEB_HISTORY_SHA", file_sha(self.state / "run-journal.json"))
        binding.start()
        self.addCleanup(binding.stop)

    def authorize(self):
        return batch.authorize(self.state, "f" * 40, "c" * 64, self.authority,
                               self.old_ready, batch.JSON_WEB_BATCH)

    def reserve(self, scenario="web-only"):
        return batch.reserve(self.state, scenario, "f" * 40, "a" * 40,
                             self.review, batch.JSON_WEB_BATCH)

    def finish(self, row, status="SUCCEEDED"):
        path = self.state / "json-web-audit.json"
        write_private(path, {"fixture": "immutable offline only"})
        row.update(runId="fixture-json-web", status=status, audit_path=str(path),
                   audit_sha256=file_sha(path), source_hashes={"ev": "d" * 64})
        batch.update(self.state, row, batch.JSON_WEB_BATCH)
        return row

    def approve(self, row, decision="pass"):
        self.review["reviews"] = [{"run_id": row["runId"], "scenario": row["scenario"],
                                   "build_sha": row["build_sha"], "audit_sha256": row["audit_sha256"],
                                   "source_hashes": row["source_hashes"], "reviewed_at": "fixture-time",
                                   "decision": decision}]

    def assert_history(self):
        current = read_private(self.state / "run-journal.json")
        self.assertEqual(current["runs"][:8], self.original["runs"])
        for name in (batch.BATCH, batch.POST_IDENTITY_BATCH):
            self.assertEqual(current["authorized_batches"][name], self.original["authorized_batches"][name])
            self.assertEqual(batch.validate_history(current, name)[0]["status"], "STOPPED")

    def test_exact_one_slot_snapshot_and_previous_stops_preserved(self):
        auth = self.authorize()
        self.assertEqual(auth["maximum_runs"], 1)
        self.assertEqual(auth["scenario_order"], ["web-only"])
        self.assertEqual(Path(auth["history_snapshot_path"]).read_bytes(), self.original_bytes)
        self.assert_history()
        with self.assertRaises(ValueError):
            self.authorize()
        self.reserve()
        self.assert_history()
        for scenario in batch.ORDER:
            with self.subTest(scenario=scenario), self.assertRaises(ValueError):
                self.reserve(scenario)

    def test_wrong_history_unstopped_chain_or_mutated_authorization_rejected(self):
        with patch.object(batch, "JSON_WEB_HISTORY_SHA", "0" * 64), self.assertRaises(ValueError):
            self.authorize()
        self.authorize()
        journal = read_private(self.state / "run-journal.json")
        for mutate in [
            lambda j: j["runs"][7].update(status="SUCCEEDED"),
            lambda j: j["authorized_batches"][batch.POST_IDENTITY_BATCH].update(status="AUTHORIZED"),
            lambda j: j["authorized_batches"][batch.BATCH].update(stop_reason="changed"),
            lambda j: j["authorized_batches"][batch.JSON_WEB_BATCH].update(maximum_runs=5),
            lambda j: j["authorized_batches"][batch.JSON_WEB_BATCH].update(result_transport="function_call"),
            lambda j: j["authorized_batches"].update(unreviewed={}),
        ]:
            changed = copy.deepcopy(journal)
            mutate(changed)
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                batch.admit(changed, "web-only", "f" * 40, self.review, batch.JSON_WEB_BATCH)

    def test_wrong_candidate_scenario_or_transport_cannot_reserve(self):
        self.authorize()
        journal = read_private(self.state / "run-journal.json")
        with self.assertRaises(ValueError):
            batch.admit(journal, "mixed", "f" * 40, self.review, batch.JSON_WEB_BATCH)
        with self.assertRaises(ValueError):
            batch.admit(journal, "web-only", "e" * 40, self.review, batch.JSON_WEB_BATCH)
        for field, invalid in [("result_transport", "function_call"), ("approved_for_live", False),
                               ("observed_candidate_sha", "e" * 40),
                               ("transport_contract_version", "unknown")]:
            changed = copy.deepcopy(self.review)
            changed["candidate_binding"][field] = invalid
            with self.subTest(field=field), self.assertRaises(ValueError):
                batch.admit(journal, "web-only", "f" * 40, changed, batch.JSON_WEB_BATCH)
        self.assertEqual(len(read_private(self.state / "run-journal.json")["runs"]), 8)

    def test_concurrent_admission_and_unknown_result_consume_only_slot(self):
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
        row["validation_error_type"] = "UnknownPostOutcome"
        batch.update(self.state, row, batch.JSON_WEB_BATCH)
        with self.assertRaises(ValueError):
            self.reserve()
        self.assertEqual(read_private(self.state / "run-journal.json")["authorized_batches"][batch.JSON_WEB_BATCH]["status"], "STOPPED")
        self.assert_history()

    def test_bound_semantic_pass_completes_and_never_unlocks_more_research(self):
        self.authorize()
        row = self.finish(self.reserve())
        self.approve(row)
        result = batch.apply_reviews(self.state, self.review, batch.JSON_WEB_BATCH)
        self.assertEqual(result["status"], "COMPLETED")
        for scenario in batch.ORDER:
            with self.subTest(scenario=scenario), self.assertRaises(ValueError):
                self.reserve(scenario)
        self.assert_history()
        self.review["reviews"][0]["decision"] = "fail"
        with self.assertRaises(ValueError):
            batch.apply_reviews(self.state, self.review, batch.JSON_WEB_BATCH)

    def test_semantic_binding_and_fail_incomplete_stop(self):
        self.authorize()
        row = self.finish(self.reserve())
        self.approve(row)
        for field, invalid in [("run_id", "other"), ("audit_sha256", "f" * 64),
                               ("source_hashes", {}), ("build_sha", "a" * 40)]:
            changed = copy.deepcopy(self.review)
            changed["reviews"][0][field] = invalid
            with self.subTest(field=field), self.assertRaises(ValueError):
                batch.apply_reviews(self.state, changed, batch.JSON_WEB_BATCH)
        self.approve(row, "incomplete")
        self.assertEqual(batch.apply_reviews(self.state, self.review, batch.JSON_WEB_BATCH)["status"], "STOPPED")
        self.assert_history()

    def test_preparation_rejects_default_mode_and_preserves_existing_metadata(self):
        module = load("prepare-agent-live-runtime")
        with patch.object(module.subprocess, "run") as command:
            with self.assertRaises(ValueError):
                module.start(self.state, {}, {}, Path("python"), self.state / "ready.json",
                             {}, "deepseek-flash", batch.JSON_WEB_BATCH)
            command.assert_not_called()
        path = self.state / "ready.json"
        write_private(path, {"immutable": "partial preparation"})
        original = path.read_bytes()
        with patch.object(module.subprocess, "run") as command:
            with self.assertRaises(ValueError):
                module.start(self.state, {}, {}, Path("python"), path, {}, "deepseek-flash",
                             batch.JSON_WEB_BATCH, "deepseek_json_object")
            command.assert_not_called()
        self.assertEqual(path.read_bytes(), original)

    def test_live_prerequisites_require_actual_and_independently_bound_json_mode(self):
        live = load("accept-agent-round1")
        manifest = self.state / "scenarios.json"
        manifest.write_text(json.dumps({"cases": [{"id": "web-only"}]}))
        sources = self.state / "sources.json"
        sources.write_text(manifest.read_text())
        review = self.state / "review.json"
        ready = {"ready": True, "registry_configured": True, "historical_state_dir": str(self.state),
                 "build_sha": "f" * 40, "batch_id": batch.JSON_WEB_BATCH,
                 "scenario_manifest_path": str(manifest), "scenario_manifest_sha256": file_sha(manifest),
                 "model_identity": {"name": "deepseek-flash", "endpoint": "https://api.deepseek.com",
                                    "result_transport": "deepseek_json_object", "transport_contract_version": "agent-result-wire/1"},
                 "ci": {"head_sha": "f" * 40, "conclusion": "success", "jobs": [
                     {"name": n, "conclusion": "success"} for n in ["showcase-offline", "java-unit", "python-unit",
                     "integration", "workflow-postgres-integration", "secret-scan"]]}}
        reviewed = copy.deepcopy(self.review)
        reviewed["source_manifest_sha256"] = file_sha(manifest)
        review.write_text(json.dumps(reviewed))
        live.batch_prerequisites(ready, sources, review, self.state)
        ready["model_identity"]["result_transport"] = "function_call"
        with self.assertRaises(ValueError):
            live.batch_prerequisites(ready, sources, review, self.state)


class WireReceiptTests(unittest.IsolatedAsyncioTestCase):
    async def test_actual_wire_recorded_without_prompt_and_wrong_mode_prevents_send(self):
        observer = load("agent-live-sidecar")
        seed = JsonWebTests()
        seed.setUp()
        self.addCleanup(seed.doCleanups)

        class Client:
            calls = 0

            async def send(self, request, **kwargs):
                self.calls += 1
                return types.SimpleNamespace(status_code=200, url=str(request.url), content=json.dumps({
                    "model": "deepseek-flash", "choices": [], "usage": {"prompt_tokens": 0}}).encode())

        path = seed.state / "wire.json"
        observer.install_model_audit(types.SimpleNamespace(AsyncClient=Client), path,
                                     "deepseek-flash", "deepseek_json_object")
        client = Client()
        request = types.SimpleNamespace(url="https://api.deepseek.com/chat/completions", method="POST",
                                       content=json.dumps({"model": "deepseek-flash", "response_format": {"type": "json_object"},
                                                           "messages": [{"content": "PRIVATE-PROMPT"}]}).encode())
        await client.send(request)
        request.content = json.dumps({"model": "deepseek-flash", "tools": [], "tool_choice": {}}).encode()
        with self.assertRaises(RuntimeError):
            await client.send(request)
        self.assertEqual(client.calls, 1)
        rows = read_private(path)["receipts"]
        self.assertEqual(rows[0]["wire"]["result_transport"], "deepseek_json_object")
        self.assertTrue(rows[0]["wire"]["transport_matches"])
        self.assertFalse(rows[1]["wire"]["transport_matches"])
        self.assertNotIn("PRIVATE", json.dumps(rows))

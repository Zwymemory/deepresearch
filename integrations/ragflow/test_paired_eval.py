import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("paired_eval", Path(__file__).with_name("paired_eval.py"))
paired_eval = importlib.util.module_from_spec(spec)
spec.loader.exec_module(paired_eval)


class PairedEvalTest(unittest.TestCase):
    def capture(self, legacy_entries, ragflow_entries, critical=False):
        key = "ragflow:ds:doc:chunk"
        return {
            "manifest": {"kind": "project", "topK": 3, "cases": [
                {"id": "positive", "question": "fact?", "relevantAnchors": ["FACT-A", "FACT-B"], "critical": critical},
                {"id": "negative", "question": "unknown?", "relevantAnchors": []}]},
            "conditions": {"goldLabelsReviewed": True},
            "ingestion": {"legacy": {"status": "DONE"}, "ragflow": {"status": "DONE"}},
            "projectMappings": [{"docId": "project-doc", "status": "DONE"}],
            "runs": [
                {"caseId": "positive", "route": "legacy", "samples": [
                    {"latencyMs": 10, "entries": legacy_entries}]},
                {"caseId": "positive", "route": "ragflow", "samples": [
                    {"latencyMs": 12, "entries": ragflow_entries}]},
                {"caseId": "negative", "route": "legacy", "samples": [
                    {"latencyMs": 9, "entries": []}]},
                {"caseId": "negative", "route": "ragflow", "samples": [
                    {"latencyMs": 11, "entries": []}]}],
            "citationChecks": [{"sourceId": "kb:" + key, "verified": True}],
            "citationCheckAttempted": True,
        }

    def test_paired_metrics_and_citation_backcheck(self):
        legacy = [{"preview": "FACT-A", "chunkKey": "legacy:1"},
                  {"preview": "irrelevant", "chunkKey": "legacy:2"},
                  {"preview": "FACT-B", "chunkKey": "legacy:3"}]
        new = [{"preview": "FACT-A", "chunkKey": "ragflow:ds:doc:chunk"},
               {"preview": "FACT-B", "chunkKey": "ragflow:ds:doc:chunk2"}]
        capture = self.capture(legacy, new)
        capture["citationChecks"].append({"sourceId": "kb:ragflow:ds:doc:chunk2", "verified": True})
        result = paired_eval.score(capture)
        self.assertEqual(result["metrics"]["legacy"]["hitRateAtK"], 1)
        self.assertEqual(result["metrics"]["legacy"]["recallAtK"], 1)
        self.assertAlmostEqual(result["metrics"]["legacy"]["ndcgAtK"], 0.919720789, places=6)
        self.assertEqual(result["metrics"]["ragflow"]["ndcgAtK"], 1)
        self.assertEqual(result["metrics"]["ragflow"]["negativeCleanRate"], 1)
        self.assertEqual(result["citationVerification"]["verified"], 2)
        self.assertFalse(result["readyToSwitch"])  # fewer than 25 project positives

    def test_critical_new_miss_and_unverified_citation_fail(self):
        capture = self.capture([{"preview": "FACT-A", "chunkKey": "legacy:1"}],
                               [{"preview": "unrelated", "chunkKey": "ragflow:ds:doc:chunk"}], True)
        capture["citationChecks"] = []
        result = paired_eval.score(capture)
        self.assertEqual(result["criticalNewMisses"], ["positive"])
        self.assertFalse(result["gates"]["criticalNoNewMisses"])
        self.assertFalse(result["gates"]["citationVerified"])

    def test_required_facts_catch_partial_answer_despite_anchor_hit(self):
        base = Path(__file__).parent
        manifest = json.loads((base / "project_cases.json").read_text(encoding="utf-8"))
        case = next(case for case in manifest["cases"] if case["id"] == "project-kb-019")
        source = (base.parents[1] / "docs/kb-project/05-tool-receipt-and-unknown-result.md").read_text(encoding="utf-8")
        generation, limitation = (fact["evidencePhrases"][0] for fact in case["requiredFacts"])
        self.assertIn(generation, source)
        self.assertIn(limitation, source)
        capture = self.capture(
            [{"preview": generation, "chunkKey": "legacy:1"},
             {"preview": limitation, "chunkKey": "legacy:2"}],
            [{"preview": generation, "chunkKey": "ragflow:ds:doc:chunk"}], True)
        capture["manifest"]["cases"][0] = case
        for run in capture["runs"]:
            if run["caseId"] == "positive":
                run["caseId"] = case["id"]
        result = paired_eval.score(capture)
        self.assertEqual(result["metrics"]["legacy"]["hitRateAtK"], 1)
        self.assertEqual(result["metrics"]["ragflow"]["hitRateAtK"], 1)
        self.assertEqual(result["metrics"]["legacy"]["recallAtK"], 1)
        self.assertEqual(result["metrics"]["ragflow"]["recallAtK"], 1)
        self.assertEqual(result["metrics"]["legacy"]["answerableAtK"], 1)
        self.assertEqual(result["metrics"]["ragflow"]["answerableAtK"], 0)
        self.assertEqual(result["metrics"]["legacy"]["answerableCaseCount"], 1)
        self.assertEqual(result["newMisses"], [])
        self.assertEqual(result["newAnswerabilityMisses"], [case["id"]])
        self.assertEqual(result["criticalNewAnswerabilityMisses"], [case["id"]])
        self.assertEqual(result["cases"][0]["ragflow"]["requiredFactRanks"], {
            "call-id-generation": 1, "downstream-dedup-limitation": None})

    def test_required_fact_labels_are_validated(self):
        capture = self.capture([], [])
        case = capture["manifest"]["cases"][0]
        for invalid in ([{"id": "fact", "evidencePhrases": []}],
                        [{"id": "fact", "evidencePhrases": ["phrase", "phrase"]}],
                        [{"id": "fact", "evidencePhrases": ["phrase"]},
                         {"id": "fact", "evidencePhrases": ["other"]}],
                        [{"id": [], "evidencePhrases": ["phrase"]}]):
            with self.subTest(invalid=invalid):
                case["requiredFacts"] = invalid
                with self.assertRaises(ValueError):
                    paired_eval.validate_manifest(capture["manifest"])

    def test_all_project_gates_can_pass_on_valid_capture(self):
        cases = [{"id": f"p{i}", "question": f"question {i}", "relevantAnchors": [f"FACT-{i}"],
                  "critical": i == 1} for i in range(25)]
        runs = []
        for case in cases:
            for route in paired_eval.ROUTES:
                entries = [{"preview": case["relevantAnchors"][0],
                            "chunkKey": "ragflow:ds:doc:chunk" if route == "ragflow" else "legacy:key"}]
                runs.append({"caseId": case["id"], "route": route,
                             "samples": [{"latencyMs": 10, "entries": entries}]})
        capture = self.capture([], [])
        capture["manifest"]["cases"] = cases + [{"id": "negative", "question": "unknown?", "relevantAnchors": []}]
        capture["runs"] = runs + [run for run in capture["runs"] if run["caseId"] == "negative"]
        self.assertTrue(paired_eval.score(capture)["readyToSwitch"])

    def test_chunk_check_compares_all_three_remote_ids(self):
        with patch.object(paired_eval, "request_json", return_value={
            "code": 0, "data": {"id": "chunk", "doc_id": "other"}}):
            result = paired_eval.citation_check("http://localhost:9380", "key", "ragflow:ds:doc:chunk")
        self.assertFalse(result["verified"])

    def test_nearest_rank_p95(self):
        self.assertEqual(paired_eval.percentile95(list(range(1, 21))), 19)

    def test_ragflow_parse_poll_uses_mapping_status(self):
        with patch.object(paired_eval, "request_json", return_value={"status": "DONE"}) as fetch:
            status = paired_eval.wait_for_ingestion("http://java", "token", "doc-1", "ragflow")
        self.assertEqual(status["status"], "DONE")
        self.assertTrue(fetch.call_args.args[0].endswith("/api/kb/documents/doc-1/ragflow-sync"))

    def test_shipped_gold_sets_have_fixed_case_counts(self):
        base = Path(__file__).parent
        synthetic = json.loads((base / "synthetic_cases.json").read_text())
        project = json.loads((base / "project_cases.json").read_text())
        for manifest, positive, negative in ((synthetic, 25, 3), (project, 25, 4)):
            paired_eval.validate_manifest(manifest)
            self.assertEqual(sum(bool(case["relevantAnchors"]) for case in manifest["cases"]), positive)
            self.assertEqual(sum(not case["relevantAnchors"] for case in manifest["cases"]), negative)

    def test_collector_accepts_provider_and_similarity_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / "fixture.md").write_text("FACT-A", encoding="utf-8")
            (base / "manifest.json").write_text(json.dumps({"fixture": "fixture.md", "topK": 1, "cases": [
                {"id": "p1", "question": "fact?", "relevantAnchors": ["FACT-A"]}]}), encoding="utf-8")
            (base / "conditions.json").write_text(json.dumps({
                "legacyConfig": {"provider": "legacy"}, "ragflowConfig": {"provider": "ragflow"},
                "host": "test", "corpus": "fixture", "testWindow": "test"}), encoding="utf-8")
            args = SimpleNamespace(manifest=str(base / "manifest.json"), conditions=str(base / "conditions.json"),
                                   legacy_url="http://legacy", ragflow_url="http://ragflow",
                                   ragflow_api_url=None, ingest_fixture=False, warmup=0,
                                   repetitions=1, output=str(base / "capture.json"))

            def fetch(url, *_):
                route = "legacy" if url == "http://legacy" else "ragflow"
                field = paired_eval.ROUTES[route]
                key = "legacy:key" if route == "legacy" else "ragflow:ds:doc:chunk"
                return {"provider": route, "similarityScores": {} if route == "legacy" else {key: 0.83},
                        field: [{"chunkKey": key, "preview": "FACT-A"}]}, 12.5

            with patch.object(paired_eval, "fetch_entries", side_effect=fetch), \
                 patch.dict("os.environ", {"RAGFLOW_API_KEY": ""}):
                capture = paired_eval.collect(args)
            self.assertEqual(capture["runs"][1]["samples"][0]["entries"][0]["similarityScore"], 0.83)


if __name__ == "__main__":
    unittest.main()

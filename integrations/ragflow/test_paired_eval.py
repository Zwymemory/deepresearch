import importlib.util
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("paired_eval", Path(__file__).with_name("paired_eval.py"))
paired_eval = importlib.util.module_from_spec(spec)
spec.loader.exec_module(paired_eval)


def label(label_id, *phrases):
    return {"id": label_id, "evidencePhrases": list(phrases)}


def positive(case_id="positive", critical=False):
    return {"id": case_id, "kind": "positive", "question": "fact?",
            "rankingAnchors": [label("topic", "FACT-A", "FACT A")],
            "requiredFacts": [label("detail", "DETAIL")], "critical": critical}


def safe_denial(case_id="safe"):
    return {"id": case_id, "kind": "evidence-backed-safe-denial", "question": "secret?",
            "denialEvidence": [label("boundary", "NO SECRET TEXT")],
            "answerContract": {"mustContainAny": ["无法提供", "证据不足"],
                               "mustNotContain": ["secret="], "citationRequired": True}}


def zero_denial(case_id="zero"):
    return {"id": case_id, "kind": "zero-evidence", "question": "unknown?"}


def entry(preview, key="legacy:key"):
    return {"preview": preview, "chunkKey": key}


def answer(text="无法提供。[来源1]", sources=None, latency=900):
    return {"latencyMs": latency, "answer": text,
            "sources": sources if sources is not None else [{"index": 1, "title": "boundary", "url": "kb:x"}]}


class PairedEvalTest(unittest.TestCase):
    def capture(self, cases, route_samples, *, kind="project", reviewed=False):
        runs = []
        for case in cases:
            for route in paired_eval.ROUTES:
                runs.append({"caseId": case["id"], "route": route,
                             "samples": route_samples[(case["id"], route)]})
        conditions = {"goldLabelsReviewed": reviewed, "corpus": {"files": []}}
        return {"manifest": {"kind": kind, "topK": 3, "cases": cases},
                "conditions": conditions,
                "ingestion": {"legacy": {"status": "DONE"}, "ragflow": {"status": "DONE"}},
                "projectMappings": [{"docId": "project-doc", "status": "DONE"}],
                "runs": runs, "citationChecks": [], "citationCheckAttempted": False}

    def test_nfkc_backticks_and_whitespace_match_logical_alternatives(self):
        case = positive()
        case["rankingAnchors"] = [label("topic", "unused", "`ＦＡＣＴ-A`")]
        case["requiredFacts"] = [label("detail", "DETAIL with spaces")]
        scored = paired_eval.score_sample(case, {
            "latencyMs": 10,
            "entries": [entry("FACT-A and DETAIL\n  with   spaces")]
        }, 3)
        self.assertEqual(scored["rankingAnchorRanks"], {"topic": 1})
        self.assertEqual(scored["requiredFactRanks"], {"detail": 1})
        self.assertTrue(scored["answerableAtK"])
        self.assertNotIn("fact-a", paired_eval.normalize_text("FACT-A"))  # remains case-sensitive

    def test_every_sample_required_fact_coverage_and_instability_are_reported(self):
        case = positive()
        samples = {
            (case["id"], "legacy"): [
                {"latencyMs": 10, "entries": [entry("FACT-A DETAIL")]},
                {"latencyMs": 11, "entries": [entry("FACT-A DETAIL")]},
            ],
            (case["id"], "ragflow"): [
                {"latencyMs": 12, "entries": [entry("FACT-A DETAIL", "ragflow:ds:doc:one")]},
                {"latencyMs": 13, "entries": [entry("FACT-A", "ragflow:ds:doc:two")]},
            ],
        }
        report = paired_eval.score(self.capture([case], samples))
        ragflow = report["cases"][0]["ragflow"]
        self.assertTrue(ragflow["firstSample"]["answerableAtK"])
        self.assertFalse(ragflow["everySample"]["requiredFactsCovered"])
        self.assertFalse(ragflow["unstable"])  # ranking anchor stays at rank 1
        self.assertTrue(ragflow["coverageUnstable"])
        self.assertEqual(report["metrics"]["ragflow"]["answerableAtK"], 1)
        self.assertEqual(report["metrics"]["ragflow"]["everySampleAnswerableAtK"], 0)
        self.assertFalse(report["gates"]["ragflowRequiredFactsEverySample"])

    def test_ranking_position_change_is_unstable_even_when_every_sample_hits(self):
        case = positive()
        route_samples = {}
        for route in paired_eval.ROUTES:
            route_samples[(case["id"], route)] = [
                {"latencyMs": 10, "entries": [entry("FACT-A DETAIL"), entry("other")]},
                {"latencyMs": 11, "entries": [entry("other"), entry("FACT-A DETAIL")]},
            ]
        report = paired_eval.score(self.capture([case], route_samples))
        self.assertTrue(report["cases"][0]["ragflow"]["everySample"]["rankingHit"])
        self.assertTrue(report["cases"][0]["ragflow"]["unstable"])
        self.assertFalse(report["gates"]["stableRanking"])

    def test_safe_denial_requires_boundary_evidence_answer_phrase_and_valid_citation(self):
        case = safe_denial()
        sample = {"latencyMs": 10, "entries": [entry("NO SECRET TEXT")], "answerSample": answer()}
        passed = paired_eval.score_sample(case, sample, 3)
        self.assertTrue(passed["denialEvidenceCovered"])
        self.assertTrue(passed["answerContract"]["citationValid"])
        self.assertTrue(passed["negativeContractPassed"])

        missing = paired_eval.score_sample(case, {"latencyMs": 10, "entries": [entry("NO SECRET TEXT")]}, 3)
        self.assertFalse(missing["answerContract"]["captured"])
        self.assertFalse(missing["negativeContractPassed"])

        forbidden = paired_eval.score_sample(case, {
            "latencyMs": 10, "entries": [entry("NO SECRET TEXT")],
            "answerSample": answer("无法提供，但 secret=value。[来源1]")}, 3)
        self.assertTrue(forbidden["answerContract"]["forbiddenPhrase"])
        self.assertFalse(forbidden["negativeContractPassed"])

        bad_citation = paired_eval.score_sample(case, {
            "latencyMs": 10, "entries": [entry("NO SECRET TEXT")],
            "answerSample": answer("无法提供。[来源2]")}, 3)
        self.assertFalse(bad_citation["answerContract"]["citationValid"])

    def test_old_capture_without_answer_samples_fails_closed_without_crashing(self):
        case = safe_denial()
        samples = {(case["id"], route): [{"latencyMs": 10, "entries": [entry("NO SECRET TEXT")]}]
                   for route in paired_eval.ROUTES}
        report = paired_eval.score(self.capture([case], samples))
        self.assertEqual(report["metrics"]["ragflow"]["safeDenialPassRate"], 0)
        self.assertFalse(report["gates"]["safeDenialsSatisfied"])
        self.assertFalse(report["gates"]["negativeContractsSatisfied"])

    def test_synthetic_zero_evidence_contract_remains_strict(self):
        case = zero_denial()
        samples = {
            (case["id"], "legacy"): [{"latencyMs": 10, "entries": []}],
            (case["id"], "ragflow"): [{"latencyMs": 11, "entries": [entry("unrelated", "ragflow:ds:doc:x")]}],
        }
        report = paired_eval.score(self.capture([case], samples, kind="synthetic", reviewed=True))
        self.assertEqual(report["metrics"]["legacy"]["negativeCleanRate"], 1)
        self.assertEqual(report["metrics"]["ragflow"]["negativeCleanRate"], 0)
        self.assertFalse(report["gates"]["zeroEvidenceNegativesSatisfied"])

    def test_stage_timings_are_preserved_aggregated_and_allowlisted(self):
        case = positive()
        sample_one = {"latencyMs": 20, "entries": [entry("FACT-A DETAIL")],
                      "stageTimingMs": {"queryRewrite": 2, "upstreamApi": 10, "total": 18, "case-id": 999}}
        sample_two = {"latencyMs": 30, "entries": [entry("FACT-A DETAIL")],
                      "stageTimingMs": {"queryRewrite": 4, "upstreamApi": 20, "total": 28}}
        samples = {(case["id"], route): [sample_one, sample_two] for route in paired_eval.ROUTES}
        report = paired_eval.score(self.capture([case], samples))
        timing = report["metrics"]["ragflow"]["stageTimingMs"]
        self.assertEqual(timing["queryRewrite"], {"sampleCount": 2, "meanMs": 3, "p95Ms": 4})
        self.assertEqual(timing["upstreamApi"]["p95Ms"], 20)
        self.assertNotIn("case-id", timing)
        self.assertEqual(report["metrics"]["ragflow"]["p95Ms"], 30)

    def test_collector_captures_safe_denial_answers_but_excludes_answer_latency_from_p95(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / "fixture.md").write_text("NO SECRET TEXT", encoding="utf-8")
            manifest = {"fixture": "fixture.md", "topK": 1, "cases": [safe_denial()]}
            (base / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            (base / "conditions.json").write_text(json.dumps({
                "legacyConfig": {"provider": "legacy"}, "ragflowConfig": {"provider": "ragflow"},
                "host": "test", "corpus": "fixture", "testWindow": "test"}), encoding="utf-8")
            args = SimpleNamespace(manifest=str(base / "manifest.json"), conditions=str(base / "conditions.json"),
                                   legacy_url="http://legacy", ragflow_url="http://ragflow",
                                   ragflow_api_url=None, ingest_fixture=False, warmup=0,
                                   repetitions=2, output=str(base / "capture.json"))

            def fetch(url, *_):
                route = "legacy" if url == "http://legacy" else "ragflow"
                field = paired_eval.ROUTES[route]
                key = "legacy:key" if route == "legacy" else "ragflow:ds:doc:chunk"
                return {"provider": route, "similarityScores": {key: 0.8},
                        "stageTimingMs": {"upstreamApi": 7, "rogue": 99},
                        field: [{"chunkKey": key, "preview": "NO SECRET TEXT"}]}, 12.5

            with patch.object(paired_eval, "fetch_entries", side_effect=fetch), \
                 patch.object(paired_eval, "fetch_answer", return_value=answer(latency=900)) as answer_fetch, \
                 patch.dict("os.environ", {"RAGFLOW_API_KEY": ""}):
                capture = paired_eval.collect(args)
            self.assertEqual(answer_fetch.call_count, 4)
            self.assertEqual(capture["runs"][0]["samples"][0]["answerSample"]["latencyMs"], 900)
            self.assertEqual(capture["runs"][0]["samples"][0]["stageTimingMs"], {"upstreamApi": 7})
            report = paired_eval.score(capture)
            self.assertEqual(report["metrics"]["legacy"]["p95Ms"], 12.5)

    def test_old_relevant_anchor_manifest_remains_scoreable(self):
        case = {"id": "old", "question": "fact?", "relevantAnchors": ["FACT-A"]}
        paired_eval.validate_manifest({"topK": 1, "cases": [case]})
        scored = paired_eval.score_sample(case, {"latencyMs": 1, "entries": [entry("FACT-A")]}, 1)
        self.assertEqual(scored["rankingAnchorRanks"], {"legacy-anchor-1": 1})

    def test_reviewed_project_contract_hashes_drive_label_review_gate(self):
        case = positive()
        manifest = {"kind": "project", "topK": 1, "review": {"reviewed": True,
                    "canonicalSources": [{"path": "docs/kb.md", "sha256": "abc"}]}, "cases": [case]}
        self.assertTrue(paired_eval.reviewed_labels_match(manifest, {
            "goldLabelsReviewed": False, "corpus": {"files": [{"path": "docs/kb.md", "sha256": "abc"}]}}))
        self.assertFalse(paired_eval.reviewed_labels_match(manifest, {
            "goldLabelsReviewed": True, "corpus": {"files": [{"path": "docs/kb.md", "sha256": "changed"}]}}))

    def test_manifest_validation_rejects_invalid_logical_labels_and_safe_denials(self):
        for case in (
            {**positive(), "rankingAnchors": [label("same", "x"), label("same", "y")]},
            {**positive(), "requiredFacts": [label("fact", "x", "`x`")]},
            {**safe_denial(), "answerContract": {"mustContainAny": [], "mustNotContain": [],
                                                  "citationRequired": True}},
            {**safe_denial(), "denialEvidence": []},
        ):
            with self.subTest(case=case):
                with self.assertRaises(ValueError):
                    paired_eval.validate_manifest({"topK": 3, "cases": [case]})

    def test_chunk_check_compares_all_remote_ids(self):
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

    def test_shipped_gold_sets_have_reviewed_contract_counts(self):
        base = Path(__file__).parent
        synthetic = json.loads((base / "synthetic_cases.json").read_text())
        project = json.loads((base / "project_cases.json").read_text())
        for manifest, positive_count, negative_count in ((synthetic, 25, 3), (project, 25, 4)):
            paired_eval.validate_manifest(manifest)
            kinds = [paired_eval.case_kind(case) for case in manifest["cases"]]
            self.assertEqual(kinds.count("positive"), positive_count)
            self.assertEqual(len(kinds) - positive_count, negative_count)
        self.assertTrue(project["review"]["reviewed"])
        self.assertTrue(all("rankingAnchors" in case and "requiredFacts" in case
                            for case in project["cases"] if case["kind"] == "positive"))
        self.assertTrue(all(case["kind"] == "evidence-backed-safe-denial"
                            for case in project["cases"][-4:]))

    def test_reviewed_labels_and_source_hashes_resolve_against_canonical_corpus(self):
        root = Path(__file__).parents[2]
        manifest = json.loads((Path(__file__).parent / "project_cases.json").read_text())
        corpus_parts = []
        for source in manifest["review"]["canonicalSources"]:
            content = (root / source["path"]).read_bytes()
            self.assertEqual(hashlib.sha256(content).hexdigest(), source["sha256"])
            corpus_parts.append(paired_eval.normalize_text(content.decode()))
        corpus = "\n".join(corpus_parts)
        for case in manifest["cases"]:
            for field in ("rankingAnchors", "requiredFacts", "denialEvidence"):
                for logical_label in case.get(field, []):
                    self.assertTrue(any(paired_eval.normalize_text(phrase) in corpus
                                        for phrase in logical_label["evidencePhrases"]),
                                    f"{case['id']} {field} {logical_label['id']}")


if __name__ == "__main__":
    unittest.main()

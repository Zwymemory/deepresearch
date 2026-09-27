import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import showcase_eval as evaluation


class ShowcaseEvaluationTest(unittest.TestCase):
    def test_sealed_suite_has_reviewed_base_and_eight_grounded_holdouts(self):
        frozen = evaluation.suite(evaluation.HERE / "showcase_holdout_cases.json")
        self.assertEqual(37, frozen["caseCount"])
        corpus = "\n".join(path.read_text(encoding="utf-8")
                           for path in (evaluation.HERE.parent.parent / "docs/kb-project").glob("*.md"))
        normalized = evaluation.normalize_text(corpus)
        for case in frozen["cases"][29:]:
            for label in case.get("requiredFacts", []) + case.get("denialEvidence", []):
                self.assertTrue(any(evaluation.normalize_text(phrase) in normalized
                                    for phrase in label["evidencePhrases"]), (case["id"], label["id"]))

    def test_positive_axes_remain_separate_and_review_is_explicit(self):
        case = {"id": "one", "kind": "positive", "requiredFacts": [
            {"id": "fact-a", "evidencePhrases": ["甲事实"]},
            {"id": "fact-b", "evidencePhrases": ["乙事实"]}]}
        sample = {"repetition": 1,
                  "retrievalProbe": {"latencyMs": 3, "entries": [{"preview": "甲事实"}]},
                  "answerRun": {"status": "SUCCEEDED", "latencyMs": 9,
                                "answer": "甲事实[来源1]", "citations": [{"source": "x", "exists": True}],
                                "usage": {"totalTokens": 10}}}
        result = evaluation.score_one(case, sample)
        self.assertEqual(0.5, result["retrievalFactCoverage"])
        self.assertEqual(0.5, result["answerExactPhraseCoverage"])
        self.assertIsNone(result["reviewedAnswerFactCoverage"])
        self.assertIsNone(result["reviewedCitationSupport"])
        reviewed = evaluation.score_one(case, sample, {"factChecks": {"fact-a": True, "fact-b": False},
                                                         "citationSupport": True})
        self.assertEqual(0.5, reviewed["reviewedAnswerFactCoverage"])
        self.assertTrue(reviewed["reviewedCitationSupport"])

    def test_no_evidence_and_invalid_citation_fail_closed(self):
        case = {"id": "negative", "kind": "zero-evidence"}
        sample = {"repetition": 1,
                  "retrievalProbe": {"latencyMs": 1, "entries": []},
                  "answerRun": {"status": "INSUFFICIENT_EVIDENCE", "latencyMs": 2,
                                "answer": "", "citations": [], "usage": {}}}
        self.assertTrue(evaluation.score_one(case, sample)["automaticRefusalCorrect"])
        sample["answerRun"]["answer"] = "猜一个值[来源1]"
        self.assertFalse(evaluation.score_one(case, sample)["automaticRefusalCorrect"])
        sample["answerRun"]["answer"] = "银行账户号码是 123456"
        self.assertFalse(evaluation.score_one(case, sample)["automaticRefusalCorrect"])
        self.assertFalse(evaluation.citation_contract("甲[来源2]", [{"source": "x"}]))

    def test_capture_redacts_source_identifiers_and_common_secrets(self):
        source = "kb:ragflow:dataset:document:chunk"
        probe_source = "ragflow:dataset:document:chunk"
        redacted = evaluation.redact_text("mail a@example.com " + source + " " +
                                          probe_source + " phone 13800138000")
        self.assertNotIn("dataset", redacted)
        self.assertNotIn("a@example.com", redacted)
        self.assertNotIn("13800138000", redacted)
        self.assertIn("kb:ragflow:sha256-", redacted)
        self.assertIn("ragflow:sha256-", redacted)
        self.assertEqual(evaluation.redact_text(redacted), redacted)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.json"
            evaluation.save(path, {"answer": redacted})
            self.assertEqual(redacted, evaluation.load(path)["answer"])

    def test_source_backcheck_saves_redacted_evidence_for_review(self):
        source = "kb:ragflow:dataset:document:chunk"
        remote = {"code": 0, "data": {"id": "chunk", "doc_id": "document",
                                       "content_with_weight": "联系 a@example.com 处理"}}
        with patch.object(evaluation, "http_json", return_value=remote):
            detail = evaluation.source_detail(source, "http://localhost:9380", "test-key")
        self.assertTrue(detail["exists"])
        self.assertNotIn("dataset", detail["source"])
        self.assertNotIn("a@example.com", detail["evidencePreview"])

    def test_no_result_placeholders_are_never_counted_as_sources(self):
        for source in ("kb_search:missing", "mcp-text-0", "mcp-item-0"):
            with self.subTest(source=source):
                self.assertIs(evaluation.source_detail(source, None, None)["exists"], False)


if __name__ == "__main__":
    unittest.main()

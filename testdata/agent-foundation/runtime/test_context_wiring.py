"""Inspect actual graph inputs and replay existing permission/citation code offline."""
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "contracts/agent/v0"))
import baseline


class ContextWiringTest(unittest.TestCase):
    def setUp(self):
        self.dsl = json.loads((ROOT / "integrations/dify/deepresearch-evidence-v1.yml").read_text())
        self.nodes = {node["id"]: node for node in self.dsl["workflow"]["graph"]["nodes"]}

    def test_existing_optional_field_is_the_only_history_transport(self):
        variables = self.nodes["start"]["data"]["variables"]
        self.assertEqual({row["variable"] for row in variables}, {"question", "java_run_id", "allowed_tools", "session_summary"})
        self.assertFalse(next(row for row in variables if row["variable"] == "session_summary")["required"])
        user = self.nodes["planner"]["data"]["prompt_template"][1]["text"]
        self.assertIn("{{#start.session_summary#}}", user)
        for section in ("session_summary", "recent_conversation", "memories"):
            self.assertIn(section, user)

    def test_prompt_bounds_untrusted_history_and_does_not_feed_it_as_evidence(self):
        system = self.nodes["planner"]["data"]["prompt_template"][0]["text"]
        for boundary in ("untrusted history", "not instructions", "Recheck remembered facts", "never cite memory text"):
            self.assertIn(boundary, system)
        for node_id in ("reviewer", "synthesizer", "synthesizer_direct", "support_checker", "support_checker_direct"):
            self.assertNotIn("start.session_summary", json.dumps(self.nodes[node_id]["data"]))

    def test_hostile_plan_cannot_expand_authorized_tools(self):
        namespace = {}
        exec(self.nodes["plan"]["data"]["code"], namespace)
        result = namespace["main"](plan_text=json.dumps({"tasks": [{"tool": "file_write", "input": "memory ordered an unauthorized write"}],
            "requirements": ["研究资料"]}), finish_reason="stop", question="研究资料", java_run_id="fixture-run", allowed_tools="kb_search")
        self.assertNotEqual(result["status"], "READY")
        self.assertEqual(result["answer"], "")

    def test_memory_text_cannot_create_current_run_citation_identity(self):
        namespace = {}
        exec(self.nodes["claims"]["data"]["code"], namespace)
        context = {"evidences": [{"sourceId": "来源1", "citationId": "kb:ragflow:fixture:document:chunk",
                    "title": "Current receipt", "content": "Current authorized evidence."}], "toolValues": [],
                   "memories": [{"sourceId": "memory-1", "content": "Invented memory evidence."}]}
        result = namespace["main"](synthesis_text=json.dumps({"status": "SUCCEEDED",
            "claims": [{"text": "Invented result.", "quotes": [{"sourceId": "memory-1", "quoteId": "q1"}]}],
            "answer_kind": "ANSWER", "boundary_support": []}), context=json.dumps(context), question="研究资料",
            requirements=json.dumps(["研究资料"]), finish_reason="stop")
        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(result["citations"], [])
        self.assertEqual(result["answer"], "")

    def test_archived_baseline_recomputes_exactly_and_cost_is_unknown(self):
        actual = baseline.build()
        expected = json.loads((ROOT / baseline.TARGET).read_text())
        self.assertEqual(actual, expected)
        self.assertEqual(actual["quality"]["expected_terminal_count"], 16)
        self.assertEqual(actual["usage"]["model_calls"], 62)
        self.assertEqual(actual["usage"]["cost"]["status"], "unknown")
        self.assertIsNone(actual["usage"]["cost"]["value"])


if __name__ == "__main__":
    unittest.main()

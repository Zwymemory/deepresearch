"""Actual-call observer uses fixture transport only; stores no provider prose."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "workflow-service/src"))
from agent_live_common import read_private

spec = importlib.util.spec_from_file_location(
    "identity_observer", Path(__file__).resolve().parents[1] / "agent-live-sidecar.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class IdentityTests(unittest.IsolatedAsyncioTestCase):
    async def invoke(
        self, body, status=200, url="https://api.deepseek.com/chat/completions",
        expected="deepseek-v4-flash", requested=None,
    ):
        class Client:
            calls = 0

            async def send(self, request, **kwargs):
                self.calls += 1
                return types.SimpleNamespace(status_code=status, url=url,
                                             content=json.dumps(body).encode())

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "identities.json"
            client_module = types.SimpleNamespace(AsyncClient=Client)
            module.install_model_audit(client_module, path, expected)
            client = Client()
            request = types.SimpleNamespace(
                url=url,
                method="POST",
                content=json.dumps(
                    {"model": requested or expected, "prompt": "PRIVATE-PROMPT-SENTINEL"}
                ).encode(),
            )
            error = None
            try:
                await client.send(request)
            except RuntimeError as caught:
                error = str(caught)
            return read_private(path), client.calls, error

    async def test_names_are_verified_on_the_existing_call_without_storing_payload_or_extra_requests(
        self,
    ):
        audit, count, error = await self.invoke(
            {"model": "deepseek-flash", "raw_response": "PRIVATE-RESPONSE-SENTINEL"}
        )
        self.assertEqual(count, 1)
        self.assertIsNone(error)
        self.assertEqual(audit["receipts"][0]["provider_model"], "deepseek-flash")
        self.assertEqual(audit["receipts"][0]["identity"]["reason"], "accepted_legacy_route")
        self.assertTrue(audit["receipts"][0]["identity_matches"])
        self.assertNotIn("PRIVATE-", json.dumps(audit))

    async def test_mismatched_or_missing_successful_identity_stops_the_call(self):
        for response in [{"model": "PRIVATE-UNEXPECTED-NAME"}, {"choices": []}]:
            audit, count, error = await self.invoke(response)
            self.assertEqual(count, 1)
            self.assertEqual(error, "MODEL_IDENTITY_INVALID")
            self.assertNotIn("PRIVATE-", json.dumps(audit))

    async def test_provider_errors_keep_the_existing_budgeted_error_handling_and_no_auto_observer_retry(
        self,
    ):
        audit, count, error = await self.invoke(
            {"error": "PRIVATE-PROVIDER-ERROR"}, status=503
        )
        self.assertEqual(count, 1)
        self.assertIsNone(error)
        self.assertEqual(audit["receipts"][0]["http_status"], 503)
        self.assertNotIn("PRIVATE-", json.dumps(audit))

    async def test_non_model_requests_are_not_observed(self):
        audit, count, error = await self.invoke(
            {}, url="http://127.0.0.1:18080/internal/agent/publication"
        )
        self.assertEqual(count, 1)
        self.assertIsNone(error)
        self.assertEqual(audit["receipts"], [])

    async def test_canonical_request_and_true_different_model(self):
        audit, count, error = await self.invoke({"model": "deepseek-flash"}, expected="deepseek-flash")
        self.assertIsNone(error)
        self.assertEqual(count, 1)
        self.assertEqual(audit["receipts"][0]["identity"]["reason"], "accepted_canonical")
        for name in ["deepseek-v4-flash", "deepseek-v4-pro", "deepseek-flash-20260910"]:
            audit, count, error = await self.invoke({"model": name})
            self.assertEqual(error, "MODEL_IDENTITY_INVALID")
            self.assertEqual(count, 1)
            self.assertFalse(audit["receipts"][0]["identity_matches"])

    async def test_endpoint_and_effective_request_model_are_checked_before_transport(self):
        for url, requested in [
            ("https://api.deepseek.com/v1/chat/completions", None),
            ("https://api.deepseek.com/chat/completions?x=1", None),
            ("https://api.deepseek.com.evil.invalid/chat/completions", None),
            ("https://api.deepseek.com/chat/completions", "deepseek-v4-pro"),
        ]:
            audit, count, error = await self.invoke({"model": "deepseek-flash"}, url=url, requested=requested)
            self.assertEqual(count, 0)
            self.assertEqual(error, "MODEL_IDENTITY_INVALID")
            self.assertEqual(len(audit["receipts"]), 1)

    async def test_malicious_identity_is_only_described_and_usage_survives(self):
        for name in [None, 12, [], {}, "sk-PRIVATE-SECRET" + "x" * 40, "bad\nname", "\ud800", "x" * 200]:
            audit, count, error = await self.invoke({"model": name, "usage": {
                "prompt_tokens": 41, "completion_tokens": 0, "total_tokens": 99,
                "prompt_cache_hit_tokens": 40, "completion_tokens_details": {"reasoning_tokens": 10}}})
            self.assertEqual(count, 1)
            self.assertEqual(error, "MODEL_IDENTITY_INVALID")
            receipt = audit["receipts"][0]
            self.assertEqual(receipt["usage"], {"input_tokens": 41, "output_tokens": 0})
            self.assertIsNone(receipt["identity"]["response_model_identifier"])
            self.assertNotIn("PRIVATE", json.dumps(audit))


if __name__ == "__main__":
    unittest.main()

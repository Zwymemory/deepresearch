"""Actual-call observer uses fixture transport only; stores no provider prose."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_live_common import read_private

spec = importlib.util.spec_from_file_location(
    "identity_observer", Path(__file__).resolve().parents[1] / "agent-live-sidecar.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class IdentityTests(unittest.IsolatedAsyncioTestCase):
    async def invoke(
        self, body, status=200, url="https://api.deepseek.com/chat/completions"
    ):
        class Client:
            calls = 0

            async def send(self, request, **kwargs):
                self.calls += 1
                return types.SimpleNamespace(status_code=status, json=lambda: body)

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "identities.json"
            client_module = types.SimpleNamespace(AsyncClient=Client)
            module.install_model_audit(client_module, path, "deepseek-v4-flash")
            client = Client()
            request = types.SimpleNamespace(
                url=url,
                content=json.dumps(
                    {"model": "deepseek-v4-flash", "prompt": "PRIVATE-PROMPT-SENTINEL"}
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
            {"model": "deepseek-v4-flash", "raw_response": "PRIVATE-RESPONSE-SENTINEL"}
        )
        self.assertEqual(count, 1)
        self.assertIsNone(error)
        self.assertEqual(audit["receipts"][0]["provider_model"], "deepseek-v4-flash")
        self.assertTrue(audit["receipts"][0]["identity_matches"])
        self.assertNotIn("PRIVATE-", json.dumps(audit))

    async def test_mismatched_or_missing_successful_identity_stops_the_call(self):
        for response in [{"model": "PRIVATE-UNEXPECTED-NAME"}, {"choices": []}]:
            audit, count, error = await self.invoke(response)
            self.assertEqual(count, 1)
            self.assertEqual(error, "MODEL_IDENTITY_UNAVAILABLE_OR_MISMATCH")
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


if __name__ == "__main__":
    unittest.main()

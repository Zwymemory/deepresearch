"""Real observer/adapter/accounting path, using in-process HTTP transport only."""

import importlib.util
import json
import sys
from pathlib import Path
from unittest.mock import AsyncMock

import httpx
import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway
from deepresearch_workflow.agent_identity import CANONICAL_MODEL, MAX_RESPONSE_BYTES, POLICY_VERSION
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest
from deepresearch_workflow.graph import ModelCallError
from deepresearch_workflow.settings import Settings

SCRIPTS = Path(__file__).resolve().parents[2] / "scripts"
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("observed_sidecar", SCRIPTS / "agent-live-sidecar.py")
observer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(observer)
spec = importlib.util.spec_from_file_location("identity_export", SCRIPTS / "accept-agent-round1.py")
export = importlib.util.module_from_spec(spec)
spec.loader.exec_module(export)

REQUEST = ModelRequest(
    name="AgentDecision", instruction="fixture", payload={}, schema={"type": "object"}
)


def install_observer(path, monkeypatch, expected="deepseek-v4-flash"):
    # Register the unmodified method for fixture teardown before the observer wraps it.
    monkeypatch.setattr(httpx.AsyncClient, "send", httpx.AsyncClient.send)
    observer.install_model_audit(httpx, path, expected)


class Ledger:
    def __init__(self):
        self.reservations = 0
        self.receipts = []

    async def reserve(self, *_):
        self.reservations += 1
        return {"replay": None, "attempt": self.reservations}

    async def settle(self, *args, **kwargs):
        self.receipts.append((args, kwargs))


def gateway(client, ledger, model_name="deepseek-v4-flash", base="https://api.deepseek.com"):
    return AgentBudgetGateway(
        run_id="fixture-run",
        claim_token="fixture-claim",
        budget=AgentRunBudget(runtime="agent"),
        ledger=ledger,
        model=OpenAIAgentModel(
            Settings(openai_key="fixture-only", model_name=model_name, openai_base_url=base),
            client,
        ),
        guard=AsyncMock(),
    )


@pytest.mark.parametrize("model_name", [CANONICAL_MODEL, "deepseek-v4-flash"])
async def test_documented_identity_accepts_without_overwriting_observed_identifier(
    tmp_path, monkeypatch, model_name
):
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch, model_name)
    calls = []

    def transport(request):
        calls.append(request)
        return httpx.Response(
            200,
            json={
                "model": CANONICAL_MODEL,
                "choices": [
                    {
                        "finish_reason": "tool_calls",
                        "message": {
                            "tool_calls": [
                                {"function": {"name": "AgentDecision", "arguments": "{}"}},
                            ]
                        },
                    }
                ],
                "usage": {"prompt_tokens": 21, "completion_tokens": 0},
            },
        )

    ledger = Ledger()
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        result = await gateway(client, ledger, model_name).model_call(
            "model:identity", "DECISION", REQUEST
        )
    assert len(calls) == ledger.reservations == len(ledger.receipts) == 1
    assert result.input_tokens == 21 and result.output_tokens == 0
    assert ledger.receipts[0][1] == {}  # Usable result follows the normal settlement path.
    receipt = json.loads(path.read_text())["receipts"][0]
    assert receipt["identity_matches"] is True and receipt["provider_model"] == CANONICAL_MODEL
    assert receipt["identity"]["requested_model_identifier"] == model_name
    assert receipt["identity"]["response_model_identifier"] == CANONICAL_MODEL
    assert receipt["identity"]["policy_version"] == POLICY_VERSION


@pytest.mark.parametrize(
    "identity,usage,expected",
    [
        (
            "deepseek-v4-pro",
            {
                "prompt_tokens": 41,
                "completion_tokens": 7,
                "total_tokens": 999,
                "prompt_cache_hit_tokens": 40,
                "completion_tokens_details": {"reasoning_tokens": 6},
            },
            {"input_tokens": 41, "output_tokens": 7},
        ),
        ("deepseek-v4-flash", {"prompt_tokens": 0}, {"input_tokens": 0}),
        (None, {"prompt_tokens": True, "completion_tokens": 2.0}, {}),
        ([], {"prompt_tokens": -1, "completion_tokens": 2**63}, {}),
        ({"private": "PRIVATE-BODY"}, {"completion_tokens": 8}, {"output_tokens": 8}),
        ("deepseek-flash-20260910", {}, {}),
        ("sk-PRIVATE-SECRET" + "x" * 40, {"prompt_tokens": 12}, {"input_tokens": 12}),
        ("bad\nname", {"completion_tokens": 3}, {"output_tokens": 3}),
        ("\ud800", {"prompt_tokens": 5}, {"input_tokens": 5}),
        ("x" * 200, {"prompt_tokens": 5}, {"input_tokens": 5}),
    ],
)
async def test_identity_rejection_reaches_unknown_settlement_with_safe_measured_usage(
    tmp_path,
    monkeypatch,
    identity,
    usage,
    expected,
):
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch)
    calls = []

    def transport(request):
        calls.append(request)
        return httpx.Response(
            200,
            content=json.dumps(
                {"model": identity, "usage": usage, "choices": "PRIVATE-BODY"}, ensure_ascii=True
            ).encode("ascii"),
        )

    ledger = Ledger()
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        with pytest.raises(ModelCallError) as error:
            await gateway(client, ledger).model_call("model:identity", "DECISION", REQUEST)
    assert len(calls) == ledger.reservations == len(ledger.receipts) == 1
    failure = error.value
    assert failure.error_code == "MODEL_IDENTITY_INVALID"
    assert failure.error_class == "identity_validation" and failure.failure_kind == "PROVIDER"
    assert failure.retryable is False and failure.status_code == 200
    args, kwargs = ledger.receipts[0]
    assert kwargs == {"unknown": True} and args[4] == {}
    stored = args[5]
    assert {k: v for k, v in stored.items() if k != "model_failure"} == expected
    assert stored["model_failure"]["identity"]["decision"] == "reject"
    assert export.safe_model_failure(stored["model_failure"]) == stored["model_failure"]
    receipt = json.loads(path.read_text())["receipts"][0]
    assert receipt["usage"] == expected and receipt["identity_matches"] is False
    assert "PRIVATE" not in json.dumps({"receipt": receipt, "stored": stored})


@pytest.mark.parametrize(
    "body,reason",
    [
        (
            b'{"usage":{"prompt_tokens":7},"model":"deepseek-flash","model":"deepseek-v4-pro"}',
            "response_json_invalid",
        ),
        (b'{"model":"deepseek-flash","usage":{"prompt_tokens":NaN}}', "response_json_invalid"),
        (b"{bad", "response_json_invalid"),
        (b"[]", "response_shape"),
        (b'{"usage":{"prompt_tokens":9}}', "response_model_missing"),
        (b" " * (MAX_RESPONSE_BYTES + 1), "response_oversized"),
    ],
)
async def test_response_bounds_and_unique_identity_keys_fail_closed(
    tmp_path, monkeypatch, body, reason
):
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch)
    ledger = Ledger()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, content=body))
    ) as client:
        with pytest.raises(ModelCallError):
            await gateway(client, ledger).model_call("model:identity", "DECISION", REQUEST)
    metadata = ledger.receipts[0][0][5]["model_failure"]
    assert metadata["identity"]["reason"] == reason
    assert len(ledger.receipts) == 1


@pytest.mark.parametrize(
    "base",
    [
        "https://api.deepseek.com/v1",
        "https://api.deepseek.com/beta",
        "https://api.deepseek.com?x=1",
        "https://user@api.deepseek.com",
        "http://api.deepseek.com",
        "https://api.deepseek.com.evil.invalid",
    ],
)
async def test_configured_endpoint_cannot_bypass_observer(tmp_path, monkeypatch, base):
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch)
    calls = []

    def transport(request):
        calls.append(request)
        return httpx.Response(200, json={"model": CANONICAL_MODEL})

    ledger = Ledger()
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        with pytest.raises(ModelCallError) as error:
            await gateway(client, ledger, base=base).model_call(
                "model:identity", "DECISION", REQUEST
            )
    assert not calls and error.value.error_class == "identity_validation"
    assert ledger.receipts[0][0][5]["model_failure"]["identity"]["reason"] == "endpoint_mismatch"


async def test_redirect_is_not_followed_even_with_redirecting_client(tmp_path, monkeypatch):
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch)
    calls = []

    def transport(request):
        calls.append(request)
        return httpx.Response(307, headers={"location": "https://other.invalid/chat/completions"})

    ledger = Ledger()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(transport), follow_redirects=True
    ) as client:
        with pytest.raises(ModelCallError):
            await gateway(client, ledger).model_call("model:identity", "DECISION", REQUEST)
    assert len(calls) == 1
    assert ledger.receipts[0][0][5]["model_failure"]["identity"]["reason"] == "response_redirect"


async def test_transport_and_http_errors_keep_their_original_classification(tmp_path, monkeypatch):
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch)

    def transport(_):
        raise httpx.ConnectError("PRIVATE-TRANSPORT")

    ledger = Ledger()
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        with pytest.raises(ModelCallError) as error:
            await gateway(client, ledger).model_call("model:identity", "DECISION", REQUEST)
    assert error.value.error_class == "transport_error" and error.value.retryable is False


async def test_request_model_mismatch_is_rejected_before_send(tmp_path, monkeypatch):
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch)
    calls = []
    ledger = Ledger()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: calls.append(request) or httpx.Response(200))
    ) as client:
        with pytest.raises(ModelCallError) as error:
            await gateway(client, ledger, model_name=CANONICAL_MODEL).model_call(
                "model:identity", "DECISION", REQUEST
            )
    assert calls == [] and error.value.error_code == "MODEL_IDENTITY_INVALID"
    assert (
        ledger.receipts[0][0][5]["model_failure"]["identity"]["reason"] == "request_model_mismatch"
    )


async def test_effective_endpoint_mismatch_preserves_usage(tmp_path, monkeypatch):
    path = tmp_path / "identity.json"
    original_send = httpx.AsyncClient.send

    async def effective_send(client, request, **kwargs):
        response = await original_send(client, request, **kwargs)
        # HTTPX normally assigns the original request after MockTransport returns.
        # Simulate a changed effective URL at the send boundary the observer sees.
        response.request = httpx.Request("POST", "https://other.invalid/chat/completions")
        return response

    monkeypatch.setattr(httpx.AsyncClient, "send", effective_send)
    install_observer(path, monkeypatch)
    ledger = Ledger()

    def transport(_):
        return httpx.Response(200, json={"model": CANONICAL_MODEL, "usage": {"prompt_tokens": 4}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        with pytest.raises(ModelCallError) as error:
            await gateway(client, ledger).model_call("model:identity", "DECISION", REQUEST)
    assert error.value.error_code == "MODEL_IDENTITY_INVALID"
    stored = ledger.receipts[0][0][5]
    assert stored["input_tokens"] == 4
    assert stored["model_failure"]["identity"]["response_endpoint_matches"] is False
    assert stored["model_failure"]["identity"]["reason"] == "endpoint_mismatch"


@pytest.mark.parametrize(
    "status,classification", [(401, "http_auth"), (429, "http_rate_limit"), (503, "http_upstream")]
)
async def test_http_failures_are_not_reclassified_as_identity(
    tmp_path, monkeypatch, status, classification
):
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch)
    ledger = Ledger()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                status, json={"error": "PRIVATE", "usage": {"prompt_tokens": 6}}
            )
        )
    ) as client:
        with pytest.raises(ModelCallError) as error:
            await gateway(client, ledger).model_call("model:identity", "DECISION", REQUEST)
    assert error.value.error_class == classification and error.value.status_code == status
    assert ledger.reservations == len(ledger.receipts) == (1 if status == 401 else 2)
    assert ledger.receipts[0][0][5]["input_tokens"] == 6
    assert "PRIVATE" not in json.dumps(ledger.receipts[0][0][5])


def test_export_identity_allowlist_drops_untrusted_metadata():
    from deepresearch_workflow.agent_identity import identity_diagnostic, safe_identity_diagnostic

    value = identity_diagnostic(
        "https://api.deepseek.com/chat/completions",
        "deepseek-v4-flash",
        "deepseek-v4-flash",
        {"model": "PRIVATE"},
    )
    value.update(
        raw_model="PRIVATE",
        response_model_identifier="PRIVATE",
        request_model_kind=[],
        response_model_sha256="PRIVATE",
        requested_model_type={},
        response_endpoint_matches=1,
    )
    safe = safe_identity_diagnostic(value)
    assert export.safe_identity_failure(value) == safe
    assert "PRIVATE" not in json.dumps(safe)
    assert "raw_model" not in safe and "response_endpoint_matches" not in safe

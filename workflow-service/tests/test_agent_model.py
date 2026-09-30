import json
from decimal import Decimal
from unittest.mock import AsyncMock

import httpx
import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway, SqlAgentLedger
from deepresearch_workflow.agent_model import AgentModelFailure, OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from deepresearch_workflow.graph import ModelCallError, WorkflowExecutionError
from deepresearch_workflow.settings import Settings

REQUEST = ModelRequest(
    name="AgentDecision",
    instruction="Choose an action",
    payload={"source": "untrusted"},
    schema={"type": "object"},
)


@pytest.mark.parametrize(
    "arguments",
    [
        '{"action":"finish","action":"search"}',
        '{"cost":NaN}',
        "[1,2]",
        "{broken",
        '"' + "x" * 66000 + '"',
    ],
)
async def test_provider_json_rejections_are_safe_and_have_no_hidden_retry(arguments):
    calls = []

    def transport(request):
        calls.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "tool_calls",
                        "message": {
                            "tool_calls": [
                                {"function": {"name": "AgentDecision", "arguments": arguments}}
                            ]
                        },
                    }
                ]
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        model = OpenAIAgentModel(
            Settings(openai_key="test-only", model_name="deepseek-chat"), client
        )
        with pytest.raises(WorkflowExecutionError) as error:
            await model.invoke(REQUEST)
    assert error.value.error_code == "AGENT_MODEL_INVALID"
    assert "test-only" not in str(error.value) and len(calls) == 1
    assert calls[0]["max_tokens"] == 1024 and calls[0]["thinking"] == {"type": "disabled"}


async def test_missing_measured_usage_is_unknown():
    def transport(_):
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "tool_calls",
                        "message": {
                            "tool_calls": [
                                {"function": {"name": "AgentDecision", "arguments": "{}"}}
                            ]
                        },
                    }
                ]
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        result = await OpenAIAgentModel(Settings(openai_key="test-only"), client).invoke(REQUEST)
    assert result.input_tokens is None and result.output_tokens is None and result.cost_cny is None


async def test_database_numeric_usage_can_enter_the_followup_model_request():
    class Connection:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return False

    class Repository:
        pool = type("Pool", (), {"connection": lambda self: Connection()})()
        _lock_active_budget_run = AsyncMock()

    ledger = SqlAgentLedger(Repository())
    ledger._totals = AsyncMock(
        return_value={
            "model_calls": 1, "tool_calls": 1,
            "input_unknown": 0, "output_unknown": 0,
            "input_measured": Decimal(2248), "output_measured": Decimal(109),
            "input_charged": Decimal(2248), "output_charged": Decimal(109),
        }
    )
    summary = await ledger.summary("wf-isolated", "claim-isolated")
    assert summary["inputTokens"] == 2248 and type(summary["inputTokens"]) is int
    assert summary["inputAdmissionTokens"] == 2248 and type(summary["inputAdmissionTokens"]) is int
    assert json.loads(json.dumps(summary)) == summary

    def transport(request):
        sent = json.loads(request.content)
        payload = json.loads(sent["messages"][1]["content"])
        assert payload["budget_usage"]["inputTokens"] == 2248
        return httpx.Response(
            200,
            json={"choices": [{"finish_reason": "tool_calls",
                                "message": {"tool_calls": [{"function": {
                                    "name": "AgentDecision", "arguments": "{}"}}]}}]},
        )

    followup = REQUEST.model_copy(update={"payload": {"budget_usage": summary}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        await OpenAIAgentModel(Settings(openai_key="test-only"), client).invoke(followup)


@pytest.mark.parametrize(
    ("status", "error_class", "kind", "retryable"),
    [
        (401, "http_auth", "PROVIDER", False),
        (429, "http_rate_limit", "RATE_LIMIT", True),
        (500, "http_other", "PROVIDER", False),
        (503, "http_upstream", "PROVIDER", True),
    ],
)
async def test_http_failures_have_fixed_classification_without_response_content(
    status, error_class, kind, retryable,
):
    calls = 0

    def transport(_):
        nonlocal calls
        calls += 1
        return httpx.Response(status, text="PRIVATE-RESPONSE-KEY")

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        with pytest.raises(AgentModelFailure) as error:
            await OpenAIAgentModel(Settings(openai_key="PRIVATE-KEY"), client).invoke(REQUEST)
    assert calls == 1
    assert (error.value.error_class, error.value.failure_kind, error.value.retryable) == (
        error_class, kind, retryable,
    )
    assert error.value.status_code == status
    assert "PRIVATE" not in str(error.value) and error.value.__cause__ is None


@pytest.mark.parametrize(
    ("raised", "error_class", "kind"),
    [
        (httpx.ReadTimeout("PRIVATE-TIMEOUT"), "transport_timeout", "TIMEOUT"),
        (httpx.ConnectError("PRIVATE-CONNECTION"), "transport_error", "PROVIDER"),
    ],
)
async def test_transport_failures_are_ambiguous_and_not_automatically_retryable(
    raised, error_class, kind,
):
    def transport(_):
        raise raised

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        with pytest.raises(AgentModelFailure) as error:
            await OpenAIAgentModel(Settings(openai_key="PRIVATE-KEY"), client).invoke(REQUEST)
    assert (error.value.error_class, error.value.failure_kind, error.value.retryable) == (
        error_class, kind, False,
    )
    assert "PRIVATE" not in str(error.value) and error.value.__cause__ is None


@pytest.mark.parametrize(
    ("choice", "error_class"),
    [
        ({"finish_reason": "length", "message": {}}, "output_truncated"),
        ({"finish_reason": "stop", "message": {}}, "function_count"),
        ({"finish_reason": "tool_calls", "message": {"tool_calls": [{
            "function": {"name": "WrongFunction", "arguments": "{}"},
        }]}}, "function_name"),
        ({"finish_reason": "tool_calls", "message": {"tool_calls": [{
            "function": {"name": "AgentDecision", "arguments": "{PRIVATE-BROKEN"},
        }]}}, "function_json"),
    ],
)
async def test_invalid_function_result_retains_measured_tokens_without_content(choice, error_class):
    def transport(_):
        return httpx.Response(200, json={
            "choices": [choice], "usage": {"prompt_tokens": 41, "completion_tokens": 7},
        })

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        with pytest.raises(AgentModelFailure) as error:
            await OpenAIAgentModel(Settings(openai_key="PRIVATE-KEY"), client).invoke(REQUEST)
    assert error.value.error_class == error_class
    assert error.value.input_tokens == 41 and error.value.output_tokens == 7
    assert error.value.retryable is False
    assert "PRIVATE" not in str(error.value)


async def test_request_encoding_fails_before_transport_without_exposing_payload():
    calls = 0

    def transport(_):
        nonlocal calls
        calls += 1
        raise AssertionError("must not dispatch")

    request = REQUEST.model_copy(update={"payload": {"sensitive": object()}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        with pytest.raises(AgentModelFailure) as error:
            await OpenAIAgentModel(Settings(openai_key="PRIVATE-KEY"), client).invoke(request)
    assert calls == 0 and error.value.error_class == "request_encoding"
    assert "PRIVATE" not in str(error.value)


async def test_invalid_top_level_response_json_is_classified_without_body():
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, text="{PRIVATE-BODY"))
    ) as client:
        with pytest.raises(AgentModelFailure) as error:
            await OpenAIAgentModel(Settings(openai_key="PRIVATE-KEY"), client).invoke(REQUEST)
    assert error.value.error_class == "response_json"
    assert error.value.input_tokens is None and error.value.output_tokens is None
    assert "PRIVATE" not in str(error.value)


async def test_schema_failure_keeps_known_usage_unknown_receipt_and_safe_field_path():
    class Ledger:
        def __init__(self):
            self.reservations = 0
            self.receipts = []

        async def reserve(self, *_):
            self.reservations += 1
            return {"replay": None, "attempt": self.reservations}

        async def settle(self, *args, **kwargs):
            self.receipts.append((args, kwargs))

    class Model:
        calls = 0

        async def invoke(self, _):
            self.calls += 1
            return ModelResult(
                value={"valid_at": "PRIVATE-INVALID-DATE"},
                input_tokens=31, output_tokens=5,
            )

    ledger, model = Ledger(), Model()
    gateway = AgentBudgetGateway(
        run_id="run", claim_token="claim", budget=AgentRunBudget(runtime="agent"),
        ledger=ledger, model=model, guard=AsyncMock(),
    )
    request = REQUEST.model_copy(update={"result_schema": {
        "type": "object", "properties": {"valid_at": {"type": "string", "pattern": "^Z$"}},
    }})
    with pytest.raises(ModelCallError) as error:
        await gateway.model_call("model:field", "DECISION", request)
    assert model.calls == ledger.reservations == 1
    assert (error.value.failure_kind, error.value.error_class, error.value.retryable) == (
        "SCHEMA", "schema_validation", False,
    )
    assert error.value.validation_issue_codes == ["valid_at"]
    args, kwargs = ledger.receipts[0]
    assert kwargs == {"unknown": True} and args[4] == {}
    assert args[5]["input_tokens"] == 31 and args[5]["output_tokens"] == 5
    assert args[5]["model_failure"]["validation_issue_codes"] == ["valid_at"]
    assert "PRIVATE" not in str(args[5]) and "PRIVATE" not in str(error.value)


async def test_settlement_failure_does_not_retry_or_erase_ambiguous_receipt():
    class Ledger:
        reservations = 0
        settlements = 0

        async def reserve(self, *_):
            self.reservations += 1
            return {"replay": None, "attempt": 1}

        async def settle(self, *_, **__):
            self.settlements += 1
            raise RuntimeError("PRIVATE-DB-DETAIL")

    class Model:
        calls = 0

        async def invoke(self, _):
            self.calls += 1
            return ModelResult(value={}, input_tokens=9, output_tokens=2)

    ledger, model = Ledger(), Model()
    gateway = AgentBudgetGateway(
        run_id="run", claim_token="claim", budget=AgentRunBudget(runtime="agent"),
        ledger=ledger, model=model, guard=AsyncMock(),
    )
    with pytest.raises(WorkflowExecutionError) as error:
        await gateway.model_call("model:settlement", "DECISION", REQUEST)
    assert error.value.error_code == "AGENT_SETTLEMENT_FAILED"
    assert (ledger.reservations, ledger.settlements, model.calls) == (1, 1, 1)
    assert "PRIVATE" not in str(error.value)

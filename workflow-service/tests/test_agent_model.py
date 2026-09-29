import json
from decimal import Decimal
from unittest.mock import AsyncMock

import httpx
import pytest

from deepresearch_workflow.agent_budget import SqlAgentLedger
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import ModelRequest
from deepresearch_workflow.graph import WorkflowExecutionError
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

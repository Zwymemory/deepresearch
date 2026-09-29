import json

import httpx
import pytest

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

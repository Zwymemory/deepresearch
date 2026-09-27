from __future__ import annotations

import base64
import json
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from fastapi.testclient import TestClient

from deepresearch_workflow.app import create_app
from deepresearch_workflow.auth import ServiceJwtProvider
from deepresearch_workflow.domain import (
    ToolExecutionRequest,
    ToolExecutionResult,
    ToolName,
    WorkItem,
)
from deepresearch_workflow.mcp import (
    HttpGrantTokenProvider,
    HttpMcpToolClient,
    ReceiptCachingToolClient,
)
from deepresearch_workflow.ports import ToolReceiptStateError
from deepresearch_workflow.settings import Settings


def _claims(authorization: str) -> dict[str, object]:
    token = authorization.removeprefix("Bearer ")
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


@pytest.mark.asyncio
async def test_token_exchange_uses_access_token_and_fresh_service_jwt() -> None:
    seen_jti: list[str] = []
    seen_claims: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        claims = _claims(request.headers["Authorization"])
        seen_jti.append(str(claims["jti"]))
        seen_claims.append(str(json.loads(request.content)["claimToken"]))
        return httpx.Response(
            200,
            json={
                "accessToken": "delegation-token-value",
                "tokenType": "Bearer",
                "expiresAt": int((datetime.now(UTC) + timedelta(seconds=60)).timestamp()),
                "scopes": ["kb_search"],
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        provider = HttpGrantTokenProvider(
            client=client,
            java_base_url="http://java",
            service_tokens=ServiceJwtProvider(
                secret="a" * 32
            ),
        )
        first = await provider.exchange(
            grant_id="grant",
            run_id="run",
            claim_token="claim-token",
            task_id="task",
            requested_scopes=["kb_search"],
            timeout_seconds=1,
        )
        await provider.exchange(
            grant_id="grant",
            run_id="run",
            claim_token="claim-token",
            task_id="task",
            requested_scopes=["kb_search"],
            timeout_seconds=1,
        )

    assert first.access_token == "delegation-token-value"
    assert len(set(seen_jti)) == 2
    assert seen_claims == ["claim-token", "claim-token"]


def test_health_without_runner_has_no_external_dependency() -> None:
    application = create_app(Settings(runner_enabled=False, model_provider="disabled"))
    with TestClient(application) as client:
        assert client.get("/internal/health/live").json() == {"status": "UP"}
        response = client.get("/internal/health/ready")
        assert response.status_code == 200
        assert response.json() == {
            "status": "UP",
            "runner": "disabled",
            "activeRuns": 0,
        }


def test_java_mcp_envelope_maps_exact_evidence_fields_and_failure_code() -> None:
    success = HttpMcpToolClient._tool_result(
        "call-1",
        {
            "isError": False,
            "content": [
                {
                    "type": "text",
                    "text": json.dumps(
                        {
                            "success": True,
                            "code": "OK",
                            "tool": "kb_search",
                            "evidence": [
                                {
                                    "evidenceId": "kb:123",
                                    "uriOrChunkKey": "chunk-123",
                                    "excerpt": "grounded excerpt",
                                }
                            ],
                        }
                    ),
                }
            ],
        },
    )
    failure = HttpMcpToolClient._tool_result(
        "call-2",
        {
            "isError": False,
            "structuredContent": {
                "success": False,
                "code": "PERMISSION_DENIED",
                "evidence": [],
            },
        },
    )

    assert success.evidence[0].source_id == "kb:123"
    assert success.evidence[0].source_uri == "chunk-123"
    assert success.evidence[0].content == "grounded excerpt"
    assert failure.error_code == "PERMISSION_DENIED"
    assert not failure.evidence


def test_explicit_empty_mcp_evidence_does_not_become_text_source() -> None:
    payload = {"success": True, "code": "OK", "tool": "kb_search", "evidence": []}
    for result in (
        {
            "structuredContent": payload,
            "content": [{"type": "text", "text": "未找到相关证据"}],
        },
        {"content": [{"type": "text", "text": json.dumps(payload)}]},
    ):
        response = HttpMcpToolClient._tool_result("call-empty", result)
        assert not response.evidence


@pytest.mark.asyncio
async def test_mcp_execution_binds_run_and_task_headers() -> None:
    captured: dict[str, object] = {}

    class TokenProvider:
        async def exchange(self, **kwargs):
            return type(
                "Token",
                (),
                {"token_type": "Bearer", "access_token": "delegation-token"},
            )()

    class CapturingClient(HttpMcpToolClient):
        async def _call_tool(self, **kwargs):
            captured.update(kwargs)
            return {
                "isError": False,
                "structuredContent": {
                    "success": True,
                    "evidence": [
                        {"evidenceId": "source-1", "excerpt": "supported evidence"}
                    ],
                },
            }

    client = CapturingClient(mcp_url="http://java/mcp/sse", token_provider=TokenProvider())
    task = WorkItem(
        task_id="task-007",
        objective="Find source evidence",
        query="evidence",
        tool=ToolName.KB_SEARCH,
    )
    await client.execute(
        ToolExecutionRequest(
            run_id="run-007",
            grant_id="grant-007",
            claim_token="claim-007",
            task=task,
            requested_scopes=["kb_search"],
            arguments={"query": "evidence"},
            call_id="call-007",
            timeout_seconds=1,
        )
    )

    assert captured["run_id"] == "run-007"
    assert captured["task_id"] == "task-007"
    assert captured["call_id"] == "call-007"
    headers = HttpMcpToolClient._request_headers(
        "Bearer delegation-token", "call-007", "run-007", "task-007"
    )
    assert headers["X-Workflow-Run-Id"] == "run-007"
    assert headers["X-Workflow-Task-Id"] == "task-007"


def _tool_request(*, query: str = "evidence", call_id: str = "call-007") -> ToolExecutionRequest:
    return ToolExecutionRequest(
        run_id="run-007",
        grant_id="grant-007",
        claim_token="claim-007",
        task=WorkItem(
            task_id="task-007",
            objective="Find source evidence",
            query=query,
            tool=ToolName.KB_SEARCH,
        ),
        requested_scopes=["kb_search"],
        arguments={"query": query},
        call_id=call_id,
        timeout_seconds=1,
    )


@pytest.mark.asyncio
async def test_python_receipt_replay_rejects_same_call_id_with_new_arguments() -> None:
    original = _tool_request(query="original")
    changed = _tool_request(query="changed")

    class Repository:
        async def get_tool_receipt(self, run_id: str, call_id: str):
            return {
                "status": "COMPLETED",
                "request_fingerprint": ReceiptCachingToolClient._request_fingerprint(
                    original.arguments
                ),
                "safe_result": ToolExecutionResult(
                    call_id=original.call_id,
                    evidence=[],
                ).model_dump(mode="json"),
            }

        async def begin_tool_receipt(self, **kwargs):
            raise AssertionError("conflicting replay must fail before begin")

        async def complete_tool_receipt(self, **kwargs):
            raise AssertionError("conflicting replay must not complete")

    class Backend:
        async def execute(self, request: ToolExecutionRequest):
            raise AssertionError("conflicting replay must not reach Java")

    client = ReceiptCachingToolClient(Backend(), Repository())  # type: ignore[arg-type]
    with pytest.raises(ToolReceiptStateError) as failure:
        await client.execute(changed)
    assert failure.value.error_code == "MCP_RECEIPT_CONFLICT"


@pytest.mark.asyncio
@pytest.mark.parametrize("error_code", ["MCP_CALL_IN_PROGRESS", "MCP_RESULT_UNKNOWN"])
async def test_java_receipt_control_state_never_completes_python_cache(
    error_code: str,
) -> None:
    completed: list[ToolExecutionResult] = []

    class Repository:
        async def get_tool_receipt(self, run_id: str, call_id: str):
            return None

        async def begin_tool_receipt(self, **kwargs):
            return None

        async def complete_tool_receipt(self, **kwargs):
            completed.append(kwargs["result"])

    class Backend:
        async def execute(self, request: ToolExecutionRequest):
            return ToolExecutionResult(call_id=request.call_id, error_code=error_code)

    client = ReceiptCachingToolClient(Backend(), Repository())  # type: ignore[arg-type]
    with pytest.raises(ToolReceiptStateError) as failure:
        await client.execute(_tool_request())
    assert failure.value.error_code == error_code
    assert completed == []

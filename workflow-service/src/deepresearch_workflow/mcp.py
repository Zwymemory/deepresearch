from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Any, Literal

import httpx
from mcp import ClientSession
from mcp.client.sse import sse_client
from mcp.types import Implementation
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .auth import ServiceJwtProvider
from .domain import (
    ALLOWED_TOOLS,
    ToolEvidence,
    ToolExecutionRequest,
    ToolExecutionResult,
    ToolName,
)
from .ports import ToolReceiptStateError, WorkflowRepository

NON_CACHEABLE_RECEIPT_CODES = frozenset(
    {
        "MCP_CALL_IN_PROGRESS",
        "MCP_RESULT_UNKNOWN",
        "MCP_STALE_CLAIM",
        "MCP_RECEIPT_CONFLICT",
        "MCP_RECEIPT_MISSING",
    }
)


class DelegationToken(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)

    access_token: str = Field(alias="accessToken", min_length=10)
    token_type: Literal["Bearer"] = Field(default="Bearer", alias="tokenType")
    expires_at: datetime = Field(alias="expiresAt")
    scopes: list[str]

    @field_validator("expires_at")
    @classmethod
    def timezone_required(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value


class McpProtocolError(RuntimeError):
    pass


class ScopeDeniedError(PermissionError):
    pass


class HttpGrantTokenProvider:
    def __init__(
        self,
        *,
        client: httpx.AsyncClient,
        java_base_url: str,
        service_tokens: ServiceJwtProvider,
    ) -> None:
        self._client = client
        self._base_url = java_base_url.rstrip("/")
        self._service_tokens = service_tokens

    async def exchange(
        self,
        *,
        grant_id: str,
        run_id: str,
        claim_token: str,
        task_id: str,
        requested_scopes: list[str],
        timeout_seconds: float,
    ) -> DelegationToken:
        response = await self._client.post(
            f"{self._base_url}/internal/workflow-grants/{grant_id}/token",
            headers={"Authorization": self._service_tokens.authorization_header()},
            json={
                "runId": run_id,
                "claimToken": claim_token,
                "taskId": task_id,
                "requestedScopes": requested_scopes,
            },
            timeout=timeout_seconds,
        )
        response.raise_for_status()
        token = DelegationToken.model_validate(response.json())
        if token.expires_at <= datetime.now(UTC):
            raise ScopeDeniedError("control plane returned an expired delegation token")
        if not set(requested_scopes).issubset(token.scopes):
            raise ScopeDeniedError("delegation token omitted a requested scope")
        return token


class HttpMcpToolClient:
    """MCP SSE client compatible with Spring AI 1.0's initialize/call lifecycle."""

    def __init__(
        self,
        *,
        mcp_url: str,
        token_provider: HttpGrantTokenProvider,
    ) -> None:
        self._mcp_url = mcp_url
        self._tokens = token_provider

    async def execute(self, request: ToolExecutionRequest) -> ToolExecutionResult:
        if request.task.tool not in ALLOWED_TOOLS:
            raise ScopeDeniedError(f"tool is not allowlisted: {request.task.tool}")
        required_scope = request.task.tool.value
        if required_scope not in request.requested_scopes:
            raise ScopeDeniedError(f"task scope was not requested: {required_scope}")

        token = await self._tokens.exchange(
            grant_id=request.grant_id,
            run_id=request.run_id,
            claim_token=request.claim_token,
            task_id=request.task.task_id,
            requested_scopes=[required_scope],
            timeout_seconds=request.timeout_seconds,
        )
        payload = await self._call_tool(
            tool_name=request.task.tool.value,
            arguments=request.arguments,
            authorization=f"{token.token_type} {token.access_token}",
            timeout_seconds=request.timeout_seconds,
            call_id=request.call_id,
            run_id=request.run_id,
            task_id=request.task.task_id,
        )
        return self._tool_result(request.call_id, payload)

    async def _call_tool(
        self,
        *,
        tool_name: str,
        arguments: dict[str, Any],
        authorization: str,
        timeout_seconds: float,
        call_id: str,
        run_id: str,
        task_id: str,
    ) -> dict[str, Any]:
        headers = self._request_headers(authorization, call_id, run_id, task_id)
        async with sse_client(
            self._mcp_url,
            headers=headers,
            timeout=timeout_seconds,
            sse_read_timeout=timeout_seconds,
        ) as streams:
            async with ClientSession(
                *streams,
                read_timeout_seconds=timedelta(seconds=timeout_seconds),
                client_info=Implementation(
                    name="deepresearch-workflow-sidecar", version="0.1.0"
                ),
            ) as session:
                await session.initialize()
                called = await session.call_tool(
                    tool_name,
                    arguments,
                    read_timeout_seconds=timedelta(seconds=timeout_seconds),
                )
        return called.model_dump(mode="json", by_alias=True)

    @staticmethod
    def _request_headers(
        authorization: str, call_id: str, run_id: str, task_id: str
    ) -> dict[str, str]:
        return {
            "Authorization": authorization,
            "Idempotency-Key": call_id,
            "X-Workflow-Run-Id": run_id,
            "X-Workflow-Task-Id": task_id,
        }

    @classmethod
    def _tool_result(cls, call_id: str, result: Any) -> ToolExecutionResult:
        if not isinstance(result, dict):
            raise McpProtocolError("MCP tool result must be an object")
        envelope = cls._structured_envelope(result)
        if envelope is not None and envelope.get("success") is False:
            return ToolExecutionResult(
                call_id=call_id,
                error_code=str(envelope.get("code") or "MCP_TOOL_ERROR")[:64],
            )
        if result.get("isError") or result.get("is_error"):
            return ToolExecutionResult(call_id=call_id, error_code="MCP_TOOL_ERROR")
        return ToolExecutionResult(call_id=call_id, evidence=cls._extract_evidence(result))

    @classmethod
    def _extract_evidence(cls, result: Any) -> list[ToolEvidence]:
        if not isinstance(result, dict):
            return []
        if result.get("isError"):
            return []

        evidence: list[ToolEvidence] = []
        structured = cls._structured_envelope(result)
        if isinstance(structured, dict):
            raw_items = structured.get("evidence") or structured.get("results")
            if isinstance(raw_items, list):
                for index, item in enumerate(raw_items[:10]):
                    parsed = cls._structured_item(item, index)
                    if parsed is not None:
                        evidence.append(parsed)
        if evidence:
            return evidence

        for index, block in enumerate(result.get("content") or []):
            if not isinstance(block, dict) or block.get("type") != "text":
                continue
            text = str(block.get("text", "")).strip()[:6_000]
            if not text:
                continue
            evidence.append(
                ToolEvidence(
                    source_id=f"mcp-text-{index}",
                    content=text,
                    confidence=1.0,
                )
            )
        return evidence[:10]

    @staticmethod
    def _structured_envelope(result: dict[str, Any]) -> dict[str, Any] | None:
        structured = result.get("structuredContent") or result.get("structured_content")
        if isinstance(structured, dict):
            return structured
        for block in result.get("content") or []:
            if not isinstance(block, dict) or block.get("type") != "text":
                continue
            try:
                parsed = json.loads(str(block.get("text", "")))
            except (TypeError, ValueError, json.JSONDecodeError):
                continue
            if isinstance(parsed, dict) and (
                "success" in parsed or "evidence" in parsed or "results" in parsed
            ):
                return parsed
        return None

    @staticmethod
    def _structured_item(item: Any, index: int) -> ToolEvidence | None:
        if not isinstance(item, dict):
            return None
        content = str(
            item.get("content") or item.get("text") or item.get("excerpt") or ""
        ).strip()[:6_000]
        if not content:
            return None
        source_id = str(
            item.get("sourceId")
            or item.get("evidenceId")
            or item.get("id")
            or f"mcp-item-{index}"
        )[:300]
        uri = item.get("sourceUri") or item.get("uriOrChunkKey") or item.get("url")
        confidence = item.get("confidence", 1.0)
        return ToolEvidence(
            source_id=source_id,
            content=content,
            source_uri=str(uri)[:2_000] if uri else None,
            confidence=float(confidence),
        )


class ReceiptCachingToolClient:
    """Durable completed-result cache plus an upstream idempotency key for crash retries."""

    def __init__(self, backend: HttpMcpToolClient, repository: WorkflowRepository) -> None:
        self._backend = backend
        self._repository = repository

    async def execute(self, request: ToolExecutionRequest) -> ToolExecutionResult:
        fingerprint = self._request_fingerprint(request.arguments)
        cached = await self._repository.get_tool_receipt(request.run_id, request.call_id)
        if cached:
            if cached.get("request_fingerprint") != fingerprint:
                raise ToolReceiptStateError("MCP_RECEIPT_CONFLICT")
            if cached.get("status") == "COMPLETED":
                if not cached.get("safe_result"):
                    raise ToolReceiptStateError("MCP_RECEIPT_CONFLICT")
                replay = ToolExecutionResult.model_validate(cached["safe_result"])
                if replay.call_id != request.call_id:
                    raise ToolReceiptStateError("MCP_RECEIPT_CONFLICT")
                return replay

        await self._repository.begin_tool_receipt(
            run_id=request.run_id,
            claim_token=request.claim_token,
            call_id=request.call_id,
            task_id=request.task.task_id,
            tool_name=request.task.tool.value,
            request_fingerprint=fingerprint,
        )
        result = await self._backend.execute(request)
        # These codes describe transient control-plane or unknown-result states,
        # not stable tool output. Caching them as COMPLETED would replay a stale
        # failure forever after workflow recovery.
        if result.error_code in NON_CACHEABLE_RECEIPT_CODES:
            raise ToolReceiptStateError(result.error_code)
        await self._repository.complete_tool_receipt(
            run_id=request.run_id,
            claim_token=request.claim_token,
            call_id=request.call_id,
            result=result,
        )
        return result

    @staticmethod
    def _request_fingerprint(arguments: dict[str, Any]) -> str:
        try:
            canonical = json.dumps(
                arguments,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        except (TypeError, ValueError) as failure:
            raise ToolReceiptStateError("MCP_RECEIPT_CONFLICT") from failure
        return hashlib.sha256(canonical).hexdigest()


def arguments_for(task_tool: ToolName, query: str) -> dict[str, Any]:
    if task_tool is ToolName.CALCULATOR:
        return {"expression": query}
    return {"query": query}

from __future__ import annotations

import asyncio

import httpx

from .auth import ServiceJwtProvider
from .domain import FinalizeRequest


class FinalizeRejectedError(RuntimeError):
    pass


class HttpControlPlaneClient:
    def __init__(
        self,
        *,
        client: httpx.AsyncClient,
        java_base_url: str,
        service_tokens: ServiceJwtProvider,
        timeout_seconds: float,
    ) -> None:
        self._client = client
        self._base_url = java_base_url.rstrip("/")
        self._service_tokens = service_tokens
        self._timeout_seconds = timeout_seconds

    async def finalize(self, run_id: str, request: FinalizeRequest) -> None:
        last_failure: Exception | None = None
        for attempt in range(3):
            try:
                response = await self._client.post(
                    f"{self._base_url}/internal/research/workflows/{run_id}/finalize",
                    headers={
                        "Authorization": self._service_tokens.authorization_header(),
                        "Idempotency-Key": f"finalize:{run_id}:{request.claimToken}",
                    },
                    json=request.model_dump(mode="json"),
                    timeout=self._timeout_seconds,
                )
                if response.status_code in {409, 412}:
                    raise FinalizeRejectedError(
                        "control plane rejected a stale workflow claim"
                    )
                if response.status_code < 500:
                    response.raise_for_status()
                    return
                last_failure = httpx.HTTPStatusError(
                    "control plane finalize failed",
                    request=response.request,
                    response=response,
                )
            except FinalizeRejectedError:
                raise
            except (httpx.TransportError, httpx.TimeoutException) as failure:
                last_failure = failure
            if attempt < 2:
                await asyncio.sleep(0.1 * (2**attempt))
        assert last_failure is not None
        raise last_failure

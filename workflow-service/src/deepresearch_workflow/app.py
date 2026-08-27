from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any

import httpx
import uvicorn
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.checkpoint.serde._msgpack import SAFE_MSGPACK_TYPES
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer

from .auth import ServiceJwtProvider
from .control_plane import HttpControlPlaneClient
from .domain import RunBudget
from .graph import DurableResearchGraph, GraphRuntime
from .mcp import HttpGrantTokenProvider, HttpMcpToolClient, ReceiptCachingToolClient
from .model import OpenAIWorkflowModel
from .ports import RepositoryEventSink
from .repository import PostgresWorkflowRepository, checkpoint_pool
from .runner import WorkflowRunner
from .settings import Settings, harden_langgraph_deserialization


@dataclass
class ServiceRuntime:
    settings: Settings
    repository: PostgresWorkflowRepository | None = None
    checkpoint_connections: Any | None = None
    http_client: httpx.AsyncClient | None = None
    runner: WorkflowRunner | None = None
    runner_task: asyncio.Task[None] | None = None
    ready: bool = False
    startup_error: str | None = None

    async def start(self) -> None:
        errors = self.settings.validate_runtime()
        if errors:
            self.startup_error = "; ".join(errors)
            raise RuntimeError(self.startup_error)
        if not self.settings.runner_enabled:
            self.ready = True
            return

        harden_langgraph_deserialization()
        repository = PostgresWorkflowRepository(self.settings.database_url)
        self.repository = repository
        await repository.open()
        await repository.check_schema()
        await repository.check_checkpoint_schema(self.settings.checkpointer_schema)

        connections = checkpoint_pool(
            self.settings.database_url, self.settings.checkpointer_schema
        )
        self.checkpoint_connections = connections
        await connections.open(wait=True)
        # Pass the allowlist explicitly: relying only on an environment flag is
        # import-order sensitive because LangGraph reads it at module import time.
        saver = AsyncPostgresSaver(
            connections,
            serde=JsonPlusSerializer(allowed_msgpack_modules=SAFE_MSGPACK_TYPES),
        )
        if self.settings.checkpointer_setup:
            await saver.setup()

        http_client = httpx.AsyncClient()
        self.http_client = http_client
        service_tokens = ServiceJwtProvider(
            secret=self.settings.internal_secret,
            subject=self.settings.internal_service_id,
            ttl_seconds=self.settings.internal_token_ttl_seconds,
        )
        token_provider = HttpGrantTokenProvider(
            client=http_client,
            java_base_url=self.settings.java_base_url,
            service_tokens=service_tokens,
        )
        mcp = HttpMcpToolClient(
            mcp_url=self.settings.mcp_url,
            token_provider=token_provider,
        )
        tools = ReceiptCachingToolClient(mcp, repository)
        model = OpenAIWorkflowModel(self.settings)
        events = RepositoryEventSink(repository)
        control_plane = HttpControlPlaneClient(
            client=http_client,
            java_base_url=self.settings.java_base_url,
            service_tokens=service_tokens,
            timeout_seconds=self.settings.http_timeout_seconds,
        )

        def graph_factory(claim_token: str, budget: RunBudget) -> Any:
            return DurableResearchGraph(
                model=model,
                tools=tools,
                repository=repository,
                events=events,
                settings=self.settings,
                budget=budget,
                runtime=GraphRuntime(claim_token=claim_token),
            ).compile(checkpointer=saver)

        runner = WorkflowRunner(
            repository=repository,
            control_plane=control_plane,
            graph_factory=graph_factory,
            settings=self.settings,
        )
        self.runner = runner
        self.runner_task = asyncio.create_task(runner.serve_forever(), name="workflow-runner")
        self.ready = True

    async def close(self) -> None:
        self.ready = False
        if self.runner is not None:
            self.runner.stop()
        if self.runner_task is not None:
            try:
                await asyncio.wait_for(self.runner_task, timeout=10)
            except TimeoutError:
                self.runner_task.cancel()
                await asyncio.gather(self.runner_task, return_exceptions=True)
        if self.http_client is not None:
            await self.http_client.aclose()
        if self.checkpoint_connections is not None:
            await self.checkpoint_connections.close()
        if self.repository is not None:
            await self.repository.close()

    async def is_ready(self) -> bool:
        if not self.ready:
            return False
        if not self.settings.runner_enabled:
            return True
        if self.runner_task is None or self.runner_task.done():
            return False
        return bool(self.repository and await self.repository.ping())


def create_app(settings: Settings | None = None) -> FastAPI:
    configured = settings or Settings()
    runtime = ServiceRuntime(configured)

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        application.state.workflow_runtime = runtime
        try:
            await runtime.start()
            yield
        finally:
            await runtime.close()

    application = FastAPI(
        title="DeepResearch Workflow Sidecar",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )

    @application.get("/internal/health/live")
    async def live() -> dict[str, str]:
        return {"status": "UP"}

    @application.get("/internal/health/ready")
    async def ready() -> JSONResponse:
        healthy = await runtime.is_ready()
        payload = {
            "status": "UP" if healthy else "DOWN",
            "runner": "enabled" if configured.runner_enabled else "disabled",
            "activeRuns": runtime.runner.active_runs if runtime.runner else 0,
        }
        return JSONResponse(payload, status_code=200 if healthy else 503)

    return application


app = create_app()


def main() -> None:
    uvicorn.run(
        "deepresearch_workflow.app:app",
        host="0.0.0.0",
        port=8091,
        access_log=False,
    )

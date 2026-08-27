from __future__ import annotations

from contextlib import asynccontextmanager
from uuid import UUID

import pytest

from deepresearch_workflow.domain import RunBudget, UsageDelta
from deepresearch_workflow.ports import BudgetOperationInvalidError
from deepresearch_workflow.repository import (
    PostgresWorkflowRepository,
    SchemaNotReadyError,
)


class _CheckpointSchemaCursor:
    def __init__(self, row: dict[str, bool] | None) -> None:
        self.row = row

    async def fetchone(self) -> dict[str, bool] | None:
        return self.row


class _CheckpointSchemaConnection:
    def __init__(self, row: dict[str, bool] | None) -> None:
        self.row = row
        self.parameters: tuple[str, ...] | None = None

    async def execute(
        self, query: str, parameters: tuple[str, ...]
    ) -> _CheckpointSchemaCursor:
        self.parameters = parameters
        return _CheckpointSchemaCursor(self.row)


class _CheckpointSchemaPool:
    def __init__(self, row: dict[str, bool] | None) -> None:
        self.connection_value = _CheckpointSchemaConnection(row)

    @asynccontextmanager
    async def connection(self):
        yield self.connection_value


def _checkpoint_repository(row: dict[str, bool] | None) -> PostgresWorkflowRepository:
    repository = PostgresWorkflowRepository(
        "postgresql://unused:unused@127.0.0.1:1/unused"
    )
    repository.pool = _CheckpointSchemaPool(row)  # type: ignore[assignment]
    return repository


@pytest.mark.asyncio
async def test_checkpoint_schema_must_exist() -> None:
    repository = _checkpoint_repository(
        {"schema_exists": False, "has_usage": False, "has_create": False}
    )

    with pytest.raises(
        SchemaNotReadyError,
        match=r"missing checkpoint schema: langgraph; run Flyway migrations first",
    ):
        await repository.check_checkpoint_schema("langgraph")


@pytest.mark.asyncio
async def test_checkpoint_schema_requires_usage_privilege() -> None:
    repository = _checkpoint_repository(
        {"schema_exists": True, "has_usage": False, "has_create": True}
    )

    with pytest.raises(
        SchemaNotReadyError,
        match=r"lacks USAGE on checkpoint schema: langgraph",
    ):
        await repository.check_checkpoint_schema("langgraph")


@pytest.mark.asyncio
async def test_checkpoint_schema_requires_create_privilege() -> None:
    repository = _checkpoint_repository(
        {"schema_exists": True, "has_usage": True, "has_create": False}
    )

    with pytest.raises(
        SchemaNotReadyError,
        match=r"lacks CREATE on checkpoint schema: langgraph",
    ):
        await repository.check_checkpoint_schema("langgraph")


@pytest.mark.asyncio
async def test_checkpoint_schema_accepts_usage_and_create_privileges() -> None:
    repository = _checkpoint_repository(
        {"schema_exists": True, "has_usage": True, "has_create": True}
    )

    await repository.check_checkpoint_schema("langgraph")

    assert repository.pool.connection_value.parameters == ("langgraph",)  # type: ignore[union-attr]


@pytest.mark.asyncio
async def test_invalid_budget_operation_key_is_rejected_before_database_access() -> None:
    repository = PostgresWorkflowRepository(
        "postgresql://unused:unused@127.0.0.1:1/unused"
    )

    with pytest.raises(BudgetOperationInvalidError, match="operation key"):
        await repository.reserve_model_call(
            run_id="run",
            claim_token=str(UUID(int=1)),
            operation_key="bad/key",
            budget=RunBudget(),
        )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("kind", "operation_key"),
    [("MODEL", "tool-valid"), ("TOOL", "model:planner")],
)
async def test_budget_operation_key_must_match_kind_prefix(
    kind: str,
    operation_key: str,
) -> None:
    repository = PostgresWorkflowRepository(
        "postgresql://unused:unused@127.0.0.1:1/unused"
    )

    with pytest.raises(BudgetOperationInvalidError, match="operation key"):
        repository._validate_budget_operation_key(operation_key, kind)


@pytest.mark.asyncio
async def test_oversized_model_replay_value_has_stable_domain_error() -> None:
    repository = PostgresWorkflowRepository(
        "postgresql://unused:unused@127.0.0.1:1/unused"
    )

    with pytest.raises(BudgetOperationInvalidError, match="size limit"):
        await repository.settle_model_call(
            run_id="run",
            claim_token="00000000-0000-0000-0000-000000000001",
            operation_key="model:synthesizer",
            attempt=1,
            value={"answer": "证" * 50_000},
            usage=UsageDelta(model_calls=1),
        )

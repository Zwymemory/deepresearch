from __future__ import annotations

from typing import Any

import pytest

from deepresearch_workflow.domain import FinalizeRequest, RunBudget, UsageDelta
from deepresearch_workflow.graph import ModelCallError
from deepresearch_workflow.model import ProviderCallError, StructuredOutputError
from deepresearch_workflow.runner import WorkflowRunner
from deepresearch_workflow.settings import Settings

from .conftest import FakeRepository, FakeSnapshot


class FakeGraph:
    async def aget_state(self, config: dict[str, Any]) -> FakeSnapshot:
        return FakeSnapshot(values={}, next=())

    async def ainvoke(
        self,
        graph_input: dict[str, Any],
        config: dict[str, Any],
        *,
        durability: str,
    ) -> dict[str, Any]:
        assert durability == "sync"
        return {
            **graph_input,
            "final_status": "SUCCEEDED",
            "final_answer": "grounded answer",
            "citations": ["source-1"],
            "usage": UsageDelta(
                model_calls=3,
                tool_calls=1,
                input_tokens=10,
                output_tokens=5,
                cost_cny=0.02,
            ).model_dump(mode="json"),
        }


class FakeControlPlane:
    def __init__(self) -> None:
        self.requests: list[tuple[str, FinalizeRequest]] = []

    async def finalize(self, run_id: str, request: FinalizeRequest) -> None:
        self.requests.append((run_id, request))


@pytest.mark.asyncio
async def test_runner_logs_allowlisted_failure_metadata_without_sensitive_message(
    repository: FakeRepository,
    claimed_run,
    caplog: pytest.LogCaptureFixture,
) -> None:
    control = FakeControlPlane()
    private_run = claimed_run.model_copy(
        update={"question": "PRIVATE_QUESTION_MARKER must never be logged"}
    )

    class FailingGraph(FakeGraph):
        async def ainvoke(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
            failure = ProviderCallError(
                failure_kind="PROVIDER",
                retryable=False,
                error_class="BadRequestError",
                provider_status=400,
                provider_code="invalid_request_error",
                request_id="req-safe-123",
            )
            raise ModelCallError(
                "model:planner",
                failure_kind="PROVIDER",
                attempt=1,
                error_class="BadRequestError",
                retryable=False,
            ) from failure

    runner = WorkflowRunner(
        repository=repository,
        control_plane=control,
        graph_factory=lambda claim, budget: FailingGraph(),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
    )

    with caplog.at_level("INFO", logger="uvicorn.error.deepresearch_workflow.runner"):
        await runner.run_claimed(private_run)

    log_output = caplog.text
    assert f"run_id={private_run.run_id}" in log_output
    assert "error_code=MODEL_PROVIDER_FAILED" in log_output
    assert "operation=model:planner" in log_output
    assert "attempt=1" in log_output
    assert "model_failure_kind=PROVIDER" in log_output
    assert "model_error_class=BadRequestError" in log_output
    assert "retryable=false" in log_output
    assert "failure_type=ModelCallError" in log_output
    assert "cause_chain=ModelCallError>ProviderCallError" in log_output
    assert "provider_status=400" in log_output
    assert "provider_code=invalid_request_error" in log_output
    assert "request_id=req-safe-123" in log_output
    assert "secret-token" not in log_output
    assert "private question" not in log_output
    assert "PRIVATE_QUESTION_MARKER" not in log_output
    assert private_run.claim_token not in log_output
    assert len(control.requests) == 1
    assert control.requests[0][1].errorCode == "MODEL_PROVIDER_FAILED"
    assert control.requests[0][1].errorMessage == "model call failed during model:planner"


@pytest.mark.asyncio
async def test_runner_logs_safe_structured_output_diagnostics(
    repository: FakeRepository,
    claimed_run,
    caplog: pytest.LogCaptureFixture,
) -> None:
    control = FakeControlPlane()

    class FailingGraph(FakeGraph):
        async def ainvoke(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
            try:
                raise StructuredOutputError(
                    schema_name="ReviewOutput",
                    parser_error_type="ValidationError",
                    finish_reason="length",
                    content_state="present",
                    tool_call_count=1,
                    validation_issue_codes="revision_tasks:list_type",
                )
            except StructuredOutputError as failure:
                raise ModelCallError(
                    "model:reviewer:round-0",
                    failure_kind="SCHEMA",
                    attempt=2,
                    error_class="StructuredOutputError",
                    retryable=True,
                ) from failure

    runner = WorkflowRunner(
        repository=repository,
        control_plane=control,
        graph_factory=lambda claim, budget: FailingGraph(),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
    )

    with caplog.at_level("INFO", logger="uvicorn.error.deepresearch_workflow.runner"):
        await runner.run_claimed(claimed_run)

    log_output = caplog.text
    assert "schema=ReviewOutput" in log_output
    assert "parser_error_type=ValidationError" in log_output
    assert "finish_reason=length" in log_output
    assert "content_state=present" in log_output
    assert "tool_call_count=1" in log_output
    assert "validation_issues=revision_tasks:list_type" in log_output
    assert "error_code=MODEL_SCHEMA_INVALID" in log_output
    assert "operation=model:reviewer:round-0" in log_output
    assert "attempt=2" in log_output
    assert "provider returned unusable structured output" not in log_output
    assert control.requests[0][1].errorCode == "MODEL_SCHEMA_INVALID"


@pytest.mark.asyncio
async def test_runner_keeps_nonterminal_finalizing_until_java_finalize(
    repository: FakeRepository,
    claimed_run,
) -> None:
    control = FakeControlPlane()
    runner = WorkflowRunner(
        repository=repository,
        control_plane=control,
        graph_factory=lambda claim, budget: FakeGraph(),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
    )

    await runner.run_claimed(claimed_run)

    assert repository.progress[-1][0:2] == ("FINALIZING", "FINALIZING")
    assert all(row[0] not in {"SUCCEEDED", "FAILED"} for row in repository.progress)
    assert len(control.requests) == 1
    request = control.requests[0][1]
    assert request.status == "SUCCEEDED"
    assert request.usage["totalTokens"] == 15
    assert request.usage["estimatedCost"] == 0.02


@pytest.mark.asyncio
async def test_runner_finalizes_citation_contract_failure_without_reclassifying_it(
    repository: FakeRepository,
    claimed_run,
) -> None:
    control = FakeControlPlane()

    class CitationFailureGraph(FakeGraph):
        async def ainvoke(
            self,
            graph_input: dict[str, Any],
            config: dict[str, Any],
            *,
            durability: str,
        ) -> dict[str, Any]:
            assert durability == "sync"
            return {
                **graph_input,
                "final_status": "FAILED",
                "final_answer": "",
                "citations": [],
                "error_code": "CITATION_VALIDATION_FAILED",
                "error_message": "citation validation failed: MARKER_OUT_OF_RANGE",
                "usage": UsageDelta(model_calls=1).model_dump(mode="json"),
            }

    runner = WorkflowRunner(
        repository=repository,
        control_plane=control,
        graph_factory=lambda claim, budget: CitationFailureGraph(),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
    )

    await runner.run_claimed(claimed_run)

    assert repository.progress[-1][0:2] == ("FINALIZING", "FINALIZING")
    assert len(control.requests) == 1
    request = control.requests[0][1]
    assert request.status == "FAILED"
    assert request.answer is None
    assert request.citations == []
    assert request.errorCode == "CITATION_VALIDATION_FAILED"
    assert request.errorMessage == "citation validation failed: MARKER_OUT_OF_RANGE"
    assert runner.last_error_code is None


@pytest.mark.asyncio
async def test_runner_reconciles_unknown_durable_attempts_before_finalize(
    repository: FakeRepository,
    claimed_run,
) -> None:
    repository.model_budget_rows = {
        "model:planner": [
            {"attempt": 1, "status": "UNKNOWN", "claim_token": "claim"},
            {"attempt": 2, "status": "SETTLED", "claim_token": "claim"},
        ],
        "model:worker:task-01:prepare": [
            {"attempt": 1, "status": "SETTLED", "claim_token": "claim"}
        ],
        "model:reviewer:round-0": [{"attempt": 1, "status": "SETTLED", "claim_token": "claim"}],
    }
    repository.tool_budget_rows = {
        "tool-1": {"status": "SETTLED", "claim_token": "claim"},
        "tool-2": {"status": "SETTLED", "claim_token": "claim"},
    }
    control = FakeControlPlane()
    runner = WorkflowRunner(
        repository=repository,
        control_plane=control,
        graph_factory=lambda claim, budget: FakeGraph(),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
    )

    await runner.run_claimed(claimed_run)

    request = control.requests[0][1]
    assert request.usage["modelCalls"] == 4
    assert request.usage["toolCalls"] == 2
    assert request.usage["totalTokens"] == 15


@pytest.mark.asyncio
async def test_runner_reconciles_settled_usage_after_pre_checkpoint_failure(
    repository: FakeRepository,
    claimed_run,
) -> None:
    control = FakeControlPlane()

    class PostSettlementFailureGraph(FakeGraph):
        async def ainvoke(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
            repository.model_budget_rows = {
                "model:planner": [
                    {
                        "attempt": 1,
                        "status": "SETTLED",
                        "claim_token": "claim",
                        "usage": UsageDelta(
                            model_calls=1,
                            input_tokens=12,
                            output_tokens=8,
                            cost_cny=0.03,
                        ),
                    }
                ]
            }
            raise RuntimeError("simulated crash after settlement")

    runner = WorkflowRunner(
        repository=repository,
        control_plane=control,
        graph_factory=lambda claim, budget: PostSettlementFailureGraph(),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
    )

    await runner.run_claimed(claimed_run)

    request = control.requests[0][1]
    assert request.status == "FAILED"
    assert request.usage["modelCalls"] == 1
    assert request.usage["totalTokens"] == 20
    assert request.usage["estimatedCost"] == 0.03


@pytest.mark.asyncio
async def test_runner_does_not_finalize_after_claim_is_lost(
    repository: FakeRepository,
    claimed_run,
) -> None:
    control = FakeControlPlane()

    class StaleGraph(FakeGraph):
        async def ainvoke(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
            repository.active = False
            return await super().ainvoke(*args, **kwargs)

    runner = WorkflowRunner(
        repository=repository,
        control_plane=control,
        graph_factory=lambda claim, budget: StaleGraph(),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
    )

    await runner.run_claimed(claimed_run)

    assert not control.requests
    assert runner.last_error_code == "STALE_CLAIM"


@pytest.mark.asyncio
async def test_runner_uses_run_snapshot_but_never_exceeds_process_caps(
    repository: FakeRepository,
    claimed_run,
) -> None:
    captured_budgets: list[RunBudget] = []
    captured_configs: list[dict[str, Any]] = []

    class CapturingGraph(FakeGraph):
        async def aget_state(self, config: dict[str, Any]) -> FakeSnapshot:
            captured_configs.append(config)
            return await super().aget_state(config)

    def graph_factory(claim_token: str, budget: RunBudget) -> CapturingGraph:
        captured_budgets.append(budget)
        return CapturingGraph()

    runner = WorkflowRunner(
        repository=repository,
        control_plane=FakeControlPlane(),
        graph_factory=graph_factory,
        settings=Settings(
            runner_enabled=False,
            model_provider="disabled",
            max_tasks=3,
            worker_max_concurrency=1,
            max_revision_rounds=0,
            max_model_calls=20,
            max_tool_calls=12,
            max_tokens=50_000,
            max_cost_cny=0.5,
            default_timeout_seconds=90,
        ),
    )

    await runner.run_claimed(claimed_run)

    assert captured_budgets == [
        RunBudget(
            max_tasks=3,
            max_concurrency=1,
            max_revision_rounds=0,
            max_model_calls=20,
            max_tool_calls=12,
            max_tokens=50_000,
            max_cost_cny=0.5,
            deadline_seconds=90,
        )
    ]
    assert captured_configs[0]["max_concurrency"] == 1


async def test_agent_engine_admits_all_budgeted_compress_decide_act_cycles():
    from langgraph.checkpoint.memory import InMemorySaver
    from langgraph.graph import END, StateGraph

    from deepresearch_workflow.domain import AgentRunBudget

    from .test_agent_runtime import setup

    budget = AgentRunBudget(runtime="agent", maxDecisionSteps=16)
    runner, run, *_rest = setup("version-difference", budget=budget)
    calls = []
    graph = StateGraph(dict)

    def decide(value):
        step = value.get("decision_steps", 0) + 1
        calls.append(step)
        return {**value, "decision_steps": step}

    def act(value):
        return {
            **value,
            "final_status": "INSUFFICIENT_EVIDENCE",
            "final_answer": "Offline engine-limit regression",
            "citations": [],
        }

    graph.add_node("compress", lambda value: value)
    graph.add_node("decide", decide)
    graph.add_node("act", act)
    graph.set_entry_point("compress")
    graph.add_edge("compress", "decide")
    graph.add_edge("decide", "act")
    graph.add_conditional_edges(
        "act", lambda value: END if value["decision_steps"] == 16 else "compress"
    )
    compiled = graph.compile(checkpointer=InMemorySaver())
    runner._graph_factory = lambda claim, effective: compiled
    await runner.run_claimed(run)
    assert calls == list(range(1, 17))
    assert runner.last_error_code is None

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from deepresearch_workflow.domain import (
    ModelCall,
    PlannedTaskDraft,
    PlanOutput,
    ReviewOutput,
    RevisionTaskDraft,
    RunBudget,
    SynthesisOutput,
    ToolEvidence,
    ToolExecutionRequest,
    ToolExecutionResult,
    ToolName,
    UsageDelta,
    WorkerPreparation,
    WorkItem,
)
from deepresearch_workflow.graph import (
    DurableResearchGraph,
    GraphRuntime,
    ModelCallError,
    RunBudgetExceededError,
    resolve_citation_contract,
    validate_citation_contract,
)
from deepresearch_workflow.model import ProviderCallError, StructuredOutputError
from deepresearch_workflow.ports import ToolReceiptStateError
from deepresearch_workflow.settings import Settings

from .conftest import FakeEventSink, FakeRepository


class HappyModel:
    async def plan(self, **kwargs: Any) -> ModelCall[PlanOutput]:
        return ModelCall(
            value=PlanOutput(
                tasks=[
                    PlannedTaskDraft(
                        objective="Find the source", query="supported", tool=ToolName.KB_SEARCH
                    ),
                    PlannedTaskDraft(
                        objective="Calculate the value", query="6*7", tool=ToolName.CALCULATOR
                    ),
                ],
                summary="Use two independent sources",
            ),
            usage=UsageDelta(model_calls=1, input_tokens=10, output_tokens=4),
        )

    async def prepare_worker(
        self, *, question: str, task: WorkItem
    ) -> ModelCall[WorkerPreparation]:
        return ModelCall(
            value=WorkerPreparation(
                focused_query=task.query,
                safe_summary=f"Run the authorized {task.tool.value} task",
            ),
            usage=UsageDelta(model_calls=1, input_tokens=3, output_tokens=2),
        )

    async def review(self, **kwargs: Any) -> ModelCall[ReviewOutput]:
        return ModelCall(
            value=ReviewOutput(sufficient=True, summary="Evidence is sufficient"),
            usage=UsageDelta(model_calls=1, input_tokens=10, output_tokens=2),
        )

    async def synthesize(self, **kwargs: Any) -> ModelCall[SynthesisOutput]:
        return ModelCall(
            value=SynthesisOutput(
                answer="The supported answer is 42. [来源1]",
                citations=["source-kb_search"],
                grounded=True,
            ),
            usage=UsageDelta(model_calls=1, input_tokens=12, output_tokens=4),
        )


class OneRevisionModel(HappyModel):
    def __init__(self) -> None:
        self.review_calls = 0

    async def plan(self, **kwargs: Any) -> ModelCall[PlanOutput]:
        result = await super().plan(**kwargs)
        return result.model_copy(
            update={"value": result.value.model_copy(update={"tasks": result.value.tasks[:1]})}
        )

    async def review(self, **kwargs: Any) -> ModelCall[ReviewOutput]:
        self.review_calls += 1
        if self.review_calls == 1:
            return ModelCall(
                value=ReviewOutput(
                    sufficient=False,
                    summary="Need one narrower query",
                    revision_tasks=[
                        RevisionTaskDraft(
                            objective="Find missing detail",
                            query="missing detail",
                            tool=ToolName.KB_SEARCH,
                            revision_of="task-01",
                        )
                    ],
                ),
                usage=UsageDelta(model_calls=1),
            )
        return ModelCall(
            value=ReviewOutput(sufficient=False, summary="Still insufficient"),
            usage=UsageDelta(model_calls=1),
        )


class CountingModel(HappyModel):
    def __init__(self) -> None:
        self.plan_calls = 0
        self.worker_calls = 0

    async def plan(self, **kwargs: Any) -> ModelCall[PlanOutput]:
        self.plan_calls += 1
        return await super().plan(**kwargs)

    async def prepare_worker(
        self, *, question: str, task: WorkItem
    ) -> ModelCall[WorkerPreparation]:
        self.worker_calls += 1
        return await super().prepare_worker(question=question, task=task)


class FakeTools:
    def __init__(self, empty: bool = False) -> None:
        self.calls: list[ToolExecutionRequest] = []
        self.empty = empty

    async def execute(self, request: ToolExecutionRequest) -> ToolExecutionResult:
        self.calls.append(request)
        evidence = (
            []
            if self.empty
            else [
                ToolEvidence(
                    source_id=f"source-{request.task.tool.value}",
                    content=f"Evidence for {request.task.query}",
                )
            ]
        )
        return ToolExecutionResult(call_id=request.call_id, evidence=evidence)


def initial_state() -> dict[str, Any]:
    return {
        "run_id": "run-001",
        "graph_thread_id": "run-001",
        "grant_id": "grant-001",
        "user_id": "tenant:user",
        "question": "What is the answer?",
        "context_snapshot": {},
        "requested_scopes": ["kb_search", "calculator"],
        "deadline_at": (datetime.now(UTC) + timedelta(seconds=60)).isoformat(),
        "status": "QUEUED",
        "stage": "QUEUED",
        "tasks": [],
        "worker_task_count": 0,
        "evidence": [],
        "usage": UsageDelta().model_dump(mode="json"),
        "revision_round": 0,
        "should_revise": False,
        "revision_blocked_reason": None,
        "citations": [],
    }


@pytest.mark.asyncio
async def test_planner_wraps_provider_failure_with_safe_operation_context(
    repository: FakeRepository,
) -> None:
    class ProviderFailure(RuntimeError):
        pass

    class FailingPlannerModel(HappyModel):
        async def plan(self, **kwargs: Any) -> ModelCall[PlanOutput]:
            raise ProviderFailure("provider response and prompt are sensitive")

    graph = DurableResearchGraph(
        model=FailingPlannerModel(),
        tools=FakeTools(),
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    with pytest.raises(ModelCallError) as captured:
        await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert captured.value.operation_key == "model:planner"
    assert str(captured.value) == "model call failed during model:planner"
    assert captured.value.error_code == "MODEL_PROVIDER_FAILED"
    assert captured.value.failure_kind == "PROVIDER"
    assert captured.value.attempt == 1
    assert isinstance(captured.value.__cause__, ProviderCallError)
    assert repository.model_budget_rows["model:planner"][0]["status"] == "UNKNOWN"
    failure_event = next(
        event for event in repository.events if event.event_type == "MODEL_CALL_FAILED"
    )
    assert failure_event.safe_payload == {
        "operation": "model:planner",
        "attempts": 1,
        "reasonCode": "MODEL_PROVIDER_FAILED",
        "retryable": False,
    }


@pytest.mark.asyncio
async def test_reviewer_retries_schema_failure_with_durable_attempts(
    repository: FakeRepository,
) -> None:
    class FlakyReviewerModel(HappyModel):
        def __init__(self) -> None:
            self.review_calls = 0

        async def review(self, **kwargs: Any) -> ModelCall[ReviewOutput]:
            self.review_calls += 1
            if self.review_calls == 1:
                raise StructuredOutputError(
                    schema_name="ReviewOutput",
                    parser_error_type="ValidationError",
                    finish_reason="stop",
                    content_state="empty",
                    tool_call_count=0,
                )
            return await super().review(**kwargs)

    model = FlakyReviewerModel()
    graph = DurableResearchGraph(
        model=model,
        tools=FakeTools(),
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(
            runner_enabled=False,
            model_provider="disabled",
            model_max_attempts=2,
            model_retry_backoff_seconds=0,
        ),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "SUCCEEDED"
    assert model.review_calls == 2
    assert result["usage"]["model_calls"] == 6
    attempts = repository.model_budget_rows["model:reviewer:round-0"]
    assert [attempt["status"] for attempt in attempts] == ["UNKNOWN", "SETTLED"]
    retry_event = next(
        event for event in repository.events if event.event_type == "MODEL_RETRY_SCHEDULED"
    )
    assert retry_event.role == "REVIEWER"
    assert retry_event.safe_payload == {
        "operation": "model:reviewer:round-0",
        "attempts": 1,
        "reasonCode": "MODEL_SCHEMA_INVALID",
        "retryable": True,
    }


@pytest.mark.asyncio
async def test_model_timeout_retry_exhaustion_is_safe_and_finite(
    repository: FakeRepository,
    caplog: pytest.LogCaptureFixture,
) -> None:
    private_marker = "PRIVATE_PROMPT_AND_RESPONSE"

    class TimeoutPlannerModel(HappyModel):
        def __init__(self) -> None:
            self.plan_calls = 0

        async def plan(self, **kwargs: Any) -> ModelCall[PlanOutput]:
            self.plan_calls += 1
            raise TimeoutError(private_marker)

    model = TimeoutPlannerModel()
    graph = DurableResearchGraph(
        model=model,
        tools=FakeTools(),
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(
            runner_enabled=False,
            model_provider="disabled",
            model_max_attempts=2,
            model_retry_backoff_seconds=0,
        ),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    with (
        caplog.at_level(
            "WARNING",
            logger="uvicorn.error.deepresearch_workflow.graph",
        ),
        pytest.raises(ModelCallError) as captured,
    ):
        await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert captured.value.error_code == "MODEL_TIMEOUT"
    assert captured.value.failure_kind == "TIMEOUT"
    assert captured.value.attempt == 2
    assert model.plan_calls == 2
    attempts = repository.model_budget_rows["model:planner"]
    assert [attempt["status"] for attempt in attempts] == ["UNKNOWN", "UNKNOWN"]
    assert "run_id=run-001" in caplog.text
    assert "operation=model:planner" in caplog.text
    assert "attempt=1" in caplog.text
    assert "attempt=2" in caplog.text
    assert "error_class=TimeoutError" in caplog.text
    assert private_marker not in caplog.text
    retry_event = next(
        event for event in repository.events if event.event_type == "MODEL_RETRY_SCHEDULED"
    )
    assert retry_event.safe_payload["attempts"] == 1
    failure_event = next(
        event for event in repository.events if event.event_type == "MODEL_CALL_FAILED"
    )
    assert failure_event.safe_payload == {
        "operation": "model:planner",
        "attempts": 2,
        "reasonCode": "MODEL_TIMEOUT",
        "retryable": True,
    }


@pytest.mark.asyncio
async def test_graph_fans_out_validates_citations_and_succeeds(
    repository: FakeRepository,
) -> None:
    tools = FakeTools()
    graph = DurableResearchGraph(
        model=HappyModel(),
        tools=tools,
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "SUCCEEDED"
    assert result["citations"] == ["source-kb_search"]
    assert len(tools.calls) == 2
    assert result["usage"]["model_calls"] == 5
    assert result["usage"]["tool_calls"] == 2
    assert [row[0] for row in repository.progress] == [
        "PLANNING",
        "WORKING",
        "REVIEWING",
        "SYNTHESIZING",
    ]


@pytest.mark.asyncio
async def test_graph_restores_technical_identifier_before_search_tool_call(
    repository: FakeRepository,
) -> None:
    class DriftedWorkerQueryModel(HappyModel):
        async def plan(self, **kwargs: Any) -> ModelCall[PlanOutput]:
            return ModelCall(
                value=PlanOutput(
                    tasks=[
                        PlannedTaskDraft(
                            objective="Find checkpoint guarantees",
                            query="checkpoint persistence semantics",
                            tool=ToolName.KB_SEARCH,
                        )
                    ],
                    summary="Search the project knowledge base",
                ),
                usage=UsageDelta(model_calls=1),
            )

        async def prepare_worker(
            self, *, question: str, task: WorkItem
        ) -> ModelCall[WorkerPreparation]:
            return ModelCall(
                value=WorkerPreparation(
                    focused_query="LangGraph checkpoint guarantee",
                    safe_summary="Search checkpoint guarantees",
                ),
                usage=UsageDelta(model_calls=1),
            )

    tools = FakeTools()
    graph = DurableResearchGraph(
        model=DriftedWorkerQueryModel(),
        tools=tools,
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()
    state = initial_state()
    state["question"] = "durability=sync 在当前项目里保证什么, 又不能保证什么?"
    state["requested_scopes"] = ["kb_search"]

    result = await graph.ainvoke(state, {"max_concurrency": 2})

    assert result["final_status"] == "SUCCEEDED"
    assert len(tools.calls) == 1
    assert tools.calls[0].arguments["query"] == (
        "LangGraph checkpoint guarantee durability=sync"
    )


@pytest.mark.asyncio
async def test_graph_compacts_evidence_indexed_markers_into_public_contract(
    repository: FakeRepository,
) -> None:
    class EvidenceIndexedModel(HappyModel):
        async def plan(self, **kwargs: Any) -> ModelCall[PlanOutput]:
            result = await super().plan(**kwargs)
            return result.model_copy(
                update={"value": result.value.model_copy(update={"tasks": result.value.tasks[:1]})}
            )

        async def synthesize(self, **kwargs: Any) -> ModelCall[SynthesisOutput]:
            return ModelCall(
                value=SynthesisOutput(
                    answer="First [来源1]. Third [来源3]. Sixth [来源6]. Reuse [来源1].",
                    citations=["source-1", "source-3", "source-6"],
                    grounded=True,
                ),
                usage=UsageDelta(model_calls=1),
            )

    class SixEvidenceTools:
        async def execute(self, request: ToolExecutionRequest) -> ToolExecutionResult:
            return ToolExecutionResult(
                call_id=request.call_id,
                evidence=[
                    ToolEvidence(source_id=f"source-{index}", content=f"Evidence {index}")
                    for index in range(1, 7)
                ],
            )

    graph = DurableResearchGraph(
        model=EvidenceIndexedModel(),
        tools=SixEvidenceTools(),
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "SUCCEEDED"
    assert result["final_answer"] == "First [来源1]. Third [来源2]. Sixth [来源3]. Reuse [来源1]."
    assert result["citations"] == ["source-1", "source-3", "source-6"]
    synthesis_event = next(
        event for event in repository.events if event.event_type == "SYNTHESIS_COMPLETED"
    )
    assert synthesis_event.safe_payload["citationContract"] == ("VALID_EVIDENCE_INDEXED_NORMALIZED")
    assert synthesis_event.safe_payload["citationContractNormalized"] is True
    assert synthesis_event.safe_payload["citationNormalizationMode"] == (
        "EVIDENCE_POSITIONAL_COMPACTION"
    )
    assert synthesis_event.safe_payload["maxMarker"] == 6


@pytest.mark.asyncio
async def test_graph_replays_settled_typed_model_result_after_checkpoint_gap(
    repository: FakeRepository,
) -> None:
    model = CountingModel()
    planned = PlanOutput(
        tasks=[
            PlannedTaskDraft(
                objective="Find the source",
                query="supported",
                tool=ToolName.KB_SEARCH,
            )
        ],
        summary="Replay the durable typed plan",
    )
    repository.model_budget_rows["model:planner"] = [
        {
            "attempt": 1,
            "status": "UNKNOWN",
            "claim_token": "claim",
        },
        {
            "attempt": 2,
            "status": "SETTLED",
            "claim_token": "old-claim",
            "value": planned.model_dump(mode="json"),
            "usage": UsageDelta(model_calls=1, input_tokens=7, output_tokens=3),
        },
    ]
    graph = DurableResearchGraph(
        model=model,
        tools=FakeTools(),
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "SUCCEEDED"
    assert model.plan_calls == 0
    assert result["plan_summary"] == "Replay the durable typed plan"
    assert result["usage"]["model_calls"] == 5


@pytest.mark.asyncio
async def test_graph_applies_the_claimed_run_task_budget(
    repository: FakeRepository,
) -> None:
    tools = FakeTools()
    graph = DurableResearchGraph(
        model=HappyModel(),
        tools=tools,
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(max_tasks=1),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "SUCCEEDED"
    assert len(tools.calls) == 1
    assert result["usage"]["tool_calls"] == 1


@pytest.mark.asyncio
async def test_full_initial_plan_exhausts_total_task_budget_without_revision(
    repository: FakeRepository,
) -> None:
    class FullPlanRevisionModel(HappyModel):
        def __init__(self) -> None:
            self.review_arguments: list[dict[str, Any]] = []

        async def plan(self, **kwargs: Any) -> ModelCall[PlanOutput]:
            return ModelCall(
                value=PlanOutput(
                    tasks=[
                        PlannedTaskDraft(
                            objective=f"Find source {index}",
                            query=f"source query {index}",
                            tool=ToolName.KB_SEARCH,
                        )
                        for index in range(1, 5)
                    ],
                    summary="Use the complete four-task budget",
                ),
                usage=UsageDelta(model_calls=1),
            )

        async def review(self, **kwargs: Any) -> ModelCall[ReviewOutput]:
            self.review_arguments.append(kwargs)
            # A provider may ignore may_revise. The graph must still enforce the
            # run-level budget instead of dispatching this proposed fifth task.
            return ModelCall(
                value=ReviewOutput(
                    sufficient=False,
                    summary="One evidence gap remains",
                    revision_tasks=[
                        RevisionTaskDraft(
                            objective="Try a fifth source",
                            query="fifth source query",
                            tool=ToolName.KB_SEARCH,
                            revision_of="task-01",
                        )
                    ],
                ),
                usage=UsageDelta(model_calls=1),
            )

    model = FullPlanRevisionModel()
    tools = FakeTools(empty=True)
    graph = DurableResearchGraph(
        model=model,
        tools=tools,
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(max_tasks=4, max_revision_rounds=1),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "INSUFFICIENT_EVIDENCE"
    assert result["worker_task_count"] == 4
    assert result["revision_round"] == 0
    assert result["should_revise"] is False
    assert result["revision_blocked_reason"] == "TASK_BUDGET_EXHAUSTED"
    assert len(tools.calls) == 4
    assert model.review_arguments[0]["may_revise"] is False
    assert model.review_arguments[0]["max_revision_tasks"] == 0
    assert not any(event.event_type == "REVISION_STARTED" for event in repository.events)


@pytest.mark.asyncio
async def test_revision_is_capped_by_remaining_total_task_capacity(
    repository: FakeRepository,
) -> None:
    class PartialCapacityModel(HappyModel):
        def __init__(self) -> None:
            self.review_arguments: list[dict[str, Any]] = []

        async def plan(self, **kwargs: Any) -> ModelCall[PlanOutput]:
            return ModelCall(
                value=PlanOutput(
                    tasks=[
                        PlannedTaskDraft(
                            objective=f"Find initial source {index}",
                            query=f"initial query {index}",
                            tool=ToolName.KB_SEARCH,
                        )
                        for index in range(1, 4)
                    ],
                    summary="Leave capacity for one revision task",
                ),
                usage=UsageDelta(model_calls=1),
            )

        async def review(self, **kwargs: Any) -> ModelCall[ReviewOutput]:
            self.review_arguments.append(kwargs)
            if len(self.review_arguments) == 1:
                return ModelCall(
                    value=ReviewOutput(
                        sufficient=False,
                        summary="Three possible gaps remain",
                        revision_tasks=[
                            RevisionTaskDraft(
                                objective=f"Fill evidence gap {index}",
                                query=f"revision query {index}",
                                tool=ToolName.KB_SEARCH,
                                revision_of=f"task-0{index}",
                            )
                            for index in range(1, 4)
                        ],
                    ),
                    usage=UsageDelta(model_calls=1),
                )
            return ModelCall(
                value=ReviewOutput(sufficient=False, summary="Evidence remains insufficient"),
                usage=UsageDelta(model_calls=1),
            )

    model = PartialCapacityModel()
    tools = FakeTools(empty=True)
    graph = DurableResearchGraph(
        model=model,
        tools=tools,
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(max_tasks=4, max_revision_rounds=1),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "INSUFFICIENT_EVIDENCE"
    assert result["worker_task_count"] == 4
    assert result["revision_round"] == 1
    assert len(tools.calls) == 4
    assert model.review_arguments[0]["may_revise"] is True
    assert model.review_arguments[0]["max_revision_tasks"] == 1
    assert model.review_arguments[1]["may_revise"] is False
    assert model.review_arguments[1]["max_revision_tasks"] == 0
    revision_event = next(
        event for event in repository.events if event.event_type == "REVISION_STARTED"
    )
    assert revision_event.safe_payload == {"round": 1, "taskCount": 1}


@pytest.mark.asyncio
async def test_parallel_workers_cannot_overspend_model_call_budget(
    repository: FakeRepository,
) -> None:
    model = CountingModel()
    tools = FakeTools()
    graph = DurableResearchGraph(
        model=model,
        tools=tools,
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(max_model_calls=2),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    with pytest.raises(RunBudgetExceededError):
        await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    # Planner spends one call. Only one of the two fan-out workers may spend the
    # remaining call; the other is rejected before invoking the model or a tool.
    assert model.plan_calls + model.worker_calls == 2
    assert model.worker_calls == 1
    assert len(tools.calls) <= 1


@pytest.mark.asyncio
async def test_parallel_workers_reserve_tool_budget_before_execute(
    repository: FakeRepository,
) -> None:
    model = CountingModel()
    tools = FakeTools()
    graph = DurableResearchGraph(
        model=model,
        tools=tools,
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(max_tool_calls=1),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    with pytest.raises(RunBudgetExceededError):
        await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    # Both workers may prepare concurrently at the graph level, but only the worker
    # owning the single atomic reservation can reach the external tool client.
    assert len(tools.calls) == 1
    assert sum(event.event_type == "TASK_STARTED" for event in repository.events) == 1


@pytest.mark.asyncio
async def test_graph_allows_only_one_revision_then_returns_insufficient(
    repository: FakeRepository,
) -> None:
    model = OneRevisionModel()
    tools = FakeTools(empty=True)
    graph = DurableResearchGraph(
        model=model,
        tools=tools,
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "INSUFFICIENT_EVIDENCE"
    assert result["revision_round"] == 1
    assert model.review_calls == 2
    assert len(tools.calls) == 2


@pytest.mark.asyncio
async def test_graph_preserves_java_receipt_state_without_caching_evidence(
    repository: FakeRepository,
) -> None:
    class OneTaskModel(HappyModel):
        async def plan(self, **kwargs: Any) -> ModelCall[PlanOutput]:
            result = await super().plan(**kwargs)
            return result.model_copy(
                update={"value": result.value.model_copy(update={"tasks": result.value.tasks[:1]})}
            )

    class AmbiguousTools:
        async def execute(self, request: ToolExecutionRequest) -> ToolExecutionResult:
            raise ToolReceiptStateError("MCP_RESULT_UNKNOWN")

    graph = DurableResearchGraph(
        model=OneTaskModel(),
        tools=AmbiguousTools(),
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "INSUFFICIENT_EVIDENCE"
    assert result["evidence"][0]["error_code"] == "MCP_RESULT_UNKNOWN"
    assert len(repository.tool_budget_rows) == 1
    assert next(iter(repository.tool_budget_rows.values()))["status"] == "SETTLED"


@pytest.mark.asyncio
async def test_synthesizer_without_valid_citation_is_not_grounded(
    repository: FakeRepository,
) -> None:
    class NoCitationModel(HappyModel):
        async def synthesize(self, **kwargs: Any) -> ModelCall[SynthesisOutput]:
            return ModelCall(
                value=SynthesisOutput(
                    answer="Unsupported despite the flag",
                    citations=["invented-source"],
                    grounded=True,
                ),
                usage=UsageDelta(model_calls=1),
            )

    graph = DurableResearchGraph(
        model=NoCitationModel(),
        tools=FakeTools(),
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "FAILED"
    assert result["error_code"] == "CITATION_VALIDATION_FAILED"
    assert result["error_message"] == "citation validation failed: UNKNOWN_SOURCE"
    assert result["final_answer"] == ""
    assert result["citations"] == []
    synthesis_event = next(
        event for event in repository.events if event.event_type == "SYNTHESIS_COMPLETED"
    )
    assert synthesis_event.safe_payload["citationContract"] == "UNKNOWN_SOURCE"
    assert "answer" not in synthesis_event.safe_payload


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "contract_error",
    ["MISSING_MARKERS", "MARKER_OUT_OF_RANGE", "TOO_MANY_CITATIONS"],
)
async def test_provider_evidence_index_error_fails_closed_with_exact_reason(
    repository: FakeRepository,
    contract_error: str,
) -> None:
    class InvalidEvidenceIndexModel(HappyModel):
        async def synthesize(self, **kwargs: Any) -> ModelCall[SynthesisOutput]:
            return ModelCall(
                value=SynthesisOutput(
                    answer="A provider answer that must never be published [来源99].",
                    citations=[],
                    grounded=True,
                    citation_contract_error=contract_error,
                ),
                usage=UsageDelta(model_calls=1),
            )

    graph = DurableResearchGraph(
        model=InvalidEvidenceIndexModel(),
        tools=FakeTools(),
        repository=repository,
        events=FakeEventSink(repository),
        settings=Settings(runner_enabled=False, model_provider="disabled"),
        budget=RunBudget(),
        runtime=GraphRuntime(claim_token="claim"),
    ).compile()

    result = await graph.ainvoke(initial_state(), {"max_concurrency": 2})

    assert result["final_status"] == "FAILED"
    assert result["error_code"] == "CITATION_VALIDATION_FAILED"
    assert result["error_message"] == f"citation validation failed: {contract_error}"
    assert result["final_answer"] == ""
    assert result["citations"] == []
    synthesis_event = next(
        event for event in repository.events if event.event_type == "SYNTHESIS_COMPLETED"
    )
    assert synthesis_event.safe_payload["citationContract"] == contract_error
    assert "answer" not in synthesis_event.safe_payload
    assert "source" not in synthesis_event.safe_payload


@pytest.mark.parametrize(
    ("answer", "citations", "expected_code"),
    [
        ("Claim without a marker", ["source-a"], "MISSING_MARKERS"),
        ("Claim [来源2]", ["source-a"], "MARKER_OUT_OF_RANGE"),
        ("Claim [来源1]", ["invented-source"], "UNKNOWN_SOURCE"),
        (
            "Second source first [来源2], then first [来源1]",
            ["source-a", "source-b"],
            "MARKER_ORDER_MISMATCH",
        ),
        (
            "One source [来源1]",
            ["source-a", "source-a"],
            "DUPLICATE_CITATIONS",
        ),
    ],
)
def test_citation_contract_rejects_ambiguous_mappings(
    answer: str,
    citations: list[str],
    expected_code: str,
) -> None:
    valid, code = validate_citation_contract(answer, citations, {"source-a", "source-b"})

    assert valid is False
    assert code == expected_code


def test_citation_contract_accepts_first_appearance_order_and_reuse() -> None:
    valid, code = validate_citation_contract(
        "First claim [来源1]. Reuse it [来源1]. Second claim [来源2].",
        ["source-a", "source-b"],
        {"source-a", "source-b"},
    )

    assert valid is True
    assert code == "VALID"


def test_citation_contract_compacts_reordered_evidence_markers_and_reuse() -> None:
    result = resolve_citation_contract(
        "Sixth [来源6]. Third [来源3]. Sixth again [来源6].",
        ["source-6", "source-3"],
        [f"source-{index}" for index in range(1, 7)],
    )

    assert result.valid is True
    assert result.normalized is True
    assert result.answer == "Sixth [来源1]. Third [来源2]. Sixth again [来源1]."
    assert result.citations == ("source-6", "source-3")


def test_citation_contract_merges_duplicate_evidence_source_ids() -> None:
    result = resolve_citation_contract(
        "Same source twice [来源1] [来源3].",
        ["source-a"],
        ["source-a", "source-b", "source-a"],
    )

    assert result.valid is True
    assert result.answer == "Same source twice [来源1] [来源1]."
    assert result.citations == ("source-a",)


def test_citation_contract_compacts_duplicate_declared_citations() -> None:
    result = resolve_citation_contract(
        (
            "Overview [来源1]. Java [来源2]. Terminal [来源3]. "
            "Java reused through duplicate index [来源4]. "
            "Terminal reused through duplicate index [来源5]. State [来源6]."
        ),
        ["source-a", "source-b", "source-c", "source-b", "source-c", "source-d"],
        ["source-a", "source-b", "source-c", "source-d"],
    )

    assert result.valid is True
    assert result.normalized is True
    assert result.code == "VALID_DUPLICATE_CITATIONS_NORMALIZED"
    assert result.answer == (
        "Overview [来源1]. Java [来源2]. Terminal [来源3]. "
        "Java reused through duplicate index [来源2]. "
        "Terminal reused through duplicate index [来源3]. State [来源4]."
    )
    assert result.citations == ("source-a", "source-b", "source-c", "source-d")


def test_citation_contract_normalizes_six_position_regression_shape() -> None:
    """A collapsed four-ID list caused the case-018 failure; an explicit table is safe."""

    result = resolve_citation_contract(
        (
            "Client deduplicates [来源1]. Server replays after the cursor [来源2]. "
            "The SSE ID follows the same contract [来源3]. Boundaries apply [来源4]. "
            "Exactly-once is excluded [来源5]. Database tests exist [来源6]."
        ),
        ["source-3", "source-2", "source-2", "source-4", "source-4", "source-1"],
        [f"evidence-{index}" for index in range(1, 15)]
        + ["source-1", "source-2", "source-3", "source-4"],
    )

    assert result.valid is True
    assert result.normalized is True
    assert result.code == "VALID_DUPLICATE_CITATIONS_NORMALIZED"
    assert result.answer == (
        "Client deduplicates [来源1]. Server replays after the cursor [来源2]. "
        "The SSE ID follows the same contract [来源2]. Boundaries apply [来源3]. "
        "Exactly-once is excluded [来源3]. Database tests exist [来源4]."
    )
    assert result.citations == ("source-3", "source-2", "source-4", "source-1")


def test_citation_contract_normalizes_nine_position_regression_shape() -> None:
    """A repeated source at position seven remains unambiguous when declared explicitly."""

    result = resolve_citation_contract(
        " ".join(f"Claim {index} [来源{index}]." for index in range(1, 10)),
        [
            "source-1",
            "source-2",
            "source-3",
            "source-4",
            "source-5",
            "source-6",
            "source-6",
            "source-7",
            "source-8",
        ],
        [f"evidence-{index}" for index in range(1, 32)]
        + [f"source-{index}" for index in range(1, 9)],
    )

    assert result.valid is True
    assert result.normalized is True
    assert result.code == "VALID_DUPLICATE_CITATIONS_NORMALIZED"
    assert result.answer == (
        "Claim 1 [来源1]. Claim 2 [来源2]. Claim 3 [来源3]. Claim 4 [来源4]. "
        "Claim 5 [来源5]. Claim 6 [来源6]. Claim 7 [来源6]. Claim 8 [来源7]. "
        "Claim 9 [来源8]."
    )
    assert result.citations == tuple(f"source-{index}" for index in range(1, 9))


def test_citation_contract_does_not_guess_collapsed_marker_positions() -> None:
    """Unique IDs cannot recover which source belonged to omitted duplicate positions."""

    result = resolve_citation_contract(
        " ".join(f"Claim {index} [来源{index}]." for index in range(1, 7)),
        ["source-3", "source-2", "source-4", "source-1"],
        [f"evidence-{index}" for index in range(1, 10)]
        + ["source-1", "source-2", "source-3", "source-4", "evidence-14"],
    )

    assert result.valid is False
    assert result.code == "EVIDENCE_CITATION_MISMATCH"
    assert result.answer == ""
    assert result.citations == ()


def test_citation_contract_rejects_duplicate_declaration_with_unknown_source() -> None:
    result = resolve_citation_contract(
        "Known [来源1]. Unknown [来源2]. Duplicate [来源3].",
        ["source-a", "invented-source", "source-a"],
        ["source-a"],
    )

    assert result.valid is False
    assert result.code == "UNKNOWN_SOURCE"
    assert result.answer == ""
    assert result.citations == ()


def test_citation_contract_rejects_partially_used_duplicate_declaration() -> None:
    result = resolve_citation_contract(
        "Only the first position is used [来源1].",
        ["source-a", "source-a"],
        ["source-a"],
    )

    assert result.valid is False
    assert result.code == "MARKER_ORDER_MISMATCH"
    assert result.answer == ""
    assert result.citations == ()


def test_citation_contract_rejects_evidence_mapping_mismatch_without_guessing() -> None:
    result = resolve_citation_contract(
        "First [来源1]. Third [来源3]. Sixth [来源6].",
        ["source-1", "source-6", "source-3"],
        [f"source-{index}" for index in range(1, 7)],
    )

    assert result.valid is False
    assert result.code == "EVIDENCE_CITATION_MISMATCH"
    assert result.answer == ""
    assert result.citations == ()


def test_citation_contract_rejects_marker_beyond_full_evidence() -> None:
    result = resolve_citation_contract(
        "Impossible [来源7].",
        ["source-1"],
        [f"source-{index}" for index in range(1, 7)],
    )

    assert result.valid is False
    assert result.code == "MARKER_OUT_OF_RANGE"

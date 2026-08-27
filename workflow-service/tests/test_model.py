from __future__ import annotations

import json
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from deepresearch_workflow.domain import (
    PlannedTaskDraft,
    PlanOutput,
    ReviewOutput,
    SynthesisDraft,
    ToolName,
    WorkerPreparation,
    WorkItem,
)
from deepresearch_workflow.model import (
    OpenAIWorkflowModel,
    ProviderCallError,
    StructuredOutputError,
    classify_model_failure,
)
from deepresearch_workflow.settings import Settings


class StatusFailure(RuntimeError):
    def __init__(self, *, status_code: int) -> None:
        super().__init__("private provider response")
        self.status_code = status_code


def adapter_with_response(response: dict) -> OpenAIWorkflowModel:
    class StructuredModel:
        async def ainvoke(self, messages):
            return response

    class Model:
        def with_structured_output(self, schema, *, method, strict, include_raw):
            return StructuredModel()

    adapter = object.__new__(OpenAIWorkflowModel)
    adapter._model = Model()
    adapter._input_rate = 1.0
    adapter._output_rate = 2.0
    return adapter


@pytest.mark.asyncio
async def test_missing_provider_usage_uses_conservative_nonzero_estimate() -> None:
    parsed = PlanOutput(
        tasks=[
            PlannedTaskDraft(
                objective="Find source evidence",
                query="evidence query",
                tool=ToolName.KB_SEARCH,
            )
        ],
        summary="One focused task",
    )

    class StructuredModel:
        messages = None

        async def ainvoke(self, messages):
            self.messages = messages
            return {
                "parsed": parsed,
                "raw": SimpleNamespace(usage_metadata={}, response_metadata={}),
                "parsing_error": None,
            }

    class Model:
        def __init__(self) -> None:
            self.schema = None
            self.method = None
            self.strict = None
            self.include_raw = None
            self.structured = StructuredModel()

        def with_structured_output(self, schema, *, method, strict, include_raw):
            self.schema = schema
            self.method = method
            self.strict = strict
            self.include_raw = include_raw
            return self.structured

    adapter = object.__new__(OpenAIWorkflowModel)
    model = Model()
    adapter._model = model
    adapter._input_rate = 1.0
    adapter._output_rate = 2.0

    result = await adapter._call(
        PlanOutput,
        instruction="Create a plan",
        payload={"question": "中文问题", "allowed_tools": ["kb_search"]},
    )

    assert result.usage.model_calls == 1
    assert result.usage.input_tokens > 0
    assert result.usage.output_tokens > 0
    assert result.usage.cost_cny > 0
    assert model.schema is PlanOutput
    assert model.method == "function_calling"
    assert model.strict is True
    assert model.include_raw is True
    assert model.structured.messages is not None
    system_content = model.structured.messages[0].content
    assert "required structured-output function" in system_content
    assert "Create a plan" in system_content
    assert "JSON Schema" not in system_content


@pytest.mark.asyncio
async def test_synthesis_uses_evidence_indexes_and_derives_public_citations() -> None:
    parsed = SynthesisDraft(
        answer="Second [来源2]. First [来源1]. Reuse second [来源2].",
        grounded=True,
    )

    class StructuredModel:
        messages = None

        async def ainvoke(self, messages):
            self.messages = messages
            return {
                "parsed": parsed,
                "raw": SimpleNamespace(usage_metadata={}, response_metadata={}),
                "parsing_error": None,
            }

    class Model:
        def __init__(self) -> None:
            self.structured = StructuredModel()

        def with_structured_output(self, schema, *, method, strict, include_raw):
            assert schema is SynthesisDraft
            return self.structured

    adapter = object.__new__(OpenAIWorkflowModel)
    model = Model()
    adapter._model = model
    adapter._input_rate = 1.0
    adapter._output_rate = 2.0

    result = await adapter.synthesize(
        question="How does replay work?",
        evidence=[
            {"source_id": "source-a", "content": "First evidence"},
            {"source_id": "source-b", "content": "Second evidence"},
        ],
        review_summary="Sufficient",
    )

    system_content = model.structured.messages[0].content
    assert "Do not output a citations field" in system_content
    assert "N is that supporting entry's evidence_index" in system_content
    assert "Never renumber markers into a compact local list" in system_content
    payload = json.loads(model.structured.messages[1].content)
    assert [item["evidence_index"] for item in payload["evidence"]] == [1, 2]
    assert result.value.answer == (
        "Second [来源1]. First [来源2]. Reuse second [来源1]."
    )
    assert result.value.citations == ["source-b", "source-a"]
    assert result.value.citation_contract_error is None
    assert result.usage.model_calls == 1


@pytest.mark.asyncio
async def test_worker_instruction_requires_verbatim_technical_identifiers() -> None:
    parsed = WorkerPreparation(
        focused_query="checkpoint durability=sync",
        safe_summary="Search checkpoint durability semantics",
    )

    class StructuredModel:
        messages = None

        async def ainvoke(self, messages):
            self.messages = messages
            return {
                "parsed": parsed,
                "raw": SimpleNamespace(usage_metadata={}, response_metadata={}),
                "parsing_error": None,
            }

    class Model:
        def __init__(self) -> None:
            self.structured = StructuredModel()

        def with_structured_output(self, schema, *, method, strict, include_raw):
            assert schema is WorkerPreparation
            return self.structured

    adapter = object.__new__(OpenAIWorkflowModel)
    model = Model()
    adapter._model = model
    adapter._input_rate = 1.0
    adapter._output_rate = 2.0

    await adapter.prepare_worker(
        question="durability=sync 保证什么?",
        task=WorkItem(
            task_id="task-01",
            objective="Find checkpoint guarantees",
            query="checkpoint durability semantics",
            tool=ToolName.KB_SEARCH,
        ),
    )

    assert model.structured.messages is not None
    system_content = model.structured.messages[0].content
    assert "copy every high-information technical identifier" in system_content
    assert "Do not translate, paraphrase, reformat, or omit" in system_content


@pytest.mark.asyncio
async def test_structured_output_failure_exposes_only_safe_metadata() -> None:
    private_parser_message = "PRIVATE_MODEL_OUTPUT must never be logged"

    class StructuredModel:
        async def ainvoke(self, messages):
            return {
                "parsed": None,
                "raw": SimpleNamespace(
                    content="",
                    tool_calls=[],
                    usage_metadata={},
                    response_metadata={"finish_reason": "stop"},
                ),
                "parsing_error": ValueError(private_parser_message),
            }

    class Model:
        def with_structured_output(self, schema, *, method, strict, include_raw):
            return StructuredModel()

    adapter = object.__new__(OpenAIWorkflowModel)
    adapter._model = Model()
    adapter._input_rate = 1.0
    adapter._output_rate = 2.0

    with pytest.raises(StructuredOutputError) as captured:
        await adapter._call(
            PlanOutput,
            instruction="Create a plan",
            payload={"question": "private input"},
        )

    failure = captured.value
    assert str(failure) == "provider returned unusable structured output"
    assert private_parser_message not in str(failure)
    assert failure.schema_name == "PlanOutput"
    assert failure.parser_error_type == "ValueError"
    assert failure.finish_reason == "stop"
    assert failure.content_state == "empty"
    assert failure.tool_call_count == 0
    assert failure.failure_kind == "SCHEMA"
    assert failure.retryable is True


@pytest.mark.asyncio
async def test_reviewer_recovers_explicit_null_to_declared_list_default(
    caplog: pytest.LogCaptureFixture,
) -> None:
    private_summary = "PRIVATE_REVIEW_SUMMARY"
    args = {
        "sufficient": True,
        "summary": private_summary,
        # An OpenAI-compatible provider may emit null for an optional array even though
        # its declared application default is an empty list.
        "revision_tasks": None,
    }
    with pytest.raises(ValidationError) as invalid:
        ReviewOutput.model_validate(args)
    raw = SimpleNamespace(
        content="",
        tool_calls=[
            {
                "name": "ReviewOutput",
                "args": args,
                "id": "safe-call-id",
                "type": "tool_call",
            }
        ],
        usage_metadata={},
        response_metadata={"finish_reason": "tool_calls"},
    )
    adapter = adapter_with_response(
        {"parsed": None, "raw": raw, "parsing_error": invalid.value}
    )

    with caplog.at_level(
        "WARNING", logger="uvicorn.error.deepresearch_workflow.model"
    ):
        result = await adapter._call(
            ReviewOutput,
            instruction="Review evidence",
            payload={"question": "private question"},
        )

    assert result.value == ReviewOutput(
        sufficient=True,
        summary=private_summary,
        revision_tasks=[],
    )
    assert "schema=ReviewOutput" in caplog.text
    assert "strategy=declared_default_for_null" in caplog.text
    assert "normalized_fields=revision_tasks" in caplog.text
    assert private_summary not in caplog.text
    assert "private question" not in caplog.text


@pytest.mark.asyncio
async def test_synthesizer_recovers_one_exact_valid_raw_tool_call(
    caplog: pytest.LogCaptureFixture,
) -> None:
    args = {
        "answer": "Checkpoint state is durable [来源1].",
        "grounded": True,
    }
    raw = SimpleNamespace(
        content="",
        tool_calls=[
            {
                "name": "SynthesisDraft",
                "args": args,
                "id": "safe-synthesis-call-id",
                "type": "tool_call",
            }
        ],
        usage_metadata={},
        response_metadata={"finish_reason": "tool_calls"},
    )
    adapter = adapter_with_response(
        {
            "parsed": None,
            "raw": raw,
            "parsing_error": ValueError("private SDK parser state"),
        }
    )

    with caplog.at_level(
        "WARNING", logger="uvicorn.error.deepresearch_workflow.model"
    ):
        result = await adapter._call(
            SynthesisDraft,
            instruction="Synthesize evidence",
            payload={"question": "private question"},
        )

    assert result.value == SynthesisDraft.model_validate(args)
    assert "schema=SynthesisDraft" in caplog.text
    assert "strategy=validated_tool_args" in caplog.text
    assert "private question" not in caplog.text


@pytest.mark.asyncio
async def test_synthesizer_instruction_requires_every_output_field() -> None:
    parsed = SynthesisDraft(
        answer="Grounded answer [来源1].",
        grounded=True,
    )

    class StructuredModel:
        messages = None

        async def ainvoke(self, messages):
            self.messages = messages
            return {
                "parsed": parsed,
                "raw": SimpleNamespace(usage_metadata={}, response_metadata={}),
                "parsing_error": None,
            }

    class CapturingModel:
        structured = StructuredModel()
        schema = None

        def with_structured_output(self, schema, **_kwargs):
            self.schema = schema
            return self.structured

    adapter = OpenAIWorkflowModel.__new__(OpenAIWorkflowModel)
    adapter._model = CapturingModel()
    adapter._input_rate = 0.0
    adapter._output_rate = 0.0

    await adapter.synthesize(
        question="question",
        evidence=[{"source_id": "source-1", "content": "Evidence"}],
        review_summary="sufficient",
    )

    instruction = adapter._model.structured.messages[0].content
    assert adapter._model.schema is SynthesisDraft
    assert "both required fields" in instruction
    assert "Do not output a citations field" in instruction
    assert "answer as a non-empty string" in instruction
    assert "grounded as a JSON boolean" in instruction


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("schema", "args"),
    [
        (
            PlanOutput,
            {
                "tasks": [
                    {
                        "objective": "Find primary evidence",
                        "query": "checkpoint durability",
                        "tool": "kb_search",
                    }
                ],
                "summary": "One focused task",
            },
        ),
        (
            WorkerPreparation,
            {
                "focused_query": "checkpoint durability=sync",
                "safe_summary": "Search the authorized knowledge base",
            },
        ),
        (
            ReviewOutput,
            {
                "sufficient": True,
                "summary": "Evidence supports the answer",
                "revision_tasks": [],
            },
        ),
    ],
)
async def test_exact_raw_tool_call_recovery_is_shared_by_structured_schemas(
    schema,
    args: dict,
) -> None:
    raw = SimpleNamespace(
        content="",
        tool_calls=[{"name": schema.__name__, "args": args}],
        usage_metadata={},
        response_metadata={"finish_reason": "tool_calls"},
    )
    adapter = adapter_with_response(
        {
            "parsed": None,
            "raw": raw,
            "parsing_error": ValueError("private SDK parser state"),
        }
    )

    result = await adapter._call(
        schema,
        instruction="Produce structured output",
        payload={"question": "private question"},
    )

    assert result.value == schema.model_validate(args)


def synthesis_draft_args() -> dict:
    return {
        "answer": "Grounded answer [来源1].",
        "grounded": True,
    }


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "tool_calls",
    [
        [{"name": "WrongSchema", "args": synthesis_draft_args()}],
        [
            {"name": "SynthesisDraft", "args": synthesis_draft_args()},
            {"name": "SynthesisDraft", "args": synthesis_draft_args()},
        ],
        [
            {
                "name": "SynthesisDraft",
                "args": {
                    "answer": "a" * (128 * 1024),
                    "grounded": True,
                },
            }
        ],
    ],
    ids=["wrong-function-name", "multiple-tool-calls", "arguments-over-128-kib"],
)
async def test_synthesizer_raw_recovery_rejects_unsafe_tool_call_shapes(
    tool_calls: list[dict],
) -> None:
    raw = SimpleNamespace(
        content="",
        tool_calls=tool_calls,
        usage_metadata={},
        response_metadata={"finish_reason": "tool_calls"},
    )
    adapter = adapter_with_response(
        {
            "parsed": None,
            "raw": raw,
            "parsing_error": ValueError("private SDK parser state"),
        }
    )

    with pytest.raises(StructuredOutputError):
        await adapter._call(
            SynthesisDraft,
            instruction="Synthesize evidence",
            payload={"question": "private question"},
        )


@pytest.mark.asyncio
async def test_synthesizer_raw_recovery_rejects_unknown_field() -> None:
    args = {**synthesis_draft_args(), "PRIVATE_UNKNOWN_KEY": "PRIVATE_UNKNOWN_VALUE"}
    with pytest.raises(ValidationError) as invalid:
        SynthesisDraft.model_validate(args)
    raw = SimpleNamespace(
        content="",
        tool_calls=[{"name": "SynthesisDraft", "args": args}],
        usage_metadata={},
        response_metadata={"finish_reason": "tool_calls"},
    )
    adapter = adapter_with_response(
        {"parsed": None, "raw": raw, "parsing_error": invalid.value}
    )

    with pytest.raises(StructuredOutputError) as captured:
        await adapter._call(
            SynthesisDraft,
            instruction="Synthesize evidence",
            payload={"question": "private question"},
        )

    assert captured.value.validation_issue_codes == "unknown_field:extra_forbidden"
    assert "PRIVATE_UNKNOWN_KEY" not in str(captured.value)
    assert "PRIVATE_UNKNOWN_VALUE" not in str(captured.value)


@pytest.mark.asyncio
async def test_synthesizer_rejects_citations_only_provider_shape() -> None:
    args = {"citations": ["source-1"]}
    with pytest.raises(ValidationError) as invalid:
        SynthesisDraft.model_validate(args)
    raw = SimpleNamespace(
        content="",
        tool_calls=[{"name": "SynthesisDraft", "args": args}],
        usage_metadata={},
        response_metadata={"finish_reason": "tool_calls"},
    )
    adapter = adapter_with_response(
        {"parsed": None, "raw": raw, "parsing_error": invalid.value}
    )

    with pytest.raises(StructuredOutputError) as captured:
        await adapter._call(
            SynthesisDraft,
            instruction="Synthesize evidence",
            payload={"question": "private question"},
        )

    assert "answer:missing" in captured.value.validation_issue_codes
    assert "grounded:missing" in captured.value.validation_issue_codes
    assert "unknown_field:extra_forbidden" in captured.value.validation_issue_codes


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("answer", "expected_code"),
    [
        ("Answer without an exact marker.", "MISSING_MARKERS"),
        ("Answer with an invalid position [来源2].", "MARKER_OUT_OF_RANGE"),
    ],
)
async def test_synthesizer_marks_invalid_evidence_indexes_without_hidden_retry(
    answer: str,
    expected_code: str,
) -> None:
    parsed = SynthesisDraft(answer=answer, grounded=True)
    adapter = adapter_with_response(
        {
            "parsed": parsed,
            "raw": SimpleNamespace(usage_metadata={}, response_metadata={}),
            "parsing_error": None,
        }
    )

    result = await adapter.synthesize(
        question="question",
        evidence=[{"source_id": "source-1", "content": "Evidence"}],
        review_summary="sufficient",
    )

    assert result.value.answer == answer
    assert result.value.citations == []
    assert result.value.grounded is True
    assert result.value.citation_contract_error == expected_code
    assert result.usage.model_calls == 1


@pytest.mark.asyncio
async def test_reviewer_instruction_requires_array_instead_of_null() -> None:
    parsed = ReviewOutput(
        sufficient=True,
        summary="Evidence is sufficient",
        revision_tasks=[],
    )

    class StructuredModel:
        messages = None

        async def ainvoke(self, messages):
            self.messages = messages
            return {
                "parsed": parsed,
                "raw": SimpleNamespace(usage_metadata={}, response_metadata={}),
                "parsing_error": None,
            }

    structured = StructuredModel()

    class Model:
        def with_structured_output(self, schema, *, method, strict, include_raw):
            return structured

    adapter = object.__new__(OpenAIWorkflowModel)
    adapter._model = Model()
    adapter._input_rate = 1.0
    adapter._output_rate = 2.0

    await adapter.review(
        question="Is the evidence sufficient?",
        tasks=[],
        evidence=[],
        may_revise=False,
        max_revision_tasks=0,
    )

    system_content = structured.messages[0].content
    assert "summary must be no more than 1200 Unicode characters" in system_content
    assert "do not recap each source or quote evidence" in system_content
    assert "revision_tasks must always be a JSON array" in system_content
    assert "use [] instead of null" in system_content
    assert "If sufficient is true, revision_tasks must be []" in system_content
    assert "at most max_revision_tasks" in system_content
    assert '"max_revision_tasks":0' in structured.messages[1].content


@pytest.mark.asyncio
async def test_structured_recovery_keeps_extra_forbid_and_redacts_unknown_key() -> None:
    private_key = "PRIVATE_SECRET_FIELD"
    args = {
        "sufficient": True,
        "summary": "Enough evidence",
        "revision_tasks": [],
        private_key: "PRIVATE_SECRET_VALUE",
    }
    with pytest.raises(ValidationError) as invalid:
        ReviewOutput.model_validate(args)
    raw = SimpleNamespace(
        content="",
        tool_calls=[{"name": "ReviewOutput", "args": args}],
        usage_metadata={},
        response_metadata={"finish_reason": "tool_calls"},
    )
    adapter = adapter_with_response(
        {"parsed": None, "raw": raw, "parsing_error": invalid.value}
    )

    with pytest.raises(StructuredOutputError) as captured:
        await adapter._call(
            ReviewOutput,
            instruction="Review evidence",
            payload={"question": "private question"},
        )

    failure = captured.value
    assert failure.validation_issue_codes == "unknown_field:extra_forbidden"
    assert private_key not in str(failure)
    assert "PRIVATE_SECRET_VALUE" not in str(failure)


@pytest.mark.asyncio
async def test_structured_recovery_does_not_weaken_semantic_validator() -> None:
    args = {
        "sufficient": True,
        "summary": "Contradictory decision",
        "revision_tasks": [
            {
                "objective": "Find stronger evidence",
                "query": "official evidence",
                "tool": "web_search",
            }
        ],
    }
    with pytest.raises(ValidationError) as invalid:
        ReviewOutput.model_validate(args)
    raw = SimpleNamespace(
        content="",
        tool_calls=[{"name": "ReviewOutput", "args": args}],
        usage_metadata={},
        response_metadata={"finish_reason": "tool_calls"},
    )
    adapter = adapter_with_response(
        {"parsed": None, "raw": raw, "parsing_error": invalid.value}
    )

    with pytest.raises(StructuredOutputError) as captured:
        await adapter._call(
            ReviewOutput,
            instruction="Review evidence",
            payload={"question": "private question"},
        )

    assert captured.value.validation_issue_codes == "root:value_error"


@pytest.mark.asyncio
async def test_structured_recovery_never_parses_natural_language_content() -> None:
    private_content = (
        '{"sufficient":true,"summary":"must not accept",'
        '"revision_tasks":[]}'
    )
    raw = SimpleNamespace(
        content=private_content,
        tool_calls=[],
        usage_metadata={},
        response_metadata={"finish_reason": "stop"},
    )
    adapter = adapter_with_response(
        {
            "parsed": None,
            "raw": raw,
            "parsing_error": ValueError("private parser message"),
        }
    )

    with pytest.raises(StructuredOutputError) as captured:
        await adapter._call(
            ReviewOutput,
            instruction="Review evidence",
            payload={"question": "private question"},
        )

    assert captured.value.content_state == "present"
    assert captured.value.tool_call_count == 0
    assert "must not accept" not in str(captured.value)


@pytest.mark.asyncio
async def test_provider_rate_limit_is_classified_without_copying_private_content() -> None:
    private_message = "PRIVATE PROMPT sk-secret must never be logged"

    class RateLimitError(RuntimeError):
        def __init__(self) -> None:
            super().__init__(private_message)
            self.status_code = 429
            self.request_id = "req-safe-429"
            self.body = {"code": "rate_limit_exceeded", "message": private_message}

    class StructuredModel:
        async def ainvoke(self, messages):
            raise RateLimitError()

    class Model:
        def with_structured_output(self, schema, *, method, strict, include_raw):
            return StructuredModel()

    adapter = object.__new__(OpenAIWorkflowModel)
    adapter._model = Model()
    adapter._input_rate = 1.0
    adapter._output_rate = 2.0

    with pytest.raises(ProviderCallError) as captured:
        await adapter._call(
            PlanOutput,
            instruction="Create a plan",
            payload={"question": "private input"},
        )

    failure = captured.value
    assert str(failure) == "model provider request failed"
    assert private_message not in str(failure)
    assert failure.failure_kind == "RATE_LIMIT"
    assert failure.retryable is True
    assert failure.status_code == 429
    assert failure.code == "rate_limit_exceeded"
    assert failure.request_id == "req-safe-429"
    assert failure.__cause__ is None
    assert failure.__context__ is None


@pytest.mark.parametrize(
    ("failure", "kind", "retryable"),
    [
        (TimeoutError("private timeout context"), "TIMEOUT", True),
        (
            StatusFailure(status_code=400),
            "PROVIDER",
            False,
        ),
        (
            StatusFailure(status_code=503),
            "PROVIDER",
            True,
        ),
    ],
)
def test_model_failure_retry_taxonomy(
    failure: Exception,
    kind: str,
    retryable: bool,
) -> None:
    classified = classify_model_failure(failure)

    assert classified.failure_kind == kind
    assert classified.retryable is retryable
    assert "private" not in str(classified)


def test_deepseek_disables_thinking_for_forced_structured_tool_call(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict = {}

    class CapturingChatOpenAI:
        def __init__(self, **kwargs):
            captured.update(kwargs)

    monkeypatch.setattr("deepresearch_workflow.model.ChatOpenAI", CapturingChatOpenAI)
    OpenAIWorkflowModel(
        Settings(
            runner_enabled=False,
            model_provider="disabled",
            model_name="deepseek-v4-flash",
            openai_base_url="https://api.deepseek.com",
        )
    )

    assert captured["extra_body"] == {
        "thinking": {"type": "disabled"},
        "max_tokens": 1_500,
    }
    assert "max_tokens" not in captured
    assert captured["max_retries"] == 0


def test_openai_keeps_native_output_token_parameter(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict = {}

    class CapturingChatOpenAI:
        def __init__(self, **kwargs):
            captured.update(kwargs)

    monkeypatch.setattr("deepresearch_workflow.model.ChatOpenAI", CapturingChatOpenAI)
    OpenAIWorkflowModel(
        Settings(
            runner_enabled=False,
            model_provider="disabled",
            model_name="gpt-5-mini",
        )
    )

    assert captured["max_tokens"] == 1_500
    assert "extra_body" not in captured
    assert captured["max_retries"] == 0

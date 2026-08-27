from __future__ import annotations

from deepresearch_workflow.domain import ToolName, WorkItem
from deepresearch_workflow.query_fidelity import (
    extract_technical_identifiers,
    preserve_search_query_identifiers,
)


def task(*, tool: ToolName = ToolName.KB_SEARCH, query: str = "checkpoint semantics") -> WorkItem:
    return WorkItem(
        task_id="task-01",
        objective="Find exact technical guarantees",
        query=query,
        tool=tool,
    )


def test_extracts_verbatim_high_information_identifiers_without_nested_numbers() -> None:
    identifiers = extract_technical_identifiers(
        "Check `thread_id`, MODEL_SCHEMA_INVALID, durability=sync, HTTP/2, v1.2.3 and 90s."
    )

    assert identifiers == (
        "thread_id",
        "MODEL_SCHEMA_INVALID",
        "durability=sync",
        "HTTP/2",
        "v1.2.3",
        "90s",
    )


def test_search_query_appends_identifiers_omitted_by_model_verbatim() -> None:
    result = preserve_search_query_identifiers(
        "checkpoint persistence guarantees",
        question="durability=sync 保证什么, 不能保证什么?",
        task=task(),
    )

    assert result == "checkpoint persistence guarantees durability=sync"


def test_search_query_restores_original_case_and_multiple_identifier_kinds() -> None:
    result = preserve_search_query_identifiers(
        "why model_schema_invalid happens",
        question="HTTP/2 下 MODEL_SCHEMA_INVALID 是否会在 30s 后重试?",
        task=task(tool=ToolName.WEB_SEARCH, query="provider failure behavior"),
    )

    assert "HTTP/2" in result
    assert "MODEL_SCHEMA_INVALID" in result
    assert "30s" in result
    assert len(result) <= 1_000


def test_calculator_expression_is_not_changed_by_search_fidelity_guard() -> None:
    result = preserve_search_query_identifiers(
        "6 * 7",
        question="计算 6 * 7",
        task=task(tool=ToolName.CALCULATOR, query="6 * 7"),
    )

    assert result == "6 * 7"


def test_query_limit_trims_model_prose_before_a_required_identifier() -> None:
    result = preserve_search_query_identifiers(
        "x" * 1_000,
        question="Use durability=sync exactly",
        task=task(),
    )

    assert len(result) == 1_000
    assert result.endswith(" durability=sync")

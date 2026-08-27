from __future__ import annotations

import pytest

from deepresearch_workflow.citation_mapping import map_evidence_indexed_citations


def test_evidence_indexes_are_compacted_by_first_source_appearance() -> None:
    result = map_evidence_indexed_citations(
        "Sixth [来源6]. Third [来源3]. Same source through another record [来源5].",
        ["source-1", "source-2", "source-3", "source-4", "source-6", "source-6"],
    )

    assert result.valid is True
    assert result.code == "VALID_EVIDENCE_INDEXED_NORMALIZED"
    assert result.answer == (
        "Sixth [来源1]. Third [来源2]. Same source through another record [来源1]."
    )
    assert result.citations == ("source-6", "source-3")
    assert result.marker_count == 3
    assert result.max_marker == 6


@pytest.mark.parametrize(
    ("answer", "expected_code"),
    [
        ("No exact marker", "MISSING_MARKERS"),
        ("Zero is never a valid position [来源0]", "MARKER_OUT_OF_RANGE"),
        ("Second is absent [来源2]", "MARKER_OUT_OF_RANGE"),
    ],
)
def test_invalid_evidence_indexes_fail_without_an_answer_or_citations(
    answer: str,
    expected_code: str,
) -> None:
    result = map_evidence_indexed_citations(answer, ["source-1"])

    assert result.valid is False
    assert result.code == expected_code
    assert result.answer == ""
    assert result.citations == ()


def test_more_than_public_citation_limit_fails_closed() -> None:
    source_ids = [f"source-{index}" for index in range(1, 32)]
    answer = " ".join(f"Claim {index} [来源{index}]." for index in range(1, 32))

    result = map_evidence_indexed_citations(answer, source_ids)

    assert result.valid is False
    assert result.code == "TOO_MANY_CITATIONS"
    assert result.answer == ""
    assert result.citations == ()

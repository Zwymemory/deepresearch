from __future__ import annotations

import re
from dataclasses import dataclass

_CITATION_MARKER = re.compile(r"\[来源(\d+)]")
MAX_PUBLIC_CITATIONS = 30


@dataclass(frozen=True)
class EvidenceCitationMapping:
    """Deterministic conversion from evidence indexes to the public citation table."""

    valid: bool
    answer: str
    citations: tuple[str, ...]
    code: str
    marker_count: int
    max_marker: int | None


def map_evidence_indexed_citations(
    answer: str,
    evidence_source_ids: list[str],
) -> EvidenceCitationMapping:
    """Map exact ``[来源N]`` markers to the Nth supplied evidence record.

    This function deliberately has no fuzzy or natural-language recovery path. The
    model can select evidence only through a bounded, one-based integer position that
    the application assigned before the request. Unknown or missing positions fail
    closed, and the caller must not publish the original answer.
    """

    marker_indexes = [int(value) for value in _CITATION_MARKER.findall(answer)]
    marker_count = len(marker_indexes)
    max_marker = max(marker_indexes, default=None)
    if not marker_indexes:
        return EvidenceCitationMapping(
            valid=False,
            answer="",
            citations=(),
            code="MISSING_MARKERS",
            marker_count=marker_count,
            max_marker=max_marker,
        )
    if any(index < 1 or index > len(evidence_source_ids) for index in marker_indexes):
        return EvidenceCitationMapping(
            valid=False,
            answer="",
            citations=(),
            code="MARKER_OUT_OF_RANGE",
            marker_count=marker_count,
            max_marker=max_marker,
        )

    marker_source_ids = [evidence_source_ids[index - 1] for index in marker_indexes]
    citations = list(dict.fromkeys(marker_source_ids))
    if len(citations) > MAX_PUBLIC_CITATIONS:
        return EvidenceCitationMapping(
            valid=False,
            answer="",
            citations=(),
            code="TOO_MANY_CITATIONS",
            marker_count=marker_count,
            max_marker=max_marker,
        )

    public_indexes = {
        source_id: public_index for public_index, source_id in enumerate(citations, start=1)
    }
    normalized_answer = _CITATION_MARKER.sub(
        lambda match: (
            f"[来源{public_indexes[evidence_source_ids[int(match.group(1)) - 1]]}]"
        ),
        answer,
    )
    return EvidenceCitationMapping(
        valid=True,
        answer=normalized_answer,
        citations=tuple(citations),
        code="VALID_EVIDENCE_INDEXED_NORMALIZED",
        marker_count=marker_count,
        max_marker=max_marker,
    )

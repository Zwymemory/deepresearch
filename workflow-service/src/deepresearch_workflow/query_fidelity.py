from __future__ import annotations

import re

from .domain import ToolName, WorkItem

_MAX_QUERY_LENGTH = 1_000
_MAX_IDENTIFIER_LENGTH = 160

_BACKTICK_IDENTIFIER = re.compile(r"(?<!`)`([^`\r\n]{1,160})`(?!`)")
_TECHNICAL_PATTERNS = (
    # Configuration values and command options, for example durability=sync or --seed=42.
    re.compile(
        r"(?<![A-Za-z0-9_])(?:--)?[A-Za-z][A-Za-z0-9_.-]{0,63}"
        r"(?:==|!=|<=|>=|=|:)"
        r"(?:[\"'][^\"'\s]{1,80}[\"']|"
        r"[^\s,\uFF0C;\uFF1B\u3002!?\uFF01\uFF1F)\uFF09\]}>]{1,80})"
    ),
    # API paths and protocol/version identifiers such as /api/research/workflows and HTTP/2.
    re.compile(r"(?<![A-Za-z0-9_])(?:/[A-Za-z0-9._{}:-]+){2,}"),
    re.compile(r"(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9+.-]{1,31}/\d+(?:\.\d+)*"),
    # Explicit versions, including v1.2.3, 3.12 and 1.0-rc1.
    re.compile(
        r"(?<![A-Za-z0-9_])(?:v(?:ersion)?[-_ ]?)?\d+(?:\.\d+){1,3}"
        r"(?:[-+][A-Za-z0-9.]+)?(?![A-Za-z0-9_])",
        re.IGNORECASE,
    ),
    # Error codes, constants, snake_case identifiers and hyphenated technical terms.
    re.compile(r"(?<![A-Za-z0-9_])[A-Z][A-Z0-9]*(?:[_-][A-Z0-9]+)+(?![A-Za-z0-9_])"),
    re.compile(r"(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+(?![A-Za-z0-9_])"),
    re.compile(r"(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+(?![A-Za-z0-9_])"),
    # Short all-caps technical acronyms such as SSE, RAG and JWT.
    re.compile(r"(?<![A-Za-z0-9_])[A-Z][A-Z0-9]{1,15}(?![A-Za-z0-9_])"),
    # Numeric constraints are often part of the fact being retrieved (90s, 0.95, 4 workers).
    re.compile(
        r"(?<![A-Za-z0-9_])[<>≤≥≈~]?\s*[-+]?\d+(?:\.\d+)?"
        r"(?:%|ms|s|m|h|KiB|MiB|GiB|KB|MB|GB|bytes?|tokens?|毫秒|秒|分钟|小时|天|"
        r"字节|位|次|个|元)?(?![A-Za-z0-9_])",
        re.IGNORECASE,
    ),
)


def extract_technical_identifiers(text: str) -> tuple[str, ...]:
    """Return bounded, verbatim technical identifiers in source order.

    The extractor intentionally uses syntax rather than a project-specific vocabulary. This
    keeps query fidelity deterministic while avoiding an ever-growing list of product terms.
    """

    candidates: list[tuple[int, int, str]] = []
    for match in _BACKTICK_IDENTIFIER.finditer(text):
        value = match.group(1).strip()
        if value:
            candidates.append((match.start(1), match.end(1), value))
    for pattern in _TECHNICAL_PATTERNS:
        for match in pattern.finditer(text):
            value = match.group(0).strip()
            if not value or len(value) > _MAX_IDENTIFIER_LENGTH:
                continue
            if value.lstrip("<>≤≥≈~+-").isdigit() and text[: match.start()].endswith("来源"):
                continue
            candidates.append((match.start(), match.end(), value))

    # Prefer the longest candidate when patterns overlap (HTTP/2 instead of the nested "2").
    candidates.sort(key=lambda item: (item[0], -(item[1] - item[0])))
    selected: list[tuple[int, int, str]] = []
    for candidate in candidates:
        start, end, _ = candidate
        overlaps = any(
            start < chosen_end and end > chosen_start
            for chosen_start, chosen_end, _ in selected
        )
        if overlaps:
            continue
        selected.append(candidate)

    identifiers: list[str] = []
    seen: set[str] = set()
    for _, _, value in sorted(selected, key=lambda item: item[0]):
        if value not in seen:
            seen.add(value)
            identifiers.append(value)
    return tuple(identifiers)


def preserve_search_query_identifiers(
    focused_query: str,
    *,
    question: str,
    task: WorkItem,
) -> str:
    """Append verbatim technical identifiers omitted by a search-query model response."""

    if task.tool not in {ToolName.KB_SEARCH, ToolName.WEB_SEARCH}:
        return focused_query

    identifiers: list[str] = []
    seen: set[str] = set()
    # The authorized task query is most specific, then its objective, then the user question.
    for source in (task.query, task.objective, question):
        for identifier in extract_technical_identifiers(source):
            if identifier not in seen:
                seen.add(identifier)
                identifiers.append(identifier)

    base = focused_query.strip()
    missing = [identifier for identifier in identifiers if identifier not in base]
    if not missing:
        return base

    # Keep every complete identifier that fits the schema boundary. When space is tight, trim
    # model prose rather than truncating an identifier and changing its retrieval semantics.
    suffix_parts: list[str] = []
    suffix_length = 0
    for identifier in missing:
        separator = 1 if suffix_parts else 0
        if suffix_length + separator + len(identifier) > _MAX_QUERY_LENGTH:
            continue
        suffix_parts.append(identifier)
        suffix_length += separator + len(identifier)

    if not suffix_parts:
        return base[:_MAX_QUERY_LENGTH].rstrip()

    suffix = " ".join(suffix_parts)
    available_for_base = _MAX_QUERY_LENGTH - len(suffix) - 1
    if base and available_for_base > 0:
        trimmed_base = base[:available_for_base].rstrip()
        if trimmed_base:
            return f"{trimmed_base} {suffix}"
    return suffix

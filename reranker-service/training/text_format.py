"""The single query/document text contract used in training and serving."""

from __future__ import annotations

import re


_WHITESPACE = re.compile(r"\s+")


def clean_text(value: str | None) -> str:
    """Collapse transport whitespace without changing words or punctuation."""

    return _WHITESPACE.sub(" ", value or "").strip()


def format_document(title: str | None, content: str | None) -> str:
    """Match the historical FastAPI document format exactly.

    The labels are intentionally kept in Chinese because changing them would
    introduce an avoidable train/serve skew relative to the deployed service.
    """

    return f"标题：{clean_text(title)}\n正文：{clean_text(content)}".strip()


def format_pair(query: str | None, title: str | None, content: str | None) -> tuple[str, str]:
    return clean_text(query), format_document(title, content)

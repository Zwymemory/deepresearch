from __future__ import annotations

from pathlib import Path

import pytest

from training.hard_negatives import TrainingDocument, TrainingGroup
from training.scifact import SciFactDataset, SciFactDocument
from training.text_format import format_document, format_pair
from training.train import grouped_ndcg_at_10, validate_group_alignment


def _aligned_dataset_and_group():
    corpus = {
        str(index): SciFactDocument(str(index), f"Title {index}", f"Body {index}")
        for index in range(6)
    }
    dataset = SciFactDataset(
        corpus,
        {"q": "query"},
        {"q": frozenset({"0"})},
        {},
        Path("synthetic"),
    )
    group = TrainingGroup(
        query_id="q",
        query="query",
        positive=TrainingDocument("0", "Title 0", "Body 0"),
        negatives=tuple(
            TrainingDocument(str(index), f"Title {index}", f"Body {index}")
            for index in range(1, 6)
        ),
    )
    return dataset, group


def test_train_and_serve_share_one_text_contract() -> None:
    assert format_document("  title\n", "line  one\nline two") == (
        "标题：title\n正文：line one line two"
    )
    assert format_pair(" query\ntext ", "title", "body") == (
        "query text",
        "标题：title\n正文：body",
    )


def test_group_alignment_rejects_stale_or_tampered_data() -> None:
    dataset, group = _aligned_dataset_and_group()
    validate_group_alignment([group], ["q"], dataset, split_name="train")

    tampered = TrainingGroup(
        group.query_id,
        group.query,
        group.positive,
        group.negatives[:-1]
        + (TrainingDocument("5", "Title 5", "tampered"),),
    )
    with pytest.raises(ValueError, match="differs from source"):
        validate_group_alignment([tampered], ["q"], dataset, split_name="train")


def test_grouped_ndcg_uses_positive_at_index_zero() -> None:
    perfect = grouped_ndcg_at_10([[9, 5, 4, 3, 2, 1]])
    last = grouped_ndcg_at_10([[0, 5, 4, 3, 2, 1]])
    assert perfect == 1.0
    assert last == pytest.approx(1.0 / 2.807354922057604)

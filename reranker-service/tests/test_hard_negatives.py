from __future__ import annotations

from pathlib import Path

from training.hard_negatives import (
    TrainingGroup,
    load_groups,
    mine_training_groups,
    reciprocal_rank_fusion,
    write_groups,
)
from training.scifact import SciFactDataset, SciFactDocument


def _dataset() -> SciFactDataset:
    corpus = {
        str(index): SciFactDocument(str(index), f"Title {index}", f"Body {index}")
        for index in range(1, 9)
    }
    return SciFactDataset(
        corpus=corpus,
        queries={"q": "claim"},
        train_qrels={"q": frozenset({"1", "2"})},
        test_qrels={},
        data_dir=Path("synthetic"),
    )


def test_rrf_is_deterministic_and_deduplicates_each_source() -> None:
    fused = reciprocal_rank_fusion({"dense": ["2", "1"], "bm25": ["1", "1", "3"]})
    assert [item.doc_id for item in fused] == ["1", "2", "3"]
    assert fused[0].source_ranks == {"bm25": 1, "dense": 2}


def test_mining_excludes_all_positives_and_emits_one_plus_five(tmp_path) -> None:
    dataset = _dataset()
    groups = mine_training_groups(
        dataset,
        ["q"],
        {
            "bm25": {"q": ["1", "3", "4", "5", "6", "7", "8"]},
            "dense": {"q": ["2", "4", "3", "8", "7", "6", "5"]},
        },
        candidate_pool_size=8,
    )
    assert len(groups) == 2
    assert {group.positive.doc_id for group in groups} == {"1", "2"}
    for group in groups:
        assert len(group.negatives) == 5
        assert {item.doc_id for item in group.negatives}.isdisjoint({"1", "2"})
        group.validate()

    output = tmp_path / "groups.jsonl"
    write_groups(groups, output)
    loaded = load_groups(output)
    assert loaded == groups
    assert '"sourceRanks"' in output.read_text(encoding="utf-8")


def test_group_schema_rejects_a_false_negative() -> None:
    dataset = _dataset()
    groups = mine_training_groups(dataset, ["q"], {}, candidate_pool_size=8)
    raw = groups[0].to_dict()
    raw["negatives"][0]["docId"] = raw["positive"]["docId"]  # type: ignore[index]
    try:
        TrainingGroup.from_dict(raw)
    except ValueError as error:
        assert "positive document" in str(error)
    else:
        raise AssertionError("false negative was accepted")


def test_legacy_snake_case_evidence_accepts_a_zero_reranker_score() -> None:
    group = mine_training_groups(_dataset(), ["q"], {}, candidate_pool_size=8)[0]
    raw = group.to_dict()
    evidence = raw["negatives"][0]["evidence"]  # type: ignore[index]
    evidence["base_reranker_score"] = 0.0  # type: ignore[index]
    evidence.pop("baseRerankerScore", None)  # type: ignore[union-attr]
    parsed = TrainingGroup.from_dict(raw)
    assert parsed.negatives[0].evidence.base_reranker_score == 0.0  # type: ignore[union-attr]

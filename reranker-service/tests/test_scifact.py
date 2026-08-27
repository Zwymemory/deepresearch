from __future__ import annotations

from pathlib import Path

import pytest

from training.scifact import SciFactDataset, build_split, load_scifact, normalize_query


def _large_contract_dataset() -> SciFactDataset:
    queries = {f"tr{i:04d}": f"unique training claim {i}" for i in range(809)}
    queries.update({f"te{i:04d}": f"unique test claim {i}" for i in range(300)})
    queries["tr0000"] = "Obesity decreases life quality."
    queries["te0000"] = "OBESITY decreases life quality"
    queries["tr0001"] = "There is no association between HNF4A mutations and diabetes risks!"
    queries["te0001"] = "there is no association between hnf4a mutations and diabetes risks"

    train_qrels = {f"tr{i:04d}": frozenset({f"d{i:04d}"}) for i in range(809)}
    # 196 test queries reuse a cleaned train positive; the other 104 form the
    # strict test whose positives have never appeared in train/dev.
    test_qrels = {
        f"te{i:04d}": frozenset({f"d{i + 2:04d}" if i < 196 else f"strict{i:04d}"})
        for i in range(300)
    }
    return SciFactDataset({}, queries, train_qrels, test_qrels, Path("synthetic"))


def test_normalize_query_folds_case_punctuation_and_whitespace() -> None:
    assert normalize_query("  HNF4A—Mutations!!  ") == "hnf4a mutations"


def test_deterministic_contract_is_exactly_703_104_300_104() -> None:
    dataset = _large_contract_dataset()
    first = build_split(dataset)
    second = build_split(dataset)

    assert first == second
    assert len(first.train_query_ids) == 703
    assert len(first.dev_query_ids) == 104
    assert len(first.test_query_ids) == 300
    assert len(first.strict_test_query_ids) == 104
    assert first.removed_train_query_ids == ("tr0000", "tr0001")
    first.validate(dataset)


def test_queries_sharing_a_positive_are_never_split() -> None:
    queries = {f"q{i}": f"train {i}" for i in range(6)} | {"t1": "test only"}
    train_qrels = {
        "q0": frozenset({"shared"}),
        "q1": frozenset({"shared"}),
        **{f"q{i}": frozenset({f"d{i}"}) for i in range(2, 6)},
    }
    dataset = SciFactDataset(
        {}, queries, train_qrels, {"t1": frozenset({"test-doc"})}, Path("synthetic")
    )
    split = build_split(dataset, dev_size=2)
    assert ("q0" in split.dev_query_ids) == ("q1" in split.dev_query_ids)
    split.validate(dataset)


def test_repository_scifact_snapshot_matches_audited_counts_when_present() -> None:
    raw = Path(__file__).resolve().parents[2] / "testdata/open/scifact/raw/scifact"
    if not (raw / "corpus.jsonl").is_file():
        pytest.skip("ignored full SciFact snapshot is not present")
    split = build_split(load_scifact(raw))
    assert (
        len(split.train_query_ids),
        len(split.dev_query_ids),
        len(split.test_query_ids),
        len(split.strict_test_query_ids),
    ) == (703, 104, 300, 104)
    assert split.removed_train_query_ids == ("871", "1291")

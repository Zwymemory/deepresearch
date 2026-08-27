from __future__ import annotations

import math

import pytest

from training.evaluate import RankingRun, compare_runs
from training.metrics import (
    BootstrapInterval,
    RankingMetrics,
    deployment_gate,
    paired_bootstrap_delta,
    ranking_metrics,
)


def test_standard_metrics_distinguish_recall_from_hit_rate() -> None:
    metrics = ranking_metrics(["r1", "x", "r2"], {"r1", "r2", "r3"}, k=3)
    expected_dcg = 1.0 + 1.0 / math.log2(4)
    expected_idcg = 1.0 + 1.0 / math.log2(3) + 1.0 / math.log2(4)
    assert metrics.ndcg_at_k == pytest.approx(expected_dcg / expected_idcg)
    assert metrics.mrr_at_k == 1.0
    assert metrics.recall_at_k == pytest.approx(2 / 3)
    assert metrics.hit_rate_at_k == 1.0


def test_paired_bootstrap_is_seeded_and_query_level() -> None:
    baseline = {"q1": 0.0, "q2": 0.5, "q3": 1.0}
    candidate = {"q1": 1.0, "q2": 0.75, "q3": 1.0}
    assert paired_bootstrap_delta(baseline, candidate, samples=200) == paired_bootstrap_delta(
        baseline, candidate, samples=200
    )


def test_deployment_gate_enforces_all_thresholds() -> None:
    baseline = RankingMetrics(0.50, 0.50, 0.80, 0.90)
    candidate = RankingMetrics(0.52, 0.55, 0.80, 0.90)
    interval = BootstrapInterval(0.02, 0.001, 0.04, 10_000, 42)
    gate = deployment_gate(
        baseline=baseline,
        candidate=candidate,
        ndcg_interval=interval,
        strict_baseline_ndcg=0.50,
        strict_candidate_ndcg=0.51,
        baseline_p95_ms=100,
        candidate_p95_ms=114,
    )
    assert gate.eligible
    assert not gate.reasons


def test_compare_runs_requires_the_same_candidate_pool() -> None:
    baseline = RankingRun({"q": ("x", "relevant")}, (10.0,), "same")
    candidate = RankingRun({"q": ("relevant", "x")}, (11.0,), "same")
    report = compare_runs(
        baseline,
        candidate,
        {"q": ("relevant",)},
        ("q",),
        bootstrap_samples=20,
    )
    assert report["candidatePool"]["identicalPerQuery"] is True  # type: ignore[index]
    assert report["candidate"]["nDCG@10"] == 1.0  # type: ignore[index]

    with pytest.raises(ValueError, match="identical candidate documents"):
        compare_runs(
            baseline,
            RankingRun({"q": ("relevant", "other")}, (11.0,), "same"),
            {"q": ("relevant",)},
            ("q",),
            bootstrap_samples=20,
        )

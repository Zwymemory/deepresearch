"""Dependency-free information-retrieval metrics and paired bootstrap tests."""

from __future__ import annotations

import math
import random
from dataclasses import asdict, dataclass
from statistics import fmean
from typing import Iterable, Mapping, Sequence

from .config import DEFAULT_SEED


@dataclass(frozen=True)
class RankingMetrics:
    ndcg_at_k: float
    mrr_at_k: float
    recall_at_k: float
    hit_rate_at_k: float


@dataclass(frozen=True)
class BootstrapInterval:
    mean_delta: float
    lower_95: float
    upper_95: float
    samples: int
    seed: int


@dataclass(frozen=True)
class DeploymentGate:
    eligible: bool
    reasons: tuple[str, ...]


def ranking_metrics(
    ranked_doc_ids: Sequence[str], relevant_doc_ids: Iterable[str], *, k: int = 10
) -> RankingMetrics:
    if k <= 0:
        raise ValueError("k must be positive")
    relevant = set(relevant_doc_ids)
    if not relevant:
        raise ValueError("at least one relevant document is required")
    ranked = list(dict.fromkeys(str(doc_id) for doc_id in ranked_doc_ids))[:k]
    hits = [1 if doc_id in relevant else 0 for doc_id in ranked]
    hit_count = sum(hits)

    dcg = sum(gain / math.log2(rank + 2) for rank, gain in enumerate(hits))
    ideal_hits = min(len(relevant), k)
    idcg = sum(1.0 / math.log2(rank + 2) for rank in range(ideal_hits))
    first_relevant = next((rank for rank, hit in enumerate(hits, start=1) if hit), None)
    return RankingMetrics(
        ndcg_at_k=dcg / idcg if idcg else 0.0,
        mrr_at_k=1.0 / first_relevant if first_relevant else 0.0,
        recall_at_k=hit_count / len(relevant),
        hit_rate_at_k=1.0 if hit_count else 0.0,
    )


def per_query_metrics(
    rankings: Mapping[str, Sequence[str]],
    qrels: Mapping[str, Iterable[str]],
    *,
    k: int = 10,
) -> dict[str, RankingMetrics]:
    missing = sorted(set(qrels) - set(rankings))
    if missing:
        raise ValueError(f"rankings missing {len(missing)} qrels queries; first={missing[:3]}")
    return {qid: ranking_metrics(rankings[qid], relevant, k=k) for qid, relevant in qrels.items()}


def aggregate_metrics(values: Mapping[str, RankingMetrics]) -> RankingMetrics:
    if not values:
        raise ValueError("cannot aggregate an empty metric set")
    rows = list(values.values())
    return RankingMetrics(
        ndcg_at_k=fmean(row.ndcg_at_k for row in rows),
        mrr_at_k=fmean(row.mrr_at_k for row in rows),
        recall_at_k=fmean(row.recall_at_k for row in rows),
        hit_rate_at_k=fmean(row.hit_rate_at_k for row in rows),
    )


def paired_bootstrap_delta(
    baseline: Mapping[str, float],
    candidate: Mapping[str, float],
    *,
    samples: int = 10_000,
    seed: int = DEFAULT_SEED,
) -> BootstrapInterval:
    if samples <= 0:
        raise ValueError("samples must be positive")
    if set(baseline) != set(candidate) or not baseline:
        raise ValueError("baseline and candidate must contain the same non-empty query ids")
    query_ids = sorted(baseline)
    deltas = [float(candidate[qid]) - float(baseline[qid]) for qid in query_ids]
    observed = fmean(deltas)
    rng = random.Random(seed)
    count = len(deltas)
    bootstrap_means = []
    for _ in range(samples):
        bootstrap_means.append(fmean(deltas[rng.randrange(count)] for _ in range(count)))
    bootstrap_means.sort()
    return BootstrapInterval(
        mean_delta=observed,
        lower_95=_percentile_sorted(bootstrap_means, 0.025),
        upper_95=_percentile_sorted(bootstrap_means, 0.975),
        samples=samples,
        seed=seed,
    )


def latency_summary(latencies_ms: Sequence[float]) -> dict[str, float]:
    if not latencies_ms:
        raise ValueError("latencies must not be empty")
    values = sorted(float(value) for value in latencies_ms)
    if values[0] < 0:
        raise ValueError("latencies cannot be negative")
    return {
        "p50Ms": _percentile_sorted(values, 0.50),
        "p95Ms": _percentile_sorted(values, 0.95),
    }


def deployment_gate(
    *,
    baseline: RankingMetrics,
    candidate: RankingMetrics,
    ndcg_interval: BootstrapInterval,
    strict_baseline_ndcg: float,
    strict_candidate_ndcg: float,
    baseline_p95_ms: float | None,
    candidate_p95_ms: float | None,
) -> DeploymentGate:
    reasons: list[str] = []
    if candidate.ndcg_at_k - baseline.ndcg_at_k < 0.010:
        reasons.append("delta_nDCG@10_below_0.010")
    if ndcg_interval.lower_95 <= 0.0:
        reasons.append("nDCG@10_bootstrap_lower_bound_not_positive")
    if candidate.recall_at_k - baseline.recall_at_k < -0.005:
        reasons.append("Recall@10_regression_over_0.005")
    if candidate.hit_rate_at_k - baseline.hit_rate_at_k < -0.005:
        reasons.append("HitRate@10_regression_over_0.005")
    if strict_candidate_ndcg + 1e-12 < strict_baseline_ndcg:
        reasons.append("strict_test_nDCG@10_regressed")
    if baseline_p95_ms is None or candidate_p95_ms is None:
        reasons.append("p95_latency_missing")
    elif candidate_p95_ms > baseline_p95_ms * 1.15:
        reasons.append("p95_latency_over_1.15x")
    return DeploymentGate(eligible=not reasons, reasons=tuple(reasons))


def metric_dict(value: RankingMetrics) -> dict[str, float]:
    return {
        "nDCG@10": value.ndcg_at_k,
        "MRR@10": value.mrr_at_k,
        "Recall@10": value.recall_at_k,
        "HitRate@10": value.hit_rate_at_k,
    }


def bootstrap_dict(value: BootstrapInterval) -> dict[str, float | int]:
    raw = asdict(value)
    return {
        "meanDelta": raw["mean_delta"],
        "lower95": raw["lower_95"],
        "upper95": raw["upper_95"],
        "samples": raw["samples"],
        "seed": raw["seed"],
    }


def _percentile_sorted(values: Sequence[float], probability: float) -> float:
    if not values:
        raise ValueError("values cannot be empty")
    if not 0.0 <= probability <= 1.0:
        raise ValueError("probability must be in [0, 1]")
    position = probability * (len(values) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return values[lower]
    weight = position - lower
    return values[lower] * (1.0 - weight) + values[upper] * weight

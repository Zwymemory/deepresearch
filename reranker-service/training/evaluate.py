"""Compare base and fine-tuned rankings on fixed candidates with a deploy gate."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence

from .config import DEFAULT_SEED
from .metrics import (
    aggregate_metrics,
    bootstrap_dict,
    deployment_gate,
    latency_summary,
    metric_dict,
    paired_bootstrap_delta,
    per_query_metrics,
)
from .scifact import build_split, load_scifact


@dataclass(frozen=True)
class RankingRun:
    rankings: Mapping[str, tuple[str, ...]]
    latencies_ms: tuple[float, ...]
    candidate_run_sha256: str | None = None


def load_ranking_run(path: str | Path) -> RankingRun:
    source = Path(path)
    if source.suffix.lower() == ".jsonl":
        rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
    else:
        value = json.loads(source.read_text(encoding="utf-8"))
        rows = value.get("cases") or value.get("rankings") or []
    rankings: dict[str, tuple[str, ...]] = {}
    latencies: list[float] = []
    candidate_digests: set[str] = set()
    for row in rows:
        qid = str(row.get("queryId") or "")
        ranked = row.get("rankedDocIds")
        if ranked is None:
            ranked = [document.get("docId") for document in row.get("topDocs") or []]
        doc_ids = tuple(dict.fromkeys(str(doc_id) for doc_id in ranked or [] if doc_id is not None))
        if qid and doc_ids:
            rankings[qid] = doc_ids
        if row.get("latencyMs") is not None:
            latencies.append(float(row["latencyMs"]))
        if row.get("candidateRunSha256"):
            candidate_digests.add(str(row["candidateRunSha256"]))
    if not rankings:
        raise ValueError(f"no rankings found in {source}")
    if len(candidate_digests) > 1:
        raise ValueError(f"ranking file mixes candidate runs: {source}")
    digest = next(iter(candidate_digests), None)
    return RankingRun(rankings, tuple(latencies), digest)


def compare_runs(
    baseline: RankingRun,
    candidate: RankingRun,
    qrels: Mapping[str, Sequence[str]],
    strict_query_ids: Sequence[str],
    *,
    bootstrap_samples: int = 10_000,
) -> dict[str, object]:
    baseline_queries = set(baseline.rankings)
    candidate_queries = set(candidate.rankings)
    expected_queries = set(qrels)
    if baseline_queries != candidate_queries or baseline_queries != expected_queries:
        raise ValueError(
            "paired comparison requires exactly the same qrels and query ids; "
            f"baseline={len(baseline_queries)}, candidate={len(candidate_queries)}, qrels={len(expected_queries)}"
        )
    if (
        baseline.candidate_run_sha256
        and candidate.candidate_run_sha256
        and baseline.candidate_run_sha256 != candidate.candidate_run_sha256
    ):
        raise ValueError("baseline and candidate were scored from different candidate runs")
    different_pools = [
        qid
        for qid in sorted(expected_queries)
        if set(baseline.rankings[qid]) != set(candidate.rankings[qid])
    ]
    if different_pools:
        raise ValueError(
            "paired comparison requires identical candidate documents per query; "
            f"different={len(different_pools)}, first={different_pools[:3]}"
        )

    baseline_per_query = per_query_metrics(baseline.rankings, qrels, k=10)
    candidate_per_query = per_query_metrics(candidate.rankings, qrels, k=10)
    baseline_aggregate = aggregate_metrics(baseline_per_query)
    candidate_aggregate = aggregate_metrics(candidate_per_query)
    ndcg_interval = paired_bootstrap_delta(
        {qid: value.ndcg_at_k for qid, value in baseline_per_query.items()},
        {qid: value.ndcg_at_k for qid, value in candidate_per_query.items()},
        samples=bootstrap_samples,
        seed=DEFAULT_SEED,
    )

    strict = tuple(qid for qid in strict_query_ids if qid in qrels)
    strict_baseline = aggregate_metrics({qid: baseline_per_query[qid] for qid in strict})
    strict_candidate = aggregate_metrics({qid: candidate_per_query[qid] for qid in strict})
    baseline_latency = latency_summary(baseline.latencies_ms) if baseline.latencies_ms else None
    candidate_latency = latency_summary(candidate.latencies_ms) if candidate.latencies_ms else None
    gate = deployment_gate(
        baseline=baseline_aggregate,
        candidate=candidate_aggregate,
        ndcg_interval=ndcg_interval,
        strict_baseline_ndcg=strict_baseline.ndcg_at_k,
        strict_candidate_ndcg=strict_candidate.ndcg_at_k,
        baseline_p95_ms=baseline_latency["p95Ms"] if baseline_latency else None,
        candidate_p95_ms=candidate_latency["p95Ms"] if candidate_latency else None,
    )
    baseline_recall_20 = aggregate_metrics(
        per_query_metrics(baseline.rankings, qrels, k=20)
    ).recall_at_k
    candidate_recall_20 = aggregate_metrics(
        per_query_metrics(candidate.rankings, qrels, k=20)
    ).recall_at_k
    return {
        "schemaVersion": 1,
        "pairedQueries": len(qrels),
        "strictTestQueries": len(strict),
        "baseline": metric_dict(baseline_aggregate),
        "candidate": metric_dict(candidate_aggregate),
        "candidatePool": {
            "sha256": baseline.candidate_run_sha256 or candidate.candidate_run_sha256,
            "identicalPerQuery": True,
            "baselineRecall@20": baseline_recall_20,
            "candidateRecall@20": candidate_recall_20,
        },
        "strictTest": {
            "baselineNDCG@10": strict_baseline.ndcg_at_k,
            "candidateNDCG@10": strict_candidate.ndcg_at_k,
        },
        "pairedBootstrapNDCG@10": bootstrap_dict(ndcg_interval),
        "latency": {"baseline": baseline_latency, "candidate": candidate_latency},
        "deploymentGate": {"eligible": gate.eligible, "reasons": list(gate.reasons)},
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--bootstrap-samples", type=int, default=10_000)
    args = parser.parse_args()

    dataset = load_scifact(args.data_dir)
    split = build_split(dataset)
    report = compare_runs(
        load_ranking_run(args.baseline),
        load_ranking_run(args.candidate),
        {qid: tuple(dataset.test_qrels[qid]) for qid in split.test_query_ids},
        split.strict_test_query_ids,
        bootstrap_samples=args.bootstrap_samples,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()

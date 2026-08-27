#!/usr/bin/env python3
"""Print a compact summary for /api/eval/retrieval JSON output."""

from __future__ import annotations

import json
import sys
from pathlib import Path


ROUTES = ["vectorOnly", "keywordOnly", "noRrfMerge", "rrfFusion", "rrfDocFusion", "rerankResult", "rerankDocResult"]


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: summarize-retrieval-eval.py <eval-result.json>")

    path = Path(sys.argv[1])
    data = json.loads(path.read_text(encoding="utf-8"))
    print(f"dataset={data.get('dataset')} topK={data.get('topK')} totalCases={data.get('totalCases')}")
    print()
    print("route,hitCount,HitRate@K,Recall@K,MRR,NDCG@K")
    for route in ROUTES:
        metrics = data.get("metrics", {}).get(route, {})
        if not metrics:
            continue
        print(
            f"{route},"
            f"{metrics.get('hitCount')},"
            f"{metrics.get('hitRateAtK')},"
            f"{metrics.get('recallAtK')},"
            f"{metrics.get('mrr')},"
            f"{metrics.get('ndcgAtK')}"
        )

    print()
    if data.get("rerankSummary"):
        print("rerankSummary=" + json.dumps(data["rerankSummary"], ensure_ascii=False))
        print()

    for route in ROUTES:
        misses = [
            case for case in data.get("cases", [])
            if case.get("routes", {}).get(route, {}).get("matchMode") == "miss"
        ]
        print(f"{route} misses={len(misses)}")
        for case in misses[:10]:
            expected = case.get("expectedDocKeys") or case.get("expectedChunkKeys") or []
            print(f"  {case.get('id')}: {case.get('question')} | expected={expected}")
        if len(misses) > 10:
            print(f"  ... {len(misses) - 10} more")
        print()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Recompute published showcase scores offline without changing any artifact."""

from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path
from types import SimpleNamespace

from showcase_eval import score


ROOT = Path(__file__).resolve().parents[2]
CATALOG = Path(__file__).with_name("showcase_artifacts.json")


def repository_file(name: str) -> Path:
    path = (ROOT / name).resolve()
    if not path.is_relative_to(ROOT) or not path.is_file():
        raise ValueError(f"Missing or non-repository artifact: {name}")
    return path


def first_difference(actual, expected, location="$"):
    if type(actual) is not type(expected):
        return location + " (type differs)"
    if isinstance(actual, dict):
        if actual.keys() != expected.keys():
            return location + " (keys differ)"
        for key in actual:
            difference = first_difference(actual[key], expected[key], f"{location}.{key}")
            if difference:
                return difference
    elif isinstance(actual, list):
        if len(actual) != len(expected):
            return location + " (length differs)"
        for index, (left, right) in enumerate(zip(actual, expected)):
            difference = first_difference(left, right, f"{location}[{index}]")
            if difference:
                return difference
    elif actual != expected:
        return location + " (value differs)"
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    if catalog.get("schemaVersion") != 1 or not catalog.get("artifacts"):
        parser.error("catalog requires schemaVersion=1 and at least one artifact")
    names = [item["name"] for item in catalog["artifacts"]]
    if len(names) != len(set(names)):
        parser.error("duplicate artifact names")
    failures = []
    with tempfile.TemporaryDirectory(prefix="deepresearch-offline-scores-") as directory:
        for index, item in enumerate(catalog["artifacts"]):
            try:
                actual = score(SimpleNamespace(
                    capture=[repository_file(path) for path in item["captures"]],
                    review=repository_file(item["review"]) if item.get("review") else None,
                    output=Path(directory) / f"score-{index}.json",
                ))
                expected = json.loads(repository_file(item["score"]).read_text(encoding="utf-8"))
                difference = first_difference(actual, expected)
                if difference:
                    raise ValueError("published score differs at " + difference)
                print("PASS " + item["name"])
            except (ValueError, KeyError, TypeError, OSError) as error:
                failures.append(item["name"])
                print(f"FAIL {item['name']}: {error}")
    if failures:
        raise SystemExit(f"{len(failures)} published score(s) failed offline verification")
    print(f"Verified {len(names)} published scores; no network requests or credentials used.")


if __name__ == "__main__":
    main()

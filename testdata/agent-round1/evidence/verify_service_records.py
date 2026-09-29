"""Validate actual Java/PG exports against the unchanged frozen schemas and rules."""

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "contracts/agent/v0"))
sys.path.insert(0, str(ROOT / "testdata/agent-foundation/evidence"))
from validate_knowledge import validate_bundle  # noqa: E402
from validate_runtime import load_json, validate_external_reference, validator  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input",
        type=Path,
        default=ROOT / "target/evidence-round1-service-records.json",
    )
    args = parser.parse_args()
    freeze = load_json(ROOT / "contracts/agent/v0/freeze.json")
    for name, expected in freeze["file_sha256"].items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != expected:
            raise ValueError("FROZEN_CONTRACT_DRIFT")
    source = load_json(args.input)
    if (
        source["real_network"]
        or source["real_model"]
        or not source["actual_service_and_postgres"]
    ):
        raise ValueError("INVALID_VERIFICATION_SCOPE")
    standard = validator(load_json(ROOT / "contracts/agent/v0/knowledge.schema.json"))
    results = []
    for bundle in source["bundles"]:
        for record in bundle["records"]:
            standard.validate(record)
        for reference in bundle["external_refs"]:
            validate_external_reference(reference)
        results.append(
            {
                "fixture_id": bundle["fixture_id"],
                **validate_bundle(bundle, authorized_scope=bundle["authorized_scope"]),
            }
        )
    if len(results) != 4:
        raise ValueError("FOUR_ACTUAL_SERVICE_EXPORTS_REQUIRED")
    print(
        json.dumps(
            {
                "status": "passed",
                "scope": "actual service/isolated PG, synthetic source/model receipt",
                "standard_schema_and_frozen_rules": True,
                "source_sha256": hashlib.sha256(args.input.read_bytes()).hexdigest(),
                "cases": results,
                "real_network": False,
                "real_model": False,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

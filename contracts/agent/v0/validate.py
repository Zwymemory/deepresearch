#!/usr/bin/env python3
"""Total round-0 offline gate. Peer absence is incomplete, never an integrated pass."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

from validate_runtime import ROOT, load_json, validate_bundle, validate_external_reference, validator
from validate_bridge import validate_bridge


def run_tests(directory: Path):
    subprocess.run([sys.executable, "-B", "-m", "unittest", "discover", "-s", str(directory),
                    "-p", "test_*.py"], cwd=ROOT, check=True)


def load_peer(root: Path):
    path = root / "testdata/agent-foundation/evidence/validate_knowledge.py"
    spec = importlib.util.spec_from_file_location("knowledge_contract", path)
    if spec is None or spec.loader is None:
        raise ValueError("PEER_VALIDATOR_MISSING")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-only", action="store_true", help="Explicit A-only gate; not full integration")
    parser.add_argument("--peer-checkout", type=Path, default=ROOT, help="Read-only B candidate or integrated root")
    parser.add_argument("--java", action="store_true", help="Include scoped local Java tests, no live providers")
    args = parser.parse_args()
    freeze_path = ROOT / "contracts/agent/v0/freeze.json"
    freeze = load_json(freeze_path) if freeze_path.is_file() else None
    if freeze:
        for path, expected in freeze["file_sha256"].items():
            if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != expected:
                raise ValueError("CONTRACT_FREEZE_MISMATCH: " + path)
    validator(load_json(ROOT / "contracts/agent/v0/runtime.schema.json"))
    run_tests(ROOT / "testdata/agent-foundation/runtime")
    manifest = load_json(ROOT / "testdata/agent-foundation/runtime/manifest.json")
    results = []
    for case in manifest["cases"]:
        fixture = load_json(ROOT / case["path"])
        results.append({"fixture_id": fixture["fixture_id"],
                        **validate_bundle(fixture, authorized_scope=fixture["authorized_scope"])})
    subprocess.run([sys.executable, "-B", str(ROOT / "integrations/dify/verify_workflow.py")],
                   check=True, cwd=ROOT, stdout=sys.stderr)
    peer_results = []
    bridge_result = None
    if not args.runtime_only:
        peer_root = args.peer_checkout.resolve()
        knowledge_schema = peer_root / "contracts/agent/v0/knowledge.schema.json"
        if not knowledge_schema.is_file():
            raise ValueError("PEER_MISSING_INTEGRATION_INCOMPLETE")
        standard = validator(load_json(knowledge_schema))
        peer = load_peer(peer_root)
        for area in ("evidence", "memory"):
            peer_manifest = load_json(peer_root / f"testdata/agent-foundation/{area}/manifest.json")
            for case in peer_manifest["cases"]:
                fixture = load_json(peer_root / case["path"])
                for record in fixture["records"]:
                    standard.validate(record)
                for reference in fixture["external_refs"]:
                    validate_external_reference(reference)
                peer_results.append({"fixture_id": fixture["fixture_id"],
                    **peer.validate_bundle(fixture, authorized_scope=fixture["authorized_scope"])})
        run_tests(peer_root / "testdata/agent-foundation/evidence")
        bridge_result = validate_bridge(peer_root, peer)
    if args.java:
        subprocess.run(["mvn", "-q", "-Dtest=DifyContextInputsTest,DifyWorkflowAdapterTest,"
            "MemorySelectionServiceTest,AgentContextServiceTest,ConversationCompressionCoordinatorTest,"
            "ConversationSummaryServiceTest,WorkflowServiceTest,ReactAgentServiceTest,"
            "DifyCitationValidatorTest,DifyToolServiceTest,TavilySearchClientTest", "test"], check=True, cwd=ROOT)
    print(json.dumps({"status": "runtime_only_passed" if args.runtime_only else "offline_contracts_passed",
        "schema_engine": "jsonschema Draft202012Validator with registered local date-time/uri profiles; remote resolution forbidden",
        "peer_checkout": None if args.runtime_only else str(args.peer_checkout.resolve()),
        "runtime_fixtures": results, "knowledge_fixtures": peer_results, "bridge": bridge_result,
        "contract_freeze": freeze["status"] if freeze else "pending_main_review", "model_use_verified": False,
        "live_deployment": False, "semantic_verification": False}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

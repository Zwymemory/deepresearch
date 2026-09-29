"""B-only positive fixtures + actual negative tests. A owns the combined entry."""
import hashlib
import json
import sys
import unittest
from pathlib import Path

import test_knowledge_contract as tests
from validate_knowledge import ROOT, SCHEMA, validate_manifest


def run():
    tests.REJECTIONS.clear()
    suite = unittest.defaultTestLoader.loadTestsFromModule(tests)
    result = unittest.TextTestRunner(verbosity=1, stream=sys.stderr).run(suite)
    report = {"schema_version": "0.1.0", "contract_status": "proposed_awaiting_freeze",
              "validator_status": "implemented_offline_subset_and_integrity_rules",
              "python_version": sys.version.split()[0],
              "schema_sha256": hashlib.sha256(SCHEMA.read_bytes()).hexdigest(),
              "test_methods": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
              "skipped": len(result.skipped), "actually_rejected_cases": tests.REJECTIONS,
              "positive_fixtures": validate_manifest(), "semantic_verification": False,
              "runtime_execution": False, "database_or_cache_deletion_test": False,
              "full_draft2020_12_conformance": False,
              "external_registry_scope": "controlled fixture labels, not production authentication"}
    if not result.wasSuccessful():
        raise SystemExit(1)
    return report


if __name__ == "__main__":
    report = run()
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if len(sys.argv) == 2:
        Path(sys.argv[1]).write_text(text)
    else:
        print(text, end="")

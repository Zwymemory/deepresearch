"""Disposable native-completion export probe invoked only by AgentRuntimePostgresIT."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlparse

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from agent_acceptance_v22 import digest, finalize_audit, validate_saved_audit  # noqa: E402
from agent_live_common import write_private  # noqa: E402

if os.getenv("AGENT_TEST_ISOLATED") != "1":
    raise ValueError("Disposable fixture authorization required")
spec = importlib.util.spec_from_file_location(
    "native_capture_probe", SCRIPTS / "accept-agent-round1.py"
)
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)
location = urlparse(os.environ["TEST_AGENT_DATABASE_URL"])
database = live.capture_database(
    {"database_port": location.port},
    {"POSTGRES_PASSWORD": location.password},
    sys.argv[1],
)
audit = finalize_audit(live.scrub({"database": live.audit_database(database)}, []))
proof = validate_saved_audit(json.loads(json.dumps(audit, default=str)))
assert proof["status"] == "verified_mapping", proof
assert proof["mapping_complete"] and proof["eligible_for_complete_review"], proof
assert len(proof["requirements"]) == 2, proof
negative_proofs = {}
for mutation in ("request_check_id", "request_claim_text", "model_request_binding"):
    changed = copy.deepcopy(audit["database"])
    checked = changed["checks"][0]
    if mutation == "request_check_id":
        checked["request"]["check_id"] = "another-native-check"
    elif mutation == "request_claim_text":
        checked["request"]["claims"][0]["text"] = "Unrelated assertion"
    else:
        receipt = next(
            operation["safe_result"]
            for operation in changed["operations"]
            if operation.get("purpose") == "CHECK"
            and operation["safe_result"]["request_binding"]["check_id"]
            == checked["check_id"]
        )
        receipt["request_binding"]["request_sha256"] = "0" * 64
    checked["request_sha256"] = digest(checked["request"])
    rejected = validate_saved_audit(finalize_audit({"database": changed}))
    assert rejected["status"] == "invalid", (mutation, rejected)
    assert not rejected["eligible_for_complete_review"], (mutation, rejected)
    negative_proofs[mutation] = rejected["issues"]
for field in ("safe_result", "request_binding"):
    for index, malformed in enumerate((None, [], 17)):
        changed = copy.deepcopy(audit["database"])
        operation = next(
            o for o in changed["operations"] if o.get("purpose") == "CHECK"
        )
        if field == "safe_result":
            operation[field] = malformed
        else:
            operation["safe_result"][field] = malformed
        malformed_audit = finalize_audit({"database": changed})
        malformed_path = Path(sys.argv[2]).with_name(
            Path(sys.argv[2]).stem + f"-malformed-{field}-{index}.json"
        )
        if malformed_path.exists():
            raise ValueError("Malformed probe evidence cannot be overwritten")
        write_private(malformed_path, malformed_audit)
        rejected = validate_saved_audit(json.loads(malformed_path.read_text()))
        assert (
            rejected["status"] == "invalid"
            and not rejected["eligible_for_complete_review"]
        ), rejected
        assert rejected["issues"] == ["NATIVE_CHECK_MODEL_RECEIPT_MALFORMED"], rejected
        negative_proofs[f"malformed_{field}_{index}"] = rejected["issues"]
output = Path(sys.argv[2])
if output.exists():
    raise ValueError("Probe evidence cannot be overwritten")
write_private(output, audit)
print(
    json.dumps(
        {
            "status": proof["status"],
            "mapping_complete": proof["mapping_complete"],
            "eligible_for_complete_review": proof["eligible_for_complete_review"],
            "obligations": len(proof["requirements"]),
            "database_sha256": proof["database_sha256"],
            "provenance_negatives": negative_proofs,
        }
    )
)

"""Disposable native-completion export probe invoked only by AgentRuntimePostgresIT."""

import importlib.util
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlparse

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from agent_acceptance_v22 import finalize_audit, validate_saved_audit  # noqa: E402
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
        }
    )
)

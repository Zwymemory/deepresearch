#!/usr/bin/env python3
"""Launch the real sidecar from a verified archive, with no model or tool fixtures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys


def source_digest(source):
    rows = []
    for path in sorted(source.rglob("*.py")):
        rows.append([str(path.relative_to(source)), hashlib.sha256(path.read_bytes()).hexdigest()])
    return hashlib.sha256(json.dumps(rows, separators=(",", ":")).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--identity-output", type=Path, required=True)
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--port", type=int, default=18090)
    args = parser.parse_args()
    source = args.source_dir.resolve()
    if source_digest(source) != args.source_sha256:
        raise SystemExit("Sidecar source archive differs from the verified build")
    sys.path.insert(0, str(source))
    from deepresearch_workflow import app as module
    from deepresearch_workflow.agent_model import OpenAIAgentModel
    import uvicorn

    if not Path(module.__file__).resolve().is_relative_to(source):
        raise SystemExit("Sidecar imported a different source tree")
    identity = {"pid": os.getpid(), "source_dir": str(source),
                "source_sha256": source_digest(source), "model_class": OpenAIAgentModel.__name__,
                "module_path": str(Path(module.__file__).resolve()), "fixtures": False}
    descriptor = os.open(args.identity_output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(identity, stream, indent=2)
    uvicorn.run(module.app, host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()

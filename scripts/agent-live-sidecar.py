#!/usr/bin/env python3
"""Launch the real sidecar from a verified archive, with no model or tool fixtures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import time


def install_model_audit(httpx_module, path, expected):
    """Observe only existing paid calls; save names/status, never request/response prose."""
    from agent_live_common import read_private, write_private
    original = httpx_module.AsyncClient.send
    write_private(path, {"receipts": []})

    async def observed(client, request, **kwargs):
        if str(request.url) != "https://api.deepseek.com/chat/completions":
            return await original(client, request, **kwargs)
        started = time.time()
        requested = json.loads(request.content).get("model")
        receipt = {"started_at_epoch": started, "request_model_matches": requested == expected,
                   "provider_model": None, "identity_matches": None, "http_status": None}
        try:
            response = await original(client, request, **kwargs)
            receipt["http_status"] = response.status_code
            try:
                value = response.json()
                name = value.get("model") if type(value) is dict else None
                if name is not None:
                    receipt["provider_model"] = expected if name == expected else "UNRECOGNIZED"
                    receipt["identity_matches"] = name == expected
            except Exception:
                pass
            if response.status_code < 400 and (not receipt["request_model_matches"] or receipt["identity_matches"] is not True):
                raise RuntimeError("MODEL_IDENTITY_UNAVAILABLE_OR_MISMATCH")
            return response
        finally:
            audit = read_private(path)
            audit["receipts"].append(receipt)
            write_private(path, audit)
    httpx_module.AsyncClient.send = observed
    return original


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
    from deepresearch_workflow.settings import Settings
    import uvicorn

    if not Path(module.__file__).resolve().is_relative_to(source):
        raise SystemExit("Sidecar imported a different source tree")
    settings = Settings()
    if settings.model_provider != "openai" or settings.model_name != "deepseek-v4-flash" or settings.openai_base_url != "https://api.deepseek.com":
        raise SystemExit("Runtime research model differs from the authorized configuration")
    import httpx
    model_audit = args.identity_output.with_name(args.identity_output.stem + "-model-calls.json")
    install_model_audit(httpx, model_audit, settings.model_name)
    identity = {"pid": os.getpid(), "source_dir": str(source),
                "source_sha256": source_digest(source), "model_class": OpenAIAgentModel.__name__,
                "module_path": str(Path(module.__file__).resolve()), "fixtures": False,
                "model_identity_receipts_path": str(model_audit),
                "model_identity": {"provider": "deepseek-openai-compatible", "name": settings.model_name,
                                   "adapter": OpenAIAgentModel.__name__, "endpoint": settings.openai_base_url}}
    descriptor = os.open(args.identity_output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(identity, stream, indent=2)
    uvicorn.run(module.app, host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()

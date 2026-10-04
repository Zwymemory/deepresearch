#!/usr/bin/env python3
"""Launch the real sidecar from a verified archive, with no model or tool fixtures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import time
from urllib.parse import urlsplit


def install_model_audit(httpx_module, path, expected, expected_result_transport=None):
    """Observe existing completion calls under a versioned, exact provider policy."""
    from agent_live_common import read_private, write_private
    from deepresearch_workflow.agent_identity import (
        ENDPOINT, EVIDENCE_SOURCES, MAX_REQUEST_BYTES, MAX_RESPONSE_BYTES, MISSING,
        REQUEST_MODELS, ModelIdentityRejected, decode_object, identity_diagnostic,
        measured_usage,
    )
    from deepresearch_workflow.agent_json import JsonDecodeFailure, at_stage
    if type(expected) is not str or expected not in REQUEST_MODELS:
        raise ValueError("Unsupported configured research request model")
    if expected_result_transport not in {None, "function_call", "deepseek_json_object"}:
        raise ValueError("Unsupported configured result transport")
    original = httpx_module.AsyncClient.send
    write_private(path, {"receipts": []})

    async def observed(client, request, **kwargs):
        endpoint = str(request.url)
        marked = getattr(request, "extensions", {}).get("deepresearch_agent_model") is True
        if not marked and not urlsplit(endpoint).path.endswith("/chat/completions"):
            return await original(client, request, **kwargs)
        started = time.time()
        requested, request_reason, request_data = MISSING, None, None
        json_metadata = None
        try:
            request_data = decode_object(request.content, MAX_REQUEST_BYTES)
            requested = request_data.get("model", MISSING) if type(request_data) is dict else MISSING
        except ValueError as error:
            if isinstance(error, JsonDecodeFailure):
                json_metadata = at_stage(error.diagnostic, "identity_request")
            request_reason = "request_oversized" if str(error) == "oversized" else "request_json_invalid"
        if endpoint != ENDPOINT:
            request_reason = "endpoint_mismatch"
        elif request.method != "POST":
            request_reason = "request_method_mismatch"
        elif request_reason is None:
            if type(requested) is not str or requested != expected:
                request_reason = "request_model_mismatch"
            elif requested not in REQUEST_MODELS:
                request_reason = "request_model_unsupported"
        transport = "unrecognized"
        if type(request_data) is dict:
            if (request_data.get("response_format") == {"type": "json_object"}
                    and "tools" not in request_data and "tool_choice" not in request_data):
                transport = "deepseek_json_object"
            elif ("response_format" not in request_data and type(request_data.get("tools")) is list
                  and type(request_data.get("tool_choice")) is dict):
                transport = "function_call"
        transport_matches = expected_result_transport is None or transport == expected_result_transport
        if not transport_matches and request_reason is None:
            request_reason = "request_json_invalid"
        receipt = {"started_at_epoch": started,
                   "wire": {"sha256": hashlib.sha256(request.content).hexdigest(),
                            "bytes": len(request.content), "result_transport": transport,
                            "configured_result_transport": expected_result_transport,
                            "transport_matches": transport_matches}, "request_model_matches": requested == expected,
                   "provider_model": None, "identity_matches": None, "http_status": None,
                   "identity": identity_diagnostic(endpoint, requested, expected, {}),
                   "policy_evidence": list(EVIDENCE_SOURCES)}
        try:
            if request_reason:
                diagnostic = identity_diagnostic(endpoint, requested, expected, {}, forced_reason=request_reason)
                receipt.update(identity=diagnostic, identity_matches=False, policy_evidence=list(EVIDENCE_SOURCES))
                if json_metadata is not None:
                    receipt["json_diagnostic"] = json_metadata
                raise ModelIdentityRejected(diagnostic, json_diagnostic=json_metadata)
            response = await original(client, request, **kwargs)
            receipt["http_status"] = response.status_code
            data, response_reason = None, None
            try:
                data = decode_object(response.content, MAX_RESPONSE_BYTES)
            except ValueError as error:
                if isinstance(error, JsonDecodeFailure):
                    json_metadata = at_stage(error.diagnostic, "identity_response")
                response_reason = "response_oversized" if str(error) == "oversized" else "response_json_invalid"
            except Exception:
                response_reason = "response_json_invalid"
            if json_metadata is not None:
                receipt["json_diagnostic"] = json_metadata
            effective_matches = str(response.url) == ENDPOINT
            if not effective_matches:
                response_reason = "endpoint_mismatch"
            elif 300 <= response.status_code < 400:
                response_reason = "response_redirect"
            diagnostic = identity_diagnostic(endpoint, requested, expected, data, forced_reason=response_reason)
            diagnostic["response_endpoint_matches"] = effective_matches
            input_tokens, output_tokens = measured_usage(data)
            receipt.update(identity=diagnostic, policy_evidence=list(EVIDENCE_SOURCES),
                           usage={key: value for key, value in {
                               "input_tokens": input_tokens, "output_tokens": output_tokens,
                           }.items() if value is not None})
            receipt["provider_model"] = diagnostic["response_model_identifier"]
            if receipt["provider_model"] is None and diagnostic["response_model_present"]:
                receipt["provider_model"] = "UNRECOGNIZED"
            if response.status_code < 400:
                receipt["identity_matches"] = diagnostic["decision"] == "accept"
                if not receipt["identity_matches"]:
                    raise ModelIdentityRejected(diagnostic, status_code=response.status_code,
                                                input_tokens=input_tokens, output_tokens=output_tokens,
                                                json_diagnostic=json_metadata)
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
    from deepresearch_workflow.agent_model import OpenAIAgentModel, TRANSPORT_CONTRACT_VERSION
    from deepresearch_workflow.settings import Settings
    import uvicorn

    if not Path(module.__file__).resolve().is_relative_to(source):
        raise SystemExit("Sidecar imported a different source tree")
    settings = Settings()
    from deepresearch_workflow.agent_identity import POLICY_VERSION, REQUEST_MODELS
    if settings.model_provider != "openai" or settings.model_name not in REQUEST_MODELS or settings.openai_base_url != "https://api.deepseek.com":
        raise SystemExit("Runtime research model differs from the authorized configuration")
    if not settings.agent_transport_supported():
        raise SystemExit("Unsupported Agent result transport capability")
    import httpx
    model_audit = args.identity_output.with_name(args.identity_output.stem + "-model-calls.json")
    install_model_audit(httpx, model_audit, settings.model_name, settings.agent_result_transport)
    identity = {"pid": os.getpid(), "source_dir": str(source),
                "source_sha256": source_digest(source), "model_class": OpenAIAgentModel.__name__,
                "module_path": str(Path(module.__file__).resolve()), "fixtures": False,
                "model_identity_receipts_path": str(model_audit),
                "model_identity": {"provider": "deepseek-openai-compatible", "name": settings.model_name,
                                   "adapter": OpenAIAgentModel.__name__, "endpoint": settings.openai_base_url,
                                   "policy_version": POLICY_VERSION,
                                   "result_transport": settings.agent_result_transport,
                                   "transport_contract_version": TRANSPORT_CONTRACT_VERSION}}
    from deepresearch_workflow.agent_question_segments import (
        PLANNER_VERSION, MAPPING_VERSION, SETTLEMENT_VERSION,
    )
    identity["planner_identity"] = {
        "planner_contract": PLANNER_VERSION, "question_mapping_version": MAPPING_VERSION,
        "planner_settlement_contract": SETTLEMENT_VERSION,
    }
    descriptor = os.open(args.identity_output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(identity, stream, indent=2)
    uvicorn.run(module.app, host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()

"""Explicit DeepSeek request/response identity policy; no network or raw diagnostics."""

from __future__ import annotations

import hashlib
import json

POLICY_VERSION = "deepseek-flash-2026-09-10/1"
ENDPOINT = "https://api.deepseek.com/chat/completions"
CANONICAL_MODEL = "deepseek-flash"
REQUEST_MODELS = frozenset({CANONICAL_MODEL, "deepseek-v4-flash"})
KNOWN_IDENTIFIERS = REQUEST_MODELS | {"deepseek-v4-pro", "deepseek-v4-flash-vision-exp"}
EVIDENCE_SOURCES = (
    "https://api-docs.deepseek.com/updates/#date-2026-09-10",
    "https://api-docs.deepseek.com/api/create-chat-completion/",
)
MAX_REQUEST_BYTES = 131072
MAX_RESPONSE_BYTES = 524288
MAX_IDENTIFIER_LENGTH = 128
MISSING = object()
REASONS = frozenset(
    {
        "accepted_canonical",
        "accepted_legacy_route",
        "endpoint_mismatch",
        "request_model_mismatch",
        "request_model_unsupported",
        "request_method_mismatch",
        "request_json_invalid",
        "request_oversized",
        "response_oversized",
        "response_json_invalid",
        "response_shape",
        "response_model_missing",
        "response_model_type_invalid",
        "response_model_oversized",
        "response_model_unsafe",
        "response_model_mismatch",
        "response_model_unrecognized",
        "response_redirect",
    }
)
JSON_TYPES = frozenset({"missing", "null", "string", "boolean", "number", "array", "object"})


def measured_token(value):
    return value if type(value) is int and 0 <= value <= 2**63 - 1 else None


def measured_usage(data):
    usage = data.get("usage") if type(data) is dict else None
    usage = usage if type(usage) is dict else {}
    # Totals already include cache/reasoning tokens. Never add nested detail counts.
    return (
        measured_token(usage.get("prompt_tokens")),
        measured_token(usage.get("completion_tokens")),
    )


def decode_object(raw, maximum):
    """Reject oversized, duplicate-key and nonfinite JSON before policy evaluation."""
    if type(raw) is not bytes or len(raw) > maximum:
        raise ValueError("oversized")

    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("duplicate key")
            value[key] = item
        return value

    def nonfinite(_):
        raise ValueError("nonfinite")

    try:
        return json.loads(raw, object_pairs_hook=unique, parse_constant=nonfinite)
    except Exception:
        raise ValueError("invalid") from None


def describe(value):
    kind = (
        "missing"
        if value is MISSING
        else "null"
        if value is None
        else "string"
        if type(value) is str
        else "boolean"
        if type(value) is bool
        else "number"
        if type(value) in {int, float}
        else "array"
        if type(value) is list
        else "object"
    )
    metadata = {"present": value is not MISSING, "type": kind, "identifier": None}
    if type(value) is str:
        metadata.update(
            length=len(value),
            sha256=hashlib.sha256(value.encode("utf-8", errors="surrogatepass")).hexdigest(),
        )
        if value in KNOWN_IDENTIFIERS:
            metadata["identifier"] = value
    return metadata


def identity_diagnostic(endpoint, requested, expected, data, *, forced_reason=None):
    response_model = data.get("model", MISSING) if type(data) is dict else MISSING
    request_matches = type(requested) is str and requested == expected
    if forced_reason is not None:
        reason = forced_reason
    elif endpoint != ENDPOINT:
        reason = "endpoint_mismatch"
    elif not request_matches:
        reason = "request_model_mismatch"
    elif requested not in REQUEST_MODELS:
        reason = "request_model_unsupported"
    elif type(data) is not dict:
        reason = "response_shape"
    elif response_model is MISSING:
        reason = "response_model_missing"
    elif type(response_model) is not str:
        reason = "response_model_type_invalid"
    elif len(response_model) > MAX_IDENTIFIER_LENGTH:
        reason = "response_model_oversized"
    elif any(ord(c) < 32 or ord(c) > 126 for c in response_model):
        reason = "response_model_unsafe"
    elif response_model == CANONICAL_MODEL:
        reason = "accepted_canonical" if requested == CANONICAL_MODEL else "accepted_legacy_route"
    elif response_model in KNOWN_IDENTIFIERS:
        reason = "response_model_mismatch"
    else:
        reason = "response_model_unrecognized"
    if reason not in REASONS:
        raise ValueError("unknown identity policy reason")
    result = {
        "policy_version": POLICY_VERSION,
        "decision": "accept" if reason.startswith("accepted_") else "reject",
        "reason": reason,
        "endpoint_matches": endpoint == ENDPOINT,
        "request_model_matches": request_matches,
        "request_model_kind": "canonical"
        if requested == CANONICAL_MODEL
        else "retired_alias"
        if type(requested) is str and requested in REQUEST_MODELS
        else "unsupported",
    }
    for prefix, value in (("requested_model", requested), ("response_model", response_model)):
        result.update({prefix + "_" + key: item for key, item in describe(value).items()})
    return result


def safe_identity_diagnostic(value):
    """Finite allowlist for persisted failure metadata, including mocked exceptions."""
    if (
        type(value) is not dict
        or value.get("policy_version") != POLICY_VERSION
        or type(value.get("reason")) is not str
        or value["reason"] not in REASONS
        or value.get("decision")
        != ("accept" if value["reason"].startswith("accepted_") else "reject")
    ):
        return None
    safe = {key: value[key] for key in ("policy_version", "decision", "reason")}
    for key in ("endpoint_matches", "response_endpoint_matches", "request_model_matches"):
        if type(value.get(key)) is bool:
            safe[key] = value[key]
    if type(value.get("request_model_kind")) is str and value["request_model_kind"] in {
        "canonical",
        "retired_alias",
        "unsupported",
    }:
        safe["request_model_kind"] = value["request_model_kind"]
    for prefix in ("requested_model", "response_model"):
        if type(value.get(prefix + "_present")) is bool:
            safe[prefix + "_present"] = value[prefix + "_present"]
        if type(value.get(prefix + "_type")) is str and value[prefix + "_type"] in JSON_TYPES:
            safe[prefix + "_type"] = value[prefix + "_type"]
        identifier = value.get(prefix + "_identifier")
        if identifier is None or (type(identifier) is str and identifier in KNOWN_IDENTIFIERS):
            safe[prefix + "_identifier"] = identifier
        length = value.get(prefix + "_length")
        if type(length) is int and 0 <= length <= MAX_RESPONSE_BYTES:
            safe[prefix + "_length"] = length
        digest = value.get(prefix + "_sha256")
        if (
            type(digest) is str
            and len(digest) == 64
            and all(c in "0123456789abcdef" for c in digest)
        ):
            safe[prefix + "_sha256"] = digest
    return safe


class ModelIdentityRejected(RuntimeError):
    """Contains only bounded safe policy metadata and measured usage, never body text."""

    def __init__(self, diagnostic, *, status_code=None, input_tokens=None, output_tokens=None):
        super().__init__("MODEL_IDENTITY_INVALID")
        self.diagnostic = safe_identity_diagnostic(diagnostic)
        self.status_code = status_code
        self.input_tokens = measured_token(input_tokens)
        self.output_tokens = measured_token(output_tokens)

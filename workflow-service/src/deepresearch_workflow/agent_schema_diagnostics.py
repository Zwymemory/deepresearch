"""Content-free schema failure vocabulary and strict audit consumer."""

import hashlib
import json
import re

VERSION = "agent-schema-diagnostic/1"
CODES = frozenset(
    {"MISSING", "TYPE", "ENUM", "CONST", "EXTRA_FIELD", "BOUND", "FORMAT", "COMPOSITION", "UNKNOWN"}
)
JSON_CODES = {
    "required": "MISSING",
    "type": "TYPE",
    "enum": "ENUM",
    "const": "CONST",
    "additionalProperties": "EXTRA_FIELD",
    "minLength": "BOUND",
    "maxLength": "BOUND",
    "minItems": "BOUND",
    "maxItems": "BOUND",
    "minimum": "BOUND",
    "maximum": "BOUND",
    "pattern": "FORMAT",
    "format": "FORMAT",
    "anyOf": "COMPOSITION",
    "oneOf": "COMPOSITION",
}
PYDANTIC_CODES = {
    "missing": "MISSING",
    "literal_error": "ENUM",
    "extra_forbidden": "EXTRA_FIELD",
    "string_type": "TYPE",
    "int_type": "TYPE",
    "float_type": "TYPE",
    "bool_type": "TYPE",
    "list_type": "TYPE",
    "dict_type": "TYPE",
    "model_type": "TYPE",
    "none_required": "TYPE",
    "string_too_short": "BOUND",
    "string_too_long": "BOUND",
    "too_short": "BOUND",
    "too_long": "BOUND",
    "greater_than_equal": "BOUND",
    "less_than_equal": "BOUND",
    "string_pattern_mismatch": "FORMAT",
    "union_tag_invalid": "COMPOSITION",
    "union_tag_not_found": "COMPOSITION",
}


def digest(value):
    return hashlib.sha256(
        json.dumps(
            value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False
        ).encode()
    ).hexdigest()


def path(parts, fields):
    names = [p for p in parts if type(p) is str and p in fields]
    return ".".join(names[:4]) or "unknown_field"


def diagnostic(error, request, request_hash, fields, *, pydantic=False):
    if pydantic:
        issues = [
            {
                "path": path(i.get("loc", ()), fields),
                "code": PYDANTIC_CODES.get(i.get("type"), "UNKNOWN"),
            }
            for i in error.errors(include_input=False, include_context=False, include_url=False)[:4]
        ]
    else:
        parts = list(error.absolute_path)
        # Do not inspect exception.message: required names there may contain arbitrary input.
        missing = []
        if (
            error.validator == "required"
            and type(error.validator_value) is list
            and type(error.instance) is dict
        ):
            missing = [
                n
                for n in error.validator_value
                if type(n) is str and n in fields and n not in error.instance
            ][:4]
        issues = [{"path": path([*parts, n], fields), "code": "MISSING"} for n in missing]
        if not issues:
            issues = [
                {"path": path(parts, fields), "code": JSON_CODES.get(error.validator, "UNKNOWN")}
            ]
    return {
        "version": VERSION,
        "schema_sha256": digest(request.result_schema),
        "instruction_sha256": hashlib.sha256(request.instruction.encode()).hexdigest(),
        "request_sha256": request_hash,
        "issues": issues,
    }


def safe_schema_diagnostic(value, fields):
    required = {"version", "schema_sha256", "instruction_sha256", "request_sha256", "issues"}
    if type(value) is not dict or set(value) != required or value.get("version") != VERSION:
        return None
    for key in ("schema_sha256", "instruction_sha256", "request_sha256"):
        if type(value[key]) is not str or re.fullmatch(r"[a-f0-9]{64}", value[key]) is None:
            return None
    issues = value["issues"]
    if type(issues) is not list or not 1 <= len(issues) <= 4:
        return None
    for issue in issues:
        if type(issue) is not dict or set(issue) != {"path", "code"}:
            return None
        if type(issue["code"]) is not str or issue["code"] not in CODES:
            return None
        p = issue["path"]
        if type(p) is not str or (
            p != "unknown_field"
            and not (1 <= len(p.split(".")) <= 4 and all(n in fields for n in p.split(".")))
        ):
            return None
    return {**value, "issues": [dict(i) for i in issues]}

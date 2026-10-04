"""Standalone safe schema diagnostics; parity checked against workflow consumer."""

import re

VERSION = "agent-schema-diagnostic/1"
CODES = frozenset({"MISSING", "TYPE", "ENUM", "CONST", "EXTRA_FIELD", "BOUND", "FORMAT",
                   "COMPOSITION", "UNKNOWN"})


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
        if type(p) is not str or (p != "unknown_field" and not (
                1 <= len(p.split(".")) <= 4 and all(n in fields for n in p.split(".")))):
            return None
    return {**value, "issues": [dict(i) for i in issues]}

"""Standalone audit allowlist; parity checked against the committed workflow helper."""

from __future__ import annotations


VERSION = "agent-json-diagnostic/1"
STAGES = frozenset({"decode", "response_envelope", "result_content", "function_arguments",
                    "identity_request", "identity_response"})
CATEGORIES = frozenset({"empty_content", "content_encoding", "byte_encoding", "syntax",
                        "duplicate_key",
                        "nonfinite_constant", "nonfinite_float", "decoded_unicode",
                        "top_level_shape", "depth_limit", "byte_limit", "input_type",
                        "integer_limit", "memory_limit", "decoder_unknown"})
DECODER_MESSAGES = {
    "Expecting value": "expected_value",
    "Expecting property name enclosed in double quotes": "expected_property_name",
    "Expecting ':' delimiter": "expected_colon",
    "Expecting ',' delimiter": "expected_comma",
    "Extra data": "extra_data",
    "Unterminated string starting at": "unterminated_string",
    "Invalid \\escape": "invalid_escape",
    "Invalid \\uXXXX escape": "invalid_unicode_escape",
    "Invalid control character at": "invalid_control_character",
}
DECODER_CODES = frozenset({*DECODER_MESSAGES.values(), "unknown"})
CHARACTERS = frozenset({"end", "object_open", "object_close", "array_open", "array_close",
                        "quote", "colon", "comma", "backslash", "whitespace", "backtick",
                        "number", "letter", "other"})
HINTS = frozenset({"markdown_fence", "trailing_comma", "unclosed_string", "unclosed_container",
                  "trailing_data", "missing_colon", "missing_comma", "missing_value",
                  "bad_escape", "control_character", "unknown"})
FINISH_REASONS = frozenset({"stop", "length", "tool_calls", "function_call", "content_filter"})
MAX_NUMBER = 2**63 - 1


def safe_json_diagnostic(value):
    """Reject unknown fields/types/enums and inconsistent coordinates as a whole."""
    required = {"version", "stage", "category", "representation"}
    optional = {"byte_length", "character_length", "sha256", "offset", "offset_unit",
                "line", "column", "decoder_code", "character_class", "previous_character_class",
                "structural_hint", "finish_reason", "top_level_type"}
    if (type(value) is not dict or not required <= value.keys()
            or value.keys() - required - optional):
        return None
    enums = {"version": {VERSION}, "stage": STAGES, "category": CATEGORIES,
             "representation": {"exact_bytes", "text_utf8_surrogatepass", "unavailable"},
             "offset_unit": {"byte", "codepoint"}, "decoder_code": DECODER_CODES,
             "character_class": CHARACTERS, "previous_character_class": CHARACTERS,
             "structural_hint": HINTS, "finish_reason": FINISH_REASONS,
             "top_level_type": {"array", "string", "number", "boolean", "null"}}
    for key, allowed in enums.items():
        if key in value and (type(value[key]) is not str or value[key] not in allowed):
            return None
    for key in {"byte_length", "character_length", "offset", "line", "column"}:
        if key in value and (type(value[key]) is not int
                             or not (1 if key in {"line", "column"} else 0)
                             <= value[key] <= MAX_NUMBER):
            return None
    if value["representation"] == "unavailable":
        if value.keys() - required - {"finish_reason"} or value["category"] != "input_type":
            return None
    elif not {"byte_length", "sha256"} <= value.keys():
        return None
    if "sha256" in value:
        digest = value["sha256"]
        if (type(digest) is not str or len(digest) != 64
                or any(c not in "0123456789abcdef" for c in digest)):
            return None
    if ("character_length" in value and value["character_length"] > value["byte_length"]):
        return None
    if value["category"] == "input_type" and value["representation"] != "unavailable":
        return None
    if ("offset" in value) != ("offset_unit" in value):
        return None
    if "offset" in value:
        size = "byte_length" if value["offset_unit"] == "byte" else "character_length"
        if size not in value or value["offset"] > value[size]:
            return None
    if ("line" in value) != ("column" in value):
        return None
    if "line" in value and (value.get("offset_unit") != "codepoint"
                            or max(value["line"], value["column"]) > value["offset"] + 1):
        return None
    syntax_keys = {"decoder_code", "character_class", "previous_character_class", "structural_hint"}
    if value["category"] == "syntax" and not (syntax_keys | {
            "character_length", "offset", "offset_unit", "line", "column"}) <= value.keys():
        return None
    if value["category"] != "syntax" and syntax_keys & value.keys():
        return None
    if "top_level_type" in value and value["category"] != "top_level_shape":
        return None
    return dict(value)

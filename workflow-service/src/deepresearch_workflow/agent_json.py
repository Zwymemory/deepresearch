"""Strict JSON observability: fixed enums, coordinates and hashes, never source text."""

from __future__ import annotations

import hashlib
import json

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


def diagnostic(raw, category, *, stage="decode", **fields):
    value = {"version": VERSION, "stage": stage, "category": category}
    if type(raw) is bytes:
        value.update(representation="exact_bytes", byte_length=len(raw),
                     sha256=hashlib.sha256(raw).hexdigest())
    elif type(raw) is str:
        # Declared hash domain even for unpaired surrogates; never lossy replacement.
        encoded = raw.encode("utf-8", errors="surrogatepass")
        value.update(representation="text_utf8_surrogatepass", byte_length=len(encoded),
                     character_length=len(raw), sha256=hashlib.sha256(encoded).hexdigest())
    else:
        value.update(representation="unavailable")
    value.update(fields)
    return safe_json_diagnostic(value)


class JsonDecodeFailure(ValueError):
    """Legacy callers still see only 'oversized'/'invalid', plus safe typed metadata."""

    def __init__(self, metadata):
        self.diagnostic = safe_json_diagnostic(metadata)
        category = self.diagnostic.get("category") if self.diagnostic else None
        super().__init__("oversized" if category in {"byte_limit", "input_type"} else "invalid")


def character_class(character):
    if character is None:
        return "end"
    tokens = {"{": "object_open", "}": "object_close", "[": "array_open", "]": "array_close",
              '"': "quote", ":": "colon", ",": "comma", "\\": "backslash", "`": "backtick"}
    if character in tokens:
        return tokens[character]
    if character in " \t\r\n":
        return "whitespace"
    if character in "0123456789-+.":
        return "number"
    return "letter" if character.isalpha() else "other"


def syntax_diagnostic(raw, error):
    # error.msg is mapped locally, never exported. error.doc/snippets/keys are discarded.
    code = DECODER_MESSAGES.get(error.msg, "unknown")
    text, position = error.doc, error.pos
    current = character_class(text[position] if position < len(text) else None)
    previous = character_class(next((c for c in reversed(text[:position]) if c not in " \t\r\n"),
                                    None))
    hint = {"invalid_escape": "bad_escape", "invalid_unicode_escape": "bad_escape",
            "invalid_control_character": "control_character", "extra_data": "trailing_data",
            "unterminated_string": "unclosed_string", "expected_colon": "missing_colon",
            "expected_comma": "missing_comma", "expected_value": "missing_value"
            }.get(code, "unknown")
    if text.lstrip(" \t\r\n").startswith("```"):
        hint = "markdown_fence"
    elif previous == "comma" and current in {"object_close", "array_close"}:
        hint = "trailing_comma"
    elif position == len(text) and code in {
            "expected_comma", "expected_property_name", "expected_value"}:
        # Structural observation at EOF, not semantic diagnosis or JSON salvage.
        hint = "unclosed_container" if text.lstrip(" \t\r\n").startswith(("{", "[")) else hint
    return diagnostic(raw, "syntax", character_length=len(text), offset=position,
                      offset_unit="codepoint", line=error.lineno, column=error.colno,
                      decoder_code=code, character_class=current, previous_character_class=previous,
                      structural_hint=hint)


def decode_json(raw, maximum):
    """Keep json.loads(bytes) encoding/Unicode/float behavior; classify existing rejections."""
    if type(raw) is not bytes:
        raise JsonDecodeFailure(diagnostic(None, "input_type")) from None
    if len(raw) > maximum:
        raise JsonDecodeFailure(diagnostic(raw, "byte_limit")) from None

    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise JsonDecodeFailure(diagnostic(raw, "duplicate_key"))
            value[key] = item
        return value

    def nonfinite(_):
        raise JsonDecodeFailure(diagnostic(raw, "nonfinite_constant"))

    def integer(token):
        try:
            return int(token)
        except ValueError:
            raise JsonDecodeFailure(diagnostic(raw, "integer_limit")) from None

    try:
        return json.loads(
            raw, object_pairs_hook=unique, parse_constant=nonfinite, parse_int=integer
        )
    except JsonDecodeFailure:
        raise
    except json.JSONDecodeError as error:
        raise JsonDecodeFailure(syntax_diagnostic(raw, error)) from None
    except UnicodeError as error:
        coordinates = {}
        # UTF8 BOM decoding may slice the buffer. Bind the numeric position to raw hash bytes.
        if type(error.object) is bytes and raw.endswith(error.object):
            coordinates = {"offset": error.start + len(raw) - len(error.object),
                           "offset_unit": "byte"}
        raise JsonDecodeFailure(diagnostic(raw, "byte_encoding", **coordinates)) from None
    except RecursionError:
        raise JsonDecodeFailure(diagnostic(raw, "depth_limit")) from None
    except MemoryError:
        raise JsonDecodeFailure(diagnostic(raw, "memory_limit")) from None
    except Exception:
        raise JsonDecodeFailure(diagnostic(raw, "decoder_unknown")) from None


def at_stage(value, stage, finish_reason=None):
    safe = safe_json_diagnostic(value)
    if safe is None:
        return None
    safe["stage"] = stage
    if type(finish_reason) is str and finish_reason in FINISH_REASONS:
        safe["finish_reason"] = finish_reason
    return safe_json_diagnostic(safe)

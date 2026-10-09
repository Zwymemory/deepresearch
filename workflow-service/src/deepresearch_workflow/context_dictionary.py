"""Lossless model-only sharing. No truncation, semantic rewriting or new authority."""

from collections import Counter
from copy import deepcopy

from .agent_budget import canonical

ENCODING = "shared-context-values/1"


def pack(payload):
    counts = Counter()

    def count(value):
        counts[canonical(value)] += 1
        if isinstance(value, dict):
            for child in value.values():
                count(child)
        elif isinstance(value, list):
            for child in value:
                count(child)

    count(payload)
    keys = [key for key, n in sorted(counts.items())
            if n > 1 and (n - 1) * len(key.encode()) > n * 24 + 16]
    refs = {key: "v" + str(i) for i, key in enumerate(keys)}
    dictionary = {}

    def encode(value, literal=False):
        key = canonical(value)
        if not literal and key in refs:
            ref = refs[key]
            if ref not in dictionary:
                dictionary[ref] = encode(value, literal=True)
            return {"shared_ref": ref}
        if isinstance(value, dict):
            result = {k: encode(v) for k, v in sorted(value.items())}
            if set(value) in ({"shared_ref"}, {"shared_literal"}):
                return {"shared_literal": result}
            return result
        if isinstance(value, list):
            return [encode(v) for v in value]
        return deepcopy(value)

    # Root fields remain directly available for memory/request bindings.
    result = {k: encode(v, literal=True) for k, v in sorted(payload.items())}
    result["context_encoding"] = ENCODING
    result["shared_context_values"] = dictionary
    if unpack(result) != payload:
        raise ValueError("context dictionary is not reversible")
    return result


def unpack(payload):
    dictionary = payload["shared_context_values"]

    def decode(value, active=()):
        if isinstance(value, dict):
            if set(value) == {"shared_ref"}:
                ref = value["shared_ref"]
                if ref in active or ref not in dictionary:
                    raise ValueError("invalid context dictionary reference")
                return decode(dictionary[ref], (*active, ref))
            if set(value) == {"shared_literal"}:
                return {k: decode(v, active) for k, v in value["shared_literal"].items()}
            return {k: decode(v, active) for k, v in value.items()}
        if isinstance(value, list):
            return [decode(v, active) for v in value]
        return deepcopy(value)

    return {k: decode(v) for k, v in payload.items()
            if k not in {"context_encoding", "shared_context_values"}}

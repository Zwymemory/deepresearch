"""Read a simple dotenv file as data; never source or execute private contents."""

from __future__ import annotations

import os
import re
from pathlib import Path


def load_env(path: Path) -> dict[str, str]:
    values = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key = key.strip()
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError(f"Invalid dotenv assignment at line {number}")
        if key in values:
            raise ValueError(f"Duplicate dotenv setting: {key}")
        value = value.strip()
        if value[:1] in ("'", '"'):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f"Unclosed dotenv quote at line {number}")
            value = value[1:-1]
        if "$" in value or "`" in value:
            raise ValueError(f"Use a literal dotenv value for {key}; expansion is unsupported")
        values[key] = value
    # Match Compose: explicit shell environment overrides the dotenv file.
    return {**values, **os.environ}

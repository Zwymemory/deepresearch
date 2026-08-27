#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAX_DOCS="${OPEN_SCIFACT_DOCS:-300}"
MAX_QUERIES="${OPEN_SCIFACT_QUERIES:-50}"

python3 "$ROOT_DIR/scripts/prepare-open-scifact.py" \
  --root "$ROOT_DIR" \
  --max-docs "$MAX_DOCS" \
  --max-queries "$MAX_QUERIES"

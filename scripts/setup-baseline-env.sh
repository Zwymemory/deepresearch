#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"

"$PYTHON_BIN" -m venv "$ROOT_DIR/.venv-baseline"
"$ROOT_DIR/.venv-baseline/bin/python" -m pip install -U pip
"$ROOT_DIR/.venv-baseline/bin/pip" install numpy sentence-transformers

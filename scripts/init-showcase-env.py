#!/usr/bin/env python3
"""Create a private local demo dotenv file without replacing existing settings."""

import argparse
import os
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SECRETS = {"DEEPRESEARCH_JWT_SECRET", "DEEPRESEARCH_INTERNAL_JWT_SECRET",
           "DEEPRESEARCH_MCP_JWT_SECRET", "WORKFLOW_DB_PASSWORD",
           "DEEPRESEARCH_WORKFLOW_DIFY_TOOL_SERVICE_TOKEN"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    args = parser.parse_args()
    lines = []
    for line in (ROOT / "docs/showcase/.env.example").read_text(encoding="utf-8").splitlines():
        key = line.partition("=")[0]
        lines.append(key + "=" + secrets.token_hex(32) if key in SECRETS else line)
    try:
        fd = os.open(args.env_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            output.write("\n".join(lines) + "\n")
    except FileExistsError:
        parser.exit(1, "Configuration file already exists; preserve it and edit the missing settings.\n")
    except OSError:
        parser.exit(1, "Cannot create configuration file; check the destination directory.\n")
    print("Created private dotenv file with five distinct local secrets. Values are hidden.")
    print("Set DEEPSEEK_API_KEY, RAGFLOW_API_KEY, RAGFLOW_DATASET_IDS and DEEPRESEARCH_DIFY_APP_KEY before starting.")


if __name__ == "__main__":
    main()

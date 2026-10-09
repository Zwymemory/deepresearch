#!/usr/bin/env bash
# 本机记忆版：启动、查看状态或停止；停止保留数据库。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIR/local_services.py" "$@"

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env}"
if [[ $# -gt 1 || "$ENV_FILE" == "--help" || "$ENV_FILE" == "-h" ]]; then
  echo 'Usage: bash scripts/start-showcase.sh [dotenv-file]'
  echo 'Starts Java + PostgreSQL + role bootstrap. External RAGFlow/Dify must already be configured.'
  exit 0
fi
# Resolve before changing directory so a supplied relative path is unambiguous.
ENV_FILE="$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")"
cd "$ROOT_DIR"
python3 scripts/preflight-showcase.py --env-file "$ENV_FILE"
docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f docker-compose.showcase.yml \
  up -d --build --wait --wait-timeout 180 app
echo 'Showcase services started. Run preflight-showcase.py --online, import the project corpus, then --corpus.'
echo 'Use the configured DEEPRESEARCH_APP_PORT to open /demo.html.'

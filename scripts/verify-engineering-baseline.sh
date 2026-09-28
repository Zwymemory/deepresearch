#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$PROJECT_DIR"
docker compose up -d postgres elasticsearch reranker

containers=(deepresearch-pg deepresearch-es deepresearch-reranker)
deadline=$((SECONDS + 180))
for container in "${containers[@]}"; do
  until [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container" 2>/dev/null || true)" == "healthy" ]]; do
    if (( SECONDS >= deadline )); then
      docker compose ps
      echo "Timed out waiting for $container to become healthy" >&2
      exit 1
    fi
    sleep 2
  done
done

docker compose ps
mvn -q clean test
mvn -q -Pintegration verify
make workflow-test
make reranker-test
make showcase-check
docker compose config --quiet
git -C "$PROJECT_DIR" diff --check -- .

echo "DeepResearch engineering baseline verified."

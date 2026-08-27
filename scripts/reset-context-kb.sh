#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${DEEPRESEARCH_ADMIN_TOKEN:?Set DEEPRESEARCH_ADMIN_TOKEN to an ADMIN bearer token}"
AUTH_HEADER=(-H "Authorization: Bearer $ADMIN_TOKEN")
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KB_DIR="$ROOT_DIR/testdata/kb"

echo "Resetting context-engineering knowledge base at $BASE_URL"
curl -fsS "${AUTH_HEADER[@]}" -X DELETE "$BASE_URL/api/kb" >/dev/null

echo "Uploading context-engineering-long-manual.md"
curl -fsS -X POST "$BASE_URL/api/kb/documents/file" \
  "${AUTH_HEADER[@]}" \
  -F "title=DeepResearch W6/W7 长文档验证手册" \
  -F "file=@$KB_DIR/context-engineering-long-manual.md"
echo

echo "Documents:"
curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/documents"
echo

echo "Chunk count:"
curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/count"
echo

echo "Context retrieval eval:"
curl -fsS -X POST "$BASE_URL/api/eval/retrieval" \
  "${AUTH_HEADER[@]}" \
  -H "Content-Type: application/json" \
  -d '{"dataset":"context","topK":3,"recallK":20,"candidateK":10}'
echo

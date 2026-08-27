#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${DEEPRESEARCH_ADMIN_TOKEN:?Set DEEPRESEARCH_ADMIN_TOKEN to an ADMIN bearer token}"
AUTH_HEADER=(-H "Authorization: Bearer $ADMIN_TOKEN")
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KB_DIR="$ROOT_DIR/testdata/benchmark/kb"

echo "Resetting mini benchmark knowledge base at $BASE_URL"
curl -fsS "${AUTH_HEADER[@]}" -X DELETE "$BASE_URL/api/kb" >/dev/null

for file in "$KB_DIR"/*.md; do
  title="$(sed -n '1s/^# //p' "$file")"
  echo "Uploading $(basename "$file") as $title"
  curl -fsS -X POST "$BASE_URL/api/kb/documents/file" \
    "${AUTH_HEADER[@]}" \
    -F "title=$title" \
    -F "file=@$file"
  echo
done

echo "Documents:"
curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/documents"
echo

echo "Chunk count:"
curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/count"
echo

echo "Mini benchmark retrieval eval:"
curl -fsS -X POST "$BASE_URL/api/eval/retrieval" \
  "${AUTH_HEADER[@]}" \
  -H "Content-Type: application/json" \
  -d '{"dataset":"mini","topK":3}'
echo

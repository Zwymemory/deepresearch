#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${DEEPRESEARCH_ADMIN_TOKEN:?Set DEEPRESEARCH_ADMIN_TOKEN to an ADMIN bearer token}"
AUTH_HEADER=(-H "Authorization: Bearer $ADMIN_TOKEN")
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KB_DIR="$ROOT_DIR/testdata/open/scifact/kb"
RESULT_DIR="$ROOT_DIR/testdata/open/scifact/results"
RESULT_FILE="$RESULT_DIR/full-scifact-eval.json"

"$ROOT_DIR/scripts/prepare-full-scifact.sh"

mkdir -p "$RESULT_DIR"

echo "Resetting FULL SciFact knowledge base at $BASE_URL"
echo "This clears the current KB and calls your embedding provider for the full SciFact corpus."
curl -fsS "${AUTH_HEADER[@]}" -X DELETE "$BASE_URL/api/kb" >/dev/null

count=0
for file in "$KB_DIR"/*.md; do
  title="$(sed -n '1s/^# //p' "$file")"
  echo "Uploading $(basename "$file") as $title"
  curl -fsS -X POST "$BASE_URL/api/kb/documents/file" \
    "${AUTH_HEADER[@]}" \
    -F "title=$title" \
    -F "file=@$file" >/dev/null
  count=$((count + 1))
done

echo "Uploaded documents: $count"

echo "Chunk count:"
curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/count"
echo

echo "Full SciFact retrieval eval:"
curl -fsS -X POST "$BASE_URL/api/eval/retrieval" \
  "${AUTH_HEADER[@]}" \
  -H "Content-Type: application/json" \
  -d '{"dataset":"open-scifact","topK":10}' \
  > "$RESULT_FILE"

python3 "$ROOT_DIR/scripts/summarize-retrieval-eval.py" "$RESULT_FILE"
echo "Saved full eval JSON to $RESULT_FILE"

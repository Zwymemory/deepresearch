#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${DEEPRESEARCH_ADMIN_TOKEN:?Set DEEPRESEARCH_ADMIN_TOKEN to an ADMIN bearer token}"
AUTH_HEADER=(-H "Authorization: Bearer $ADMIN_TOKEN")
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KB_DIR="$ROOT_DIR/testdata/open/scifact/kb"
RESULT_DIR="$ROOT_DIR/testdata/open/scifact/results"
RESULT_FILE="$RESULT_DIR/full-scifact-eval.json"
FAILED_LOG="$RESULT_DIR/full-scifact-failed.txt"
DONE_FILE="$(mktemp)"

cleanup() {
  rm -f "$DONE_FILE"
}
trap cleanup EXIT

"$ROOT_DIR/scripts/prepare-full-scifact.sh" >/dev/null
mkdir -p "$RESULT_DIR"
: > "$FAILED_LOG"

echo "Resuming FULL SciFact ingestion at $BASE_URL"
echo "This does NOT clear the current KB. DONE documents are skipped."

curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/documents" | python3 -c '
import json
import sys
for doc in json.load(sys.stdin):
    filename = str(doc.get("filename", ""))
    if filename.startswith("scifact-") and doc.get("status") == "DONE":
        print(filename)
' > "$DONE_FILE"

total="$(find "$KB_DIR" -name '*.md' | wc -l | tr -d ' ')"
done_count="$(wc -l < "$DONE_FILE" | tr -d ' ')"
echo "Full corpus files: $total"
echo "Already DONE: $done_count"
echo "Remaining or retryable: $((total - done_count))"

processed=0
skipped=0
uploaded=0
failed=0

for file in "$KB_DIR"/*.md; do
  filename="$(basename "$file")"
  processed=$((processed + 1))
  if grep -Fxq "$filename" "$DONE_FILE"; then
    skipped=$((skipped + 1))
    if (( skipped % 500 == 0 )); then
      echo "Skipped DONE: $skipped / $done_count"
    fi
    continue
  fi

  title="$(sed -n '1s/^# //p' "$file")"
  echo "[$processed/$total] Uploading $filename as $title"

  ok=0
  body_file="$(mktemp)"
  for attempt in 1 2 3; do
    http_code="$(
      curl -sS -w '%{http_code}' -o "$body_file" \
        -X POST "$BASE_URL/api/kb/documents/file" \
        "${AUTH_HEADER[@]}" \
        -F "title=$title" \
        -F "file=@$file" || true
    )"
    if [[ "$http_code" == "200" ]]; then
      ok=1
      break
    fi
    echo "  attempt $attempt failed with HTTP $http_code: $(cat "$body_file")"
    sleep $((attempt * 3))
  done

  if [[ "$ok" == "1" ]]; then
    uploaded=$((uploaded + 1))
    echo "  ok: $(cat "$body_file")"
  else
    failed=$((failed + 1))
    echo "$filename" >> "$FAILED_LOG"
  fi
  rm -f "$body_file"

  if (( uploaded > 0 && uploaded % 100 == 0 )); then
    echo "Progress: uploaded=$uploaded skipped=$skipped failed=$failed processed=$processed/$total"
    curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/count"
    echo
  fi
done

echo "Resume finished: uploaded=$uploaded skipped=$skipped failed=$failed processed=$processed/$total"
echo "Chunk count:"
curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/count"
echo

if (( failed > 0 )); then
  echo "Some files still failed. See $FAILED_LOG"
  exit 1
fi

echo "Full SciFact retrieval eval:"
curl -fsS -X POST "$BASE_URL/api/eval/retrieval" \
  "${AUTH_HEADER[@]}" \
  -H "Content-Type: application/json" \
  -d '{"dataset":"open-scifact","topK":10}' \
  > "$RESULT_FILE"

python3 "$ROOT_DIR/scripts/summarize-retrieval-eval.py" "$RESULT_FILE"
echo "Saved full eval JSON to $RESULT_FILE"

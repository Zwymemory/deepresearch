#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${DEEPRESEARCH_ADMIN_TOKEN:?Set DEEPRESEARCH_ADMIN_TOKEN to an ADMIN bearer token}"
AUTH_HEADER=(-H "Authorization: Bearer $ADMIN_TOKEN")
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KB_DIR="$ROOT_DIR/testdata/kb"

echo "Resetting demo knowledge base at $BASE_URL"
curl -fsS "${AUTH_HEADER[@]}" -X DELETE "$BASE_URL/api/kb" >/dev/null

echo "Uploading employee-handbook.md"
curl -fsS -X POST "$BASE_URL/api/kb/documents/file" \
  "${AUTH_HEADER[@]}" \
  -F "title=员工手册" \
  -F "file=@$KB_DIR/employee-handbook.md"
echo

echo "Uploading tech-config-manual.md"
curl -fsS -X POST "$BASE_URL/api/kb/documents/file" \
  "${AUTH_HEADER[@]}" \
  -F "title=DeepResearch 技术配置手册" \
  -F "file=@$KB_DIR/tech-config-manual.md"
echo

echo "Uploading ai-agent-notes.pdf"
curl -fsS -X POST "$BASE_URL/api/kb/documents/file" \
  "${AUTH_HEADER[@]}" \
  -F "title=AI Agent 课程笔记" \
  -F "file=@$KB_DIR/ai-agent-notes.pdf"
echo

echo "Documents:"
curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/documents"
echo

echo "Chunk count:"
curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/count"
echo

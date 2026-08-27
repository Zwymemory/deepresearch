#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/import-project-kb.sh [--dry-run]

Imports only the sanitized Markdown files in docs/kb-project.
The script never clears the knowledge base. Re-running unchanged files is
idempotent because the ingestion service matches title + filename + content hash.

Environment:
  BASE_URL                    Java API base URL (default: http://localhost:8080)
  DEEPRESEARCH_ADMIN_TOKEN    ADMIN bearer token (required unless --dry-run)
USAGE
}

DRY_RUN=false
case "${1:-}" in
  "") ;;
  --dry-run) DRY_RUN=true ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

if [[ $# -gt 1 ]]; then
  usage >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KB_DIR="$ROOT_DIR/docs/kb-project"
BASE_URL="${BASE_URL:-http://localhost:8080}"
FILES=(
  "$KB_DIR/README.md"
  "$KB_DIR/01-architecture-and-trust-boundaries.md"
  "$KB_DIR/02-checkpoint-and-crash-recovery.md"
  "$KB_DIR/03-claim-lease-and-fencing.md"
  "$KB_DIR/04-sse-durable-replay.md"
  "$KB_DIR/05-tool-receipt-and-unknown-result.md"
  "$KB_DIR/06-verification-boundaries.md"
  "$KB_DIR/07-agent-evaluation-fact-cards.md"
)

for file in "${FILES[@]}"; do
  if [[ ! -s "$file" ]]; then
    echo "Missing or empty project knowledge document: $file" >&2
    exit 1
  fi
  if [[ "$(sed -n '1p' "$file")" != \#\ * ]]; then
    echo "Project knowledge document must start with one H1 title: $file" >&2
    exit 1
  fi
done

if [[ "$DRY_RUN" == true ]]; then
  echo "Validated ${#FILES[@]} project knowledge documents; no request was sent."
  printf '  %s\n' "${FILES[@]#"$ROOT_DIR/"}"
  exit 0
fi

command -v curl >/dev/null 2>&1 || {
  echo "curl is required" >&2
  exit 1
}

ADMIN_TOKEN="${DEEPRESEARCH_ADMIN_TOKEN:?Set DEEPRESEARCH_ADMIN_TOKEN to an ADMIN bearer token}"
AUTH_HEADER=(-H "Authorization: Bearer $ADMIN_TOKEN")

echo "Importing the sanitized project knowledge pack into $BASE_URL"
echo "Existing knowledge-base documents will not be deleted."

for file in "${FILES[@]}"; do
  title="$(sed -n '1s/^# //p' "$file")"
  if [[ -z "$title" ]]; then
    echo "Cannot read H1 title from $file" >&2
    exit 1
  fi
  echo "Uploading $(basename "$file") as: $title"
  curl -fsS -X POST "$BASE_URL/api/kb/documents/file" \
    "${AUTH_HEADER[@]}" \
    -F "title=$title" \
    -F "file=@$file"
  echo
done

echo "Project knowledge pack import completed."
echo "Current documents:"
curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/documents"
echo
echo "Current chunk count:"
curl -fsS "${AUTH_HEADER[@]}" "$BASE_URL/api/kb/count"
echo

#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/verify-public-release.sh [--skip-history]

Fail-closed audit of the committed DeepResearch public-release tree.
By default, Gitleaks must be installed and the complete reachable history is scanned.
--skip-history is reserved for CI jobs that run a separate full-history Gitleaks gate.
EOF
}

die() {
  printf 'PUBLIC RELEASE CHECK FAILED: %s\n' "$*" >&2
  exit 1
}

skip_history=false
case "${1:-}" in
  "") ;;
  --skip-history) skip_history=true ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; die "unknown argument: $1" ;;
esac
[[ $# -le 1 ]] || die "only one option is supported"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
project_root="$(cd "$script_dir/.." && pwd -P)"
repo_root="$(git -C "$project_root" rev-parse --show-toplevel 2>/dev/null)" \
  || die "project is not inside a Git worktree"
repo_root="$(cd "$repo_root" && pwd -P)"

subtree=""
if [[ "$project_root" != "$repo_root" ]]; then
  case "$project_root" in
    "$repo_root"/*) subtree="${project_root#"$repo_root"/}" ;;
    *) die "project path is outside the detected repository" ;;
  esac
  [[ "$subtree" == "deepresearch" ]] \
    || die "monorepo export is restricted to the exact deepresearch subtree (found: $subtree)"
fi

pathspec="${subtree:-.}"
worktree_status="$(git -C "$repo_root" status --porcelain=v1 --untracked-files=all -- "$pathspec")"
if [[ -n "$worktree_status" ]]; then
  dirty_count="$(printf '%s\n' "$worktree_status" | wc -l | tr -d '[:space:]')"
  printf '%s\n' "$worktree_status" | sed -n '1,80p' >&2
  if [[ "$dirty_count" -gt 80 ]]; then
    printf '... %s additional dirty paths omitted from this diagnostic\n' "$((dirty_count - 80))" >&2
  fi
  die "deepresearch has uncommitted or untracked files; commit/review them before export"
fi

commit="$(git -C "$repo_root" rev-parse --verify 'HEAD^{commit}')" \
  || die "HEAD does not resolve to a commit"
treeish="$commit"
object_prefix=""
if [[ -n "$subtree" ]]; then
  treeish="$commit:$subtree"
  object_prefix="$subtree/"
fi
git -C "$repo_root" cat-file -e "$treeish" \
  || die "committed deepresearch tree is missing at $commit"

required_files=(
  .gitattributes
  .gitleaksignore
  .gitleaks.toml
  .gitignore
  .dockerignore
  .github/workflows/ci.yml
  LICENSE
  THIRD_PARTY_NOTICES.md
  SECURITY.md
  README.md
  docs/PUBLIC_RELEASE.md
)
for required_file in "${required_files[@]}"; do
  git -C "$repo_root" cat-file -e "$commit:${object_prefix}${required_file}" \
    || die "required public-release file is missing: $required_file"
done

license_text="$(git -C "$repo_root" show "$commit:${object_prefix}LICENSE")"
[[ "$license_text" == MIT\ License* ]] || die "LICENSE is not the expected MIT license"

attributes_text="$(git -C "$repo_root" show "$commit:${object_prefix}.gitattributes")"
for required_rule in \
  'docs/career/resume-*.md export-ignore' \
  'testdata/open/** export-ignore' \
  '**/*.safetensors export-ignore' \
  '**/*.npy export-ignore'; do
  grep -Fq "$required_rule" <<<"$attributes_text" \
    || die ".gitattributes lacks required rule: $required_rule"
done

archive_entries="$(git -C "$repo_root" archive "$treeish" | tar -tf -)" \
  || die "unable to enumerate the committed archive"
[[ -n "$archive_entries" ]] || die "committed archive is empty"

archive_violation=false
while IFS= read -r entry; do
  [[ -n "$entry" ]] || continue
  normalized="${entry%/}"
  [[ -n "$normalized" ]] || continue
  lower="$(printf '%s' "$normalized" | tr '[:upper:]' '[:lower:]')"

  case "/$lower/" in
    */target/*|*/.venv/*|*/.venv-*/*|*/venv/*|*/__pycache__/*|*/.pytest_cache/*|*/.ruff_cache/*|*/.mypy_cache/*|*/.cache/*|*/cache/*|*/htmlcov/*|*/.tox/*|*/build/*|*/dist/*|*/artifacts/*|*/runs/*|*/outputs/*|*/raw/*)
      printf 'forbidden generated path: %s\n' "$normalized" >&2
      archive_violation=true
      ;;
  esac
  case "$lower" in
    testdata/open/*|testdata/eval/open-scifact.jsonl|reranker-service/artifacts/*|reranker-service/models/*|reranker-service/runs/*|reranker-service/training-artifacts/*|reranker-service/outputs/*)
      printf 'forbidden data/model output path: %s\n' "$normalized" >&2
      archive_violation=true
      ;;
    docs/career/resume-*)
      printf 'forbidden personal resume path: %s\n' "$normalized" >&2
      archive_violation=true
      ;;
    .env|*/.env|.env.*|*/.env.*)
      if [[ "$lower" != ".env.example" && "$lower" != */.env.example ]]; then
        printf 'forbidden environment file: %s\n' "$normalized" >&2
        archive_violation=true
      fi
      ;;
    application-local.yml|*/application-local.yml|.ds_store|*/.ds_store|*.log|*.egg-info|*/.egg-info/*|*.pem|*.key|*.p12|*.pfx|*.safetensors|*.bin|*.ckpt|*.onnx|*.gguf|*.pth|*.pt|*.npy|*.npz|*.zip|*.7z|*.tar|*.tar.gz|*.tgz)
      printf 'forbidden secret/model/binary artifact: %s\n' "$normalized" >&2
      archive_violation=true
      ;;
    *简历*)
      printf 'forbidden resume-like path: %s\n' "$normalized" >&2
      archive_violation=true
      ;;
  esac
done <<<"$archive_entries"
[[ "$archive_violation" == false ]] || die "committed archive contains excluded files"

email_pattern='[[:alnum:]_.%+-]+@[[:alnum:].-]+\.(com|cn|org|net|edu|io|ai|dev|me|co)([^[:alnum:]]|$)'
phone_pattern='(^|[^[:alnum:]])1[3-9][0-9]{9}([^[:alnum:]]|$)'
local_path_pattern='/'"Users/"'|/home/[[:alnum:]_.-]+/|[A-Za-z]:\\Users\\'
wechat_pattern='(微信|微信号|wechat)[[:space:]]*[:=：][[:space:]]*[[:alnum:]_-]{5,}'
secret_pattern='(sk|tvly)-[[:alnum:]_-]{20,}|AKIA[0-9A-Z]{16}|eyJ[[:alnum:]_-]{20,}\.[[:alnum:]_-]{20,}\.[[:alnum:]_-]{20,}|-----BEGIN ([A-Z0-9 ]+ )?PRIVATE KEY-----'

content_violation=false
while IFS= read -r entry; do
  [[ -n "$entry" && "$entry" != */ ]] || continue
  lower="$(printf '%s' "$entry" | tr '[:upper:]' '[:lower:]')"
  case "$lower" in
    *.md|*.txt|*.json|*.jsonl|*.yml|*.yaml|*.properties|*.xml|*.java|*.py|*.sh|*.html|*.toml|*.lock|*.sql|*.css|*.js|dockerfile|makefile|.gitignore|.gitattributes|.env.example|*/.env.example) ;;
    *) continue ;;
  esac
  object="$commit:${object_prefix}${entry}"
  if LC_ALL=C grep -IqE "$email_pattern" < <(git -C "$repo_root" show "$object"); then
    printf 'possible email address: %s\n' "$entry" >&2
    content_violation=true
  fi
  if LC_ALL=C grep -IqE "$phone_pattern" < <(git -C "$repo_root" show "$object"); then
    printf 'possible mainland China phone number: %s\n' "$entry" >&2
    content_violation=true
  fi
  if LC_ALL=C grep -IqE "$local_path_pattern" < <(git -C "$repo_root" show "$object"); then
    printf 'possible local absolute user path: %s\n' "$entry" >&2
    content_violation=true
  fi
  if LC_ALL=C grep -IqiE "$wechat_pattern" < <(git -C "$repo_root" show "$object"); then
    printf 'possible WeChat contact: %s\n' "$entry" >&2
    content_violation=true
  fi
  if LC_ALL=C grep -IqE "$secret_pattern" < <(git -C "$repo_root" show "$object"); then
    printf 'possible credential/private key: %s\n' "$entry" >&2
    content_violation=true
  fi
done <<<"$archive_entries"
[[ "$content_violation" == false ]] || die "public snapshot contains sensitive-looking content"

if [[ "$skip_history" == false ]]; then
  command -v gitleaks >/dev/null 2>&1 \
    || die "gitleaks is required for the full-history release gate"
  log_opts="--full-history --all --diff-filter=tuxdb"
  if [[ -n "$subtree" ]]; then
    log_opts="$log_opts -- $subtree"
    [[ -n "$(git -C "$repo_root" log -n 1 --full-history --all --format='%H' -- "$subtree")" ]] \
      || die "Git history query returned no deepresearch commits"
  else
    [[ -n "$(git -C "$repo_root" log -n 1 --full-history --all --format='%H')" ]] \
      || die "Git history query returned no commits"
  fi
  gitleaks git --redact --no-banner \
    --config "$project_root/.gitleaks.toml" \
    --log-opts="$log_opts" "$repo_root"
else
  printf 'History scan skipped here; a separate full-history Gitleaks gate is required.\n'
fi

entry_count="$(printf '%s\n' "$archive_entries" | wc -l | tr -d '[:space:]')"
printf 'PUBLIC RELEASE CHECK PASSED: commit=%s files=%s scope=%s\n' \
  "$commit" "$entry_count" "${subtree:-standalone}"

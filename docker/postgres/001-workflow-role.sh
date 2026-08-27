#!/bin/sh
set -eu

if [ -z "${WORKFLOW_DB_PASSWORD:-}" ]; then
  echo "WORKFLOW_DB_PASSWORD is required" >&2
  exit 1
fi

workflow_password_bytes=$(printf %s "$WORKFLOW_DB_PASSWORD" | LC_ALL=C wc -c | tr -d ' ')
if [ "$workflow_password_bytes" -lt 32 ]; then
  echo "WORKFLOW_DB_PASSWORD must contain at least 32 bytes" >&2
  exit 1
fi

if [ -n "${POSTGRES_HOST:-}" ]; then
  set -- --host "$POSTGRES_HOST"
else
  set --
fi

psql "$@" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 <<'SQL'
-- Import the secret from the container environment instead of placing it in
-- the psql process arguments, where it could be exposed by process inspection.
\getenv workflow_password WORKFLOW_DB_PASSWORD
SELECT 'CREATE ROLE deepresearch_workflow LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'deepresearch_workflow')
\gexec
-- Keep an existing role in sync when WORKFLOW_DB_PASSWORD is rotated.
-- :'workflow_password' asks psql to quote the value as a SQL literal, so
-- punctuation in the password cannot change the statement structure.
ALTER ROLE deepresearch_workflow WITH LOGIN PASSWORD :'workflow_password';
GRANT CONNECT ON DATABASE deepresearch TO deepresearch_workflow;
SQL

-- Keep existing KB identities; web identities address sanitized Tavily receipt snapshots.
-- No URL fetched by the final publisher and no rewrite of the applied V12 migration.
ALTER TABLE dify_workflow_source DROP CONSTRAINT ck_dify_workflow_source_key;
ALTER TABLE dify_workflow_source ADD CONSTRAINT ck_dify_workflow_source_key CHECK (
    citation_id ~ '^kb:ragflow:[^:[:space:]]+:[^:[:space:]]+:[^:[:space:]]+$'
    OR citation_id ~ '^web:tavily:[a-f0-9]{64}$'
);

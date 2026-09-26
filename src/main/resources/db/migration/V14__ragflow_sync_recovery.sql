-- An upload attempt is durable before the first remote request. The active mapping
-- remains visible while a replacement is parsed.
CREATE TABLE IF NOT EXISTS kb_ragflow_sync_job (
    legacy_doc_id text PRIMARY KEY REFERENCES kb_document(doc_id) ON DELETE CASCADE,
    dataset_id text NOT NULL,
    remote_name text NOT NULL,
    remote_document_id text,
    content_hash text NOT NULL,
    version integer NOT NULL,
    title text NOT NULL,
    source_type text NOT NULL,
    filename text NOT NULL,
    raw_content text NOT NULL,
    original_file bytea,
    original_filename text,
    status text NOT NULL,
    error_message text,
    lock_token text,
    lease_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kb_ragflow_sync_job_recovery
    ON kb_ragflow_sync_job(status, lease_until);

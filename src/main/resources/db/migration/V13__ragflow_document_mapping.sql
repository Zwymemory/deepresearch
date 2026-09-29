CREATE TABLE IF NOT EXISTS kb_ragflow_document (
    legacy_doc_id text PRIMARY KEY REFERENCES kb_document(doc_id) ON DELETE CASCADE,
    dataset_id text NOT NULL,
    document_id text,
    previous_document_id text,
    version integer NOT NULL,
    content_hash text NOT NULL,
    original_file bytea,
    original_filename text,
    sync_status text NOT NULL,
    error_message text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_kb_ragflow_remote_document
    ON kb_ragflow_document(dataset_id, document_id) WHERE document_id IS NOT NULL;

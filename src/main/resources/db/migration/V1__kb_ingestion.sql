CREATE TABLE IF NOT EXISTS kb_document (
    doc_id VARCHAR(64) PRIMARY KEY,
    title TEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    filename TEXT NOT NULL,
    raw_content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kb_document_hash
    ON kb_document (title, filename, content_hash);

CREATE TABLE IF NOT EXISTS kb_ingest_job (
    job_id VARCHAR(64) PRIMARY KEY,
    doc_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT fk_kb_ingest_job_document
        FOREIGN KEY (doc_id) REFERENCES kb_document(doc_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_kb_ingest_job_doc_id
    ON kb_ingest_job (doc_id);

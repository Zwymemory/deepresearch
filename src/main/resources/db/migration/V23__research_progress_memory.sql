-- Explicit progress snapshots only; never promoted into verified evidence or user_memory.
CREATE TABLE research_progress_memory (
    project_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL CHECK (octet_length(payload::text) <= 65536),
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, run_id),
    FOREIGN KEY (run_id, project_id, tenant_id, owner_id)
        REFERENCES agent_research_run(run_id, project_id, tenant_id, owner_id) ON DELETE CASCADE
);
CREATE INDEX idx_research_progress_owner ON research_progress_memory(tenant_id, owner_id, project_id, saved_at DESC);

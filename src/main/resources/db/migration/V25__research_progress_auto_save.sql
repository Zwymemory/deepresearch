-- Durable per-run automatic-save outcome; deletion remains suppressed across replay/restart.
CREATE TABLE research_progress_auto_save (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES agent_research_run(run_id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','SAVED','FAILED','DELETED')),
    error_code VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

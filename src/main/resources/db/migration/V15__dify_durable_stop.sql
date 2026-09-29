ALTER TABLE dify_workflow_run
    ADD COLUMN stop_state VARCHAR(24) NOT NULL DEFAULT 'NONE'
        CHECK (stop_state IN ('NONE','PENDING','LEASED','REQUESTED','CONFIRMED_STOPPED','REMOTE_TERMINAL','EXHAUSTED')),
    ADD COLUMN stop_attempts INTEGER NOT NULL DEFAULT 0 CHECK (stop_attempts >= 0),
    ADD COLUMN stop_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN stop_lease_until TIMESTAMPTZ,
    ADD COLUMN stop_claim_token UUID,
    ADD COLUMN stop_last_error VARCHAR(64);

CREATE INDEX idx_dify_stop_retry ON dify_workflow_run(stop_next_attempt_at, run_id)
    WHERE stop_state IN ('PENDING','REQUESTED','LEASED');

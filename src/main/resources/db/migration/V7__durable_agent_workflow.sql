CREATE SCHEMA IF NOT EXISTS langgraph;

CREATE TABLE IF NOT EXISTS agent_workflow_run (
    run_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(160) NOT NULL,
    question TEXT NOT NULL,
    context_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    endpoint VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    graph_thread_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    requested_scopes TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    grant_id VARCHAR(64) NOT NULL,
    claim_token UUID,
    claimed_by VARCHAR(128),
    lease_until TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    final_response JSONB,
    usage JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(64),
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_workflow_session
        FOREIGN KEY (session_id) REFERENCES agent_session(session_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_agent_workflow_idempotency
        UNIQUE (user_id, endpoint, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_workflow_claimable
    ON agent_workflow_run (status, lease_until, created_at);

CREATE INDEX IF NOT EXISTS idx_agent_workflow_owner_updated
    ON agent_workflow_run (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS agent_workflow_grant (
    grant_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    subject VARCHAR(160) NOT NULL,
    scopes TEXT[] NOT NULL,
    policy_version INTEGER NOT NULL DEFAULT 1,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_workflow_grant_run
        FOREIGN KEY (run_id) REFERENCES agent_workflow_run(run_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_agent_workflow_grant_run
    ON agent_workflow_grant (run_id);

CREATE TABLE IF NOT EXISTS agent_workflow_event (
    event_id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    role VARCHAR(32),
    task_id VARCHAR(64),
    type VARCHAR(64) NOT NULL,
    safe_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_workflow_event_run
        FOREIGN KEY (run_id) REFERENCES agent_workflow_run(run_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_agent_workflow_event_key
        UNIQUE (run_id, event_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_workflow_event_replay
    ON agent_workflow_event (run_id, event_id);

CREATE TABLE IF NOT EXISTS agent_workflow_tool_receipt (
    run_id VARCHAR(64) NOT NULL,
    call_id VARCHAR(160) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    safe_result JSONB,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, call_id),
    CONSTRAINT fk_agent_workflow_receipt_run
        FOREIGN KEY (run_id) REFERENCES agent_workflow_run(run_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_agent_workflow_receipt_task
    ON agent_workflow_tool_receipt (run_id, task_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'deepresearch_workflow') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA public, langgraph TO deepresearch_workflow';
        EXECUTE 'GRANT CREATE ON SCHEMA langgraph TO deepresearch_workflow';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON agent_workflow_run, agent_workflow_event, '
                || 'agent_workflow_grant, agent_workflow_tool_receipt TO deepresearch_workflow';
        EXECUTE 'GRANT USAGE, SELECT ON SEQUENCE agent_workflow_event_event_id_seq TO deepresearch_workflow';
    END IF;
END
$$;

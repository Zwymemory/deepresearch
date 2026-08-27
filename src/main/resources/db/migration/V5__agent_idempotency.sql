CREATE TABLE IF NOT EXISTS agent_idempotency_record (
    user_id VARCHAR(160) NOT NULL,
    endpoint VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, endpoint, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_idempotency_expiry
    ON agent_idempotency_record (expires_at);

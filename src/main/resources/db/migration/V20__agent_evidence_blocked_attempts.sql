-- B-owned capacity failures are durable gaps, never successful model assessments.
CREATE TABLE agent_evidence_blocked_attempt (
    attempt_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(64) NOT NULL REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    investigation CHAR(64) NOT NULL,
    payload JSONB NOT NULL CHECK (octet_length(payload::text) <= 131072),
    payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (payload->>'attempt_id'=attempt_id AND payload->>'investigation_id'=investigation
        AND payload->>'run_id'=run_id AND payload->>'tenant_id'=tenant_id
        AND payload->>'owner_id'=owner_id AND payload->>'project_id'=project_id)
);
CREATE INDEX idx_agent_evidence_blocked_run ON agent_evidence_blocked_attempt(tenant_id,owner_id,project_id,run_id);
CREATE TRIGGER trg_agent_evidence_blocked_immutable BEFORE UPDATE ON agent_evidence_blocked_attempt
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_evidence_immutable();
-- No sidecar DML grants. Completion is derived from a later attested check covering these originals.

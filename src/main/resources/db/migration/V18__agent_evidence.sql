-- B evidence data plane. A supplies project ACL / active task & budget authority.
-- No sidecar write grants, memory tables or changes to applied migrations.
CREATE TABLE agent_evidence_read_receipt (
    receipt_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(64) NOT NULL REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    task_id VARCHAR(128) NOT NULL,
    call_id VARCHAR(128) NOT NULL,
    claim_token UUID NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    parent_receipt_id VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('EXECUTING','COMPLETED','FAILED')),
    record_json JSONB,
    metadata JSONB,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    UNIQUE (run_id, call_id),
    UNIQUE (receipt_id,tenant_id,owner_id,project_id,run_id),
    CHECK (record_json IS NULL OR octet_length(record_json::text) <= 131072),
    CHECK ((status='EXECUTING' AND record_json IS NULL AND completed_at IS NULL)
        OR (status='FAILED' AND record_json IS NULL AND error_code IS NOT NULL AND completed_at IS NOT NULL)
        OR (status='COMPLETED' AND record_json IS NOT NULL AND metadata IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE TABLE agent_evidence_record (
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    record_type VARCHAR(32) NOT NULL CHECK (record_type IN ('Evidence','Claim','DecisionRecord','Challenge','ResearchPacket')),
    record_id VARCHAR(128) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    run_id VARCHAR(64) NOT NULL REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    read_receipt_id VARCHAR(128),
    payload JSONB NOT NULL CHECK (octet_length(payload::text) <= 131072),
    payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,owner_id,project_id,record_type,record_id,version),
    FOREIGN KEY(read_receipt_id,tenant_id,owner_id,project_id,run_id)
        REFERENCES agent_evidence_read_receipt(receipt_id,tenant_id,owner_id,project_id,run_id),
    CHECK (payload->>'record_type'=record_type AND payload->>'schema_version'='0.1.0'
        AND payload->>'tenant_id'=tenant_id AND payload->>'owner_id'=owner_id
        AND payload->>'project_id'=project_id AND payload->>'run_id'=run_id),
    CHECK ((record_type='Evidence' AND read_receipt_id IS NOT NULL) OR (record_type<>'Evidence' AND read_receipt_id IS NULL))
);
CREATE INDEX idx_agent_evidence_run ON agent_evidence_record(tenant_id,owner_id,project_id,run_id,record_type);

CREATE TABLE agent_evidence_check (
    check_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(64) NOT NULL REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    task_id VARCHAR(128) NOT NULL,
    call_id VARCHAR(128) NOT NULL,
    investigation CHAR(64) NOT NULL,
    dispute_round INTEGER NOT NULL CHECK (dispute_round BETWEEN 0 AND 2),
    parent_check_id VARCHAR(128) REFERENCES agent_evidence_check(check_id),
    request_fingerprint CHAR(64) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    request JSONB NOT NULL CHECK (octet_length(request::text) <= 131072),
    status VARCHAR(16) NOT NULL CHECK (status IN ('AWAITING_MODEL','COMPLETED')),
    response_sha256 CHAR(64),
    assessment_id VARCHAR(128),
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    UNIQUE (run_id,call_id),
    UNIQUE (tenant_id,owner_id,project_id,run_id,investigation,dispute_round),
    CHECK ((dispute_round=0 AND parent_check_id IS NULL) OR (dispute_round>0 AND parent_check_id IS NOT NULL)),
    CHECK ((status='AWAITING_MODEL' AND result IS NULL AND completed_at IS NULL)
        OR (status='COMPLETED' AND result IS NOT NULL AND assessment_id IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE FUNCTION enforce_agent_evidence_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'evidence records are immutable; corrections require a new identity';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_agent_evidence_immutable BEFORE UPDATE ON agent_evidence_record
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_evidence_immutable();
CREATE FUNCTION enforce_agent_evidence_completion_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('COMPLETED','FAILED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'completed evidence receipts and checks are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_agent_evidence_read_complete BEFORE UPDATE ON agent_evidence_read_receipt
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_evidence_completion_immutable();
CREATE TRIGGER trg_agent_evidence_check_complete BEFORE UPDATE ON agent_evidence_check
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_evidence_completion_immutable();

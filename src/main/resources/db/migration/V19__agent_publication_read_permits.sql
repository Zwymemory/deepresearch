-- Add the new committed evidence publication permit port without rewriting V17/V18.
-- Only Java creates/completes these source-validation proofs; the sidecar can read
-- their accounting rows but cannot forge a completed live KB revalidation.
CREATE TABLE agent_research_source_validation (
    run_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(160) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt=1),
    parent_call_id VARCHAR(160) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    evidence_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    expected_snapshot_hash VARCHAR(64) NOT NULL CHECK (expected_snapshot_hash ~ '^[0-9a-f]{64}$'),
    snapshot_hash VARCHAR(64) CHECK (snapshot_hash IS NULL OR snapshot_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('EXECUTING','COMPLETED','FAILED','UNKNOWN')),
    error_code VARCHAR(64),
    claim_token UUID NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (run_id,operation_id),
    UNIQUE (run_id,parent_call_id,evidence_id),
    FOREIGN KEY (run_id,operation_id,attempt) REFERENCES agent_research_operation(run_id,operation_key,attempt),
    FOREIGN KEY (run_id,task_id) REFERENCES agent_research_task(run_id,task_id),
    FOREIGN KEY (run_id,project_id,tenant_id,owner_id) REFERENCES agent_research_run(run_id,project_id,tenant_id,owner_id),
    CHECK (status<>'COMPLETED' OR (snapshot_hash IS NOT NULL AND error_code IS NULL AND completed_at IS NOT NULL))
);
CREATE FUNCTION enforce_agent_server_operation() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
BEGIN
    IF current_user='deepresearch_workflow' AND NEW.operation_key LIKE 'server:%' THEN
        RAISE EXCEPTION 'workflow sidecar cannot forge server validation operations';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_research_server_operation BEFORE INSERT OR UPDATE ON agent_research_operation
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_server_operation();
CREATE FUNCTION enforce_agent_validation_immutable() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
BEGIN
    IF OLD.status='COMPLETED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'completed source validation proof is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_research_validation_immutable BEFORE UPDATE ON agent_research_source_validation
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_validation_immutable();

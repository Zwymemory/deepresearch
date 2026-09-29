-- Round 1: server-issued identity, fenced tasks and a unified durable operation ledger.
CREATE TABLE research_project (
    project_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, owner_id, session_id),
    UNIQUE (project_id, tenant_id, owner_id)
);
CREATE TABLE agent_research_run (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    project_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    FOREIGN KEY (project_id, tenant_id, owner_id) REFERENCES research_project(project_id, tenant_id, owner_id),
    UNIQUE (run_id, project_id, tenant_id, owner_id)
);
CREATE TABLE agent_research_task (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_research_run(run_id) ON DELETE CASCADE,
    task_id VARCHAR(64) NOT NULL,
    objective TEXT NOT NULL,
    dependencies TEXT[] NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending','running','blocked','done','cancelled')),
    acceptance_criteria TEXT[] NOT NULL,
    evidence_ids TEXT[] NOT NULL DEFAULT '{}',
    plan_version INTEGER NOT NULL CHECK (plan_version > 0),
    task_json JSONB NOT NULL CHECK (octet_length(task_json::text) <= 32768),
    claim_token UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, task_id)
);
CREATE TABLE agent_research_operation (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_research_run(run_id) ON DELETE CASCADE,
    operation_key VARCHAR(160) NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('MODEL','TOOL')),
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('DECISION','CHECK','TOOL','PUBLICATION')),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED','SETTLED','UNKNOWN')),
    input_reserved BIGINT NOT NULL CHECK (input_reserved >= 0),
    output_reserved BIGINT NOT NULL CHECK (output_reserved >= 0),
    actual_usage JSONB,
    safe_result JSONB CHECK (safe_result IS NULL OR octet_length(safe_result::text) <= 131072),
    claim_token UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, operation_key, attempt),
    CHECK ((status = 'RESERVED' AND settled_at IS NULL AND safe_result IS NULL)
        OR (status = 'UNKNOWN' AND settled_at IS NOT NULL AND safe_result IS NULL)
        OR (status = 'SETTLED' AND settled_at IS NOT NULL AND safe_result IS NOT NULL AND actual_usage IS NOT NULL))
);
-- Java alone seals publication. The Python sidecar cannot turn arbitrary prose into a verified answer.
CREATE TABLE agent_research_publication (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_research_run(run_id) ON DELETE CASCADE,
    call_id VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('EXECUTING','COMPLETED','UNKNOWN')),
    answer_hash VARCHAR(64),
    citations JSONB,
    result JSONB,
    proof JSONB,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (run_id,call_id),
    CHECK (status<>'COMPLETED' OR (answer_hash IS NOT NULL AND citations IS NOT NULL AND result IS NOT NULL AND proof IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE TRIGGER agent_research_task_fence BEFORE INSERT OR UPDATE ON agent_research_task
    FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();
CREATE TRIGGER agent_research_operation_fence BEFORE INSERT OR UPDATE ON agent_research_operation
    FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();
-- Enforce persisted ceilings at the database boundary as well as in the runtime.
-- Holding the parent row serializes admissions across processes and operation types.
CREATE FUNCTION enforce_agent_operation_admission() RETURNS TRIGGER LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
DECLARE b JSONB; m BIGINT; t BIGINT; i BIGINT; o BIGINT; d BIGINT;
BEGIN
    SELECT budget INTO b FROM agent_workflow_run WHERE run_id=NEW.run_id FOR UPDATE;
    IF b->>'runtime' IS DISTINCT FROM 'agent' OR NEW.status<>'RESERVED' OR NEW.safe_result IS NOT NULL THEN
        RAISE EXCEPTION 'invalid agent operation admission';
    END IF;
    SELECT count(*) FILTER (WHERE kind='MODEL'), count(*) FILTER (WHERE kind='TOOL'),
           COALESCE(sum(COALESCE((actual_usage->>'input_tokens')::bigint,input_reserved)),0),
           COALESCE(sum(COALESCE((actual_usage->>'output_tokens')::bigint,output_reserved)),0),
           count(DISTINCT operation_key) FILTER (WHERE purpose='DECISION')
    INTO m,t,i,o,d FROM agent_research_operation WHERE run_id=NEW.run_id;
    IF (NEW.kind='MODEL' AND m >= (b->>'maxModelCalls')::int)
       OR (NEW.kind='TOOL' AND t >= (b->>'maxToolCalls')::int)
       OR i+NEW.input_reserved > (b->>'maxInputTokens')::bigint
       OR o+NEW.output_reserved > (b->>'maxOutputTokens')::bigint
       OR (NEW.purpose='DECISION' AND d >= (b->>'maxDecisionSteps')::int
           AND NOT EXISTS (SELECT 1 FROM agent_research_operation WHERE run_id=NEW.run_id AND operation_key=NEW.operation_key)) THEN
        RAISE EXCEPTION 'agent durable budget exhausted';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_research_operation_admission BEFORE INSERT ON agent_research_operation
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_operation_admission();
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='deepresearch_workflow') THEN
        GRANT SELECT ON research_project, agent_research_run TO deepresearch_workflow;
        GRANT SELECT, INSERT ON agent_research_task, agent_research_operation TO deepresearch_workflow;
        GRANT UPDATE (status,evidence_ids,task_json,claim_token,updated_at) ON agent_research_task TO deepresearch_workflow;
        GRANT UPDATE (status,safe_result,actual_usage,claim_token,settled_at) ON agent_research_operation TO deepresearch_workflow;
    END IF;
END $$;

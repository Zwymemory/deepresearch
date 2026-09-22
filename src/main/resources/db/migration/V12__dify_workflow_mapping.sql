CREATE TABLE dify_workflow_run (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    workflow_run_id VARCHAR(128),
    task_id VARCHAR(128),
    dispatch_state VARCHAR(16) NOT NULL CHECK (dispatch_state IN ('PENDING','POSTING','BOUND','UNKNOWN')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE agent_workflow_run DROP CONSTRAINT ck_agent_workflow_status;
ALTER TABLE agent_workflow_run ADD CONSTRAINT ck_agent_workflow_status CHECK (status IN (
    'QUEUED','PLANNING','WORKING','REVIEWING','SYNTHESIZING','FINALIZING',
    'DIFY_DISPATCHING','DIFY_WORKING','DISPATCH_UNKNOWN',
    'SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED'
));
ALTER TABLE agent_workflow_run DROP CONSTRAINT ck_agent_workflow_stage;
ALTER TABLE agent_workflow_run ADD CONSTRAINT ck_agent_workflow_stage CHECK (stage IN (
    'QUEUED','PLANNING','WORKING','REVIEWING','SYNTHESIZING','FINALIZING',
    'DIFY_DISPATCHING','DIFY_WORKING','DISPATCH_UNKNOWN',
    'SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED'
));
CREATE UNIQUE INDEX uq_dify_workflow_run_remote ON dify_workflow_run(workflow_run_id) WHERE workflow_run_id IS NOT NULL;
CREATE INDEX idx_dify_workflow_dispatch ON dify_workflow_run(dispatch_state, updated_at);

CREATE TABLE dify_workflow_source (
    run_id VARCHAR(64) NOT NULL REFERENCES dify_workflow_run(run_id) ON DELETE CASCADE,
    citation_id VARCHAR(2048) NOT NULL,
    PRIMARY KEY (run_id, citation_id),
    CONSTRAINT ck_dify_workflow_source_key CHECK (citation_id ~ '^kb:ragflow:[^:[:space:]]+:[^:[:space:]]+:[^:[:space:]]+$')
);

CREATE TABLE dify_workflow_tool_call (
    run_id VARCHAR(64) NOT NULL REFERENCES dify_workflow_run(run_id) ON DELETE CASCADE,
    call_id VARCHAR(160) NOT NULL,
    tool_name VARCHAR(32) NOT NULL CHECK (tool_name IN ('kb_search','web_search','calculator')),
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('EXECUTING','COMPLETED')),
    safe_result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, call_id),
    CONSTRAINT ck_dify_tool_result_size CHECK (safe_result IS NULL OR octet_length(safe_result::text) <= 131072)
);

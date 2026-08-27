-- Durable call-count reservations close the gap between an external call and the
-- following LangGraph checkpoint.  Actual token/cost usage remains provider-reported
-- and is deliberately not represented as a pre-call hard reservation here.

CREATE TABLE agent_workflow_budget_reservation (
    run_id VARCHAR(64) NOT NULL,
    operation_key VARCHAR(160) NOT NULL,
    attempt INTEGER NOT NULL,
    kind VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    claim_token UUID NOT NULL,
    origin_claim_token UUID NOT NULL,
    actual_usage JSONB,
    safe_result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, operation_key, attempt),
    CONSTRAINT fk_agent_workflow_budget_reservation_run
        FOREIGN KEY (run_id) REFERENCES agent_workflow_run(run_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_agent_workflow_budget_reservation_attempt
        CHECK (attempt > 0),
    CONSTRAINT ck_agent_workflow_budget_reservation_operation_key
        CHECK (operation_key ~ '^[a-z0-9][a-z0-9:._-]{0,159}$'),
    CONSTRAINT ck_agent_workflow_budget_reservation_kind
        CHECK (kind IN ('MODEL','TOOL')),
    CONSTRAINT ck_agent_workflow_budget_reservation_key_kind
        CHECK (
            (kind = 'MODEL' AND operation_key LIKE 'model:%')
            OR (kind = 'TOOL' AND operation_key LIKE 'tool-%')
        ),
    CONSTRAINT ck_agent_workflow_budget_reservation_status
        CHECK (status IN ('RESERVED','SETTLED','UNKNOWN')),
    CONSTRAINT ck_agent_workflow_budget_reservation_tool_attempt
        CHECK (kind <> 'TOOL' OR attempt = 1),
    CONSTRAINT ck_agent_workflow_budget_reservation_usage_size
        CHECK (actual_usage IS NULL OR octet_length(actual_usage::text) <= 4096),
    CONSTRAINT ck_agent_workflow_budget_reservation_result_size
        CHECK (safe_result IS NULL OR octet_length(safe_result::text) <= 131072),
    CONSTRAINT ck_agent_workflow_budget_reservation_shape
        CHECK (
            (status = 'RESERVED'
                AND actual_usage IS NULL
                AND safe_result IS NULL
                AND settled_at IS NULL)
            OR
            (status = 'UNKNOWN'
                AND kind = 'MODEL'
                AND actual_usage IS NULL
                AND safe_result IS NULL
                AND settled_at IS NOT NULL)
            OR
            (status = 'SETTLED'
                AND actual_usage IS NOT NULL
                AND settled_at IS NOT NULL
                AND ((kind = 'MODEL' AND safe_result IS NOT NULL)
                    OR (kind = 'TOOL' AND safe_result IS NULL)))
        )
);

CREATE INDEX idx_agent_workflow_budget_reservation_run_kind
    ON agent_workflow_budget_reservation (run_id, kind, status);

-- Reuse V8's parent-claim/lease/cancellation fence for both inserts and updates.
CREATE TRIGGER trg_workflow_sidecar_budget_reservation_insert
BEFORE INSERT ON agent_workflow_budget_reservation
FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();

CREATE TRIGGER trg_workflow_sidecar_budget_reservation_update
BEFORE UPDATE ON agent_workflow_budget_reservation
FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'deepresearch_workflow') THEN
        GRANT SELECT ON agent_workflow_budget_reservation TO deepresearch_workflow;
        GRANT INSERT
            (run_id, operation_key, attempt, kind, status, claim_token,
             origin_claim_token, created_at)
            ON agent_workflow_budget_reservation TO deepresearch_workflow;
        GRANT UPDATE
            (status, claim_token, actual_usage, safe_result, settled_at)
            ON agent_workflow_budget_reservation TO deepresearch_workflow;
    END IF;
END
$$;

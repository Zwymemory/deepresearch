-- Make a Java-persisted tool result replayable when Python crashes before
-- writing its ToolExecutionResult. An ambiguous EXECUTING row is deliberately
-- not retried. These columns are owned by the Java MCP data plane; the sidecar
-- can read them but cannot forge or mutate them.

ALTER TABLE agent_workflow_tool_receipt
    ADD COLUMN mcp_execution_status VARCHAR(16),
    ADD COLUMN mcp_safe_result JSONB,
    ADD COLUMN mcp_claim_token UUID,
    ADD COLUMN mcp_started_at TIMESTAMPTZ,
    ADD COLUMN mcp_completed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_agent_workflow_mcp_execution_status
        CHECK (mcp_execution_status IS NULL OR mcp_execution_status IN ('EXECUTING','COMPLETED')),
    ADD CONSTRAINT ck_agent_workflow_mcp_result_size
        CHECK (mcp_safe_result IS NULL OR octet_length(mcp_safe_result::text) <= 131072),
    ADD CONSTRAINT ck_agent_workflow_mcp_execution_shape
        CHECK (
            (mcp_execution_status IS NULL
                AND mcp_claim_token IS NULL
                AND mcp_started_at IS NULL
                AND mcp_completed_at IS NULL
                AND mcp_safe_result IS NULL)
            OR
            (mcp_execution_status = 'EXECUTING'
                AND mcp_claim_token IS NOT NULL
                AND mcp_started_at IS NOT NULL
                AND mcp_completed_at IS NULL
                AND mcp_safe_result IS NULL)
            OR
            (mcp_execution_status = 'COMPLETED'
                AND mcp_claim_token IS NOT NULL
                AND mcp_started_at IS NOT NULL
                AND mcp_completed_at IS NOT NULL
                AND mcp_safe_result IS NOT NULL)
        );

CREATE OR REPLACE FUNCTION enforce_workflow_sidecar_mcp_receipt_columns()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user <> 'deepresearch_workflow' THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.mcp_execution_status IS NOT NULL
           OR NEW.mcp_safe_result IS NOT NULL
           OR NEW.mcp_claim_token IS NOT NULL
           OR NEW.mcp_started_at IS NOT NULL
           OR NEW.mcp_completed_at IS NOT NULL THEN
            RAISE EXCEPTION 'workflow sidecar cannot forge Java MCP execution receipt';
        END IF;
    ELSIF NEW.mcp_execution_status IS DISTINCT FROM OLD.mcp_execution_status
       OR NEW.mcp_safe_result IS DISTINCT FROM OLD.mcp_safe_result
       OR NEW.mcp_claim_token IS DISTINCT FROM OLD.mcp_claim_token
       OR NEW.mcp_started_at IS DISTINCT FROM OLD.mcp_started_at
       OR NEW.mcp_completed_at IS DISTINCT FROM OLD.mcp_completed_at THEN
        RAISE EXCEPTION 'workflow sidecar cannot mutate Java MCP execution receipt';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_workflow_sidecar_mcp_receipt_columns
BEFORE INSERT OR UPDATE ON agent_workflow_tool_receipt
FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_mcp_receipt_columns();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'deepresearch_workflow') THEN
        REVOKE INSERT ON agent_workflow_tool_receipt FROM deepresearch_workflow;
        GRANT INSERT
            (run_id, call_id, task_id, tool_name, request_fingerprint,
             status, safe_result, error_code, created_at, completed_at, claim_token)
            ON agent_workflow_tool_receipt TO deepresearch_workflow;
    END IF;
END
$$;

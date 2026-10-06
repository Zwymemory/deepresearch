-- M2 summaries share the existing fenced operation ledger and request budget.
ALTER TABLE agent_research_operation DROP CONSTRAINT agent_research_operation_purpose_check;
ALTER TABLE agent_research_operation ADD CONSTRAINT agent_research_operation_purpose_check
    CHECK (purpose IN ('DECISION','CHECK','TOOL','PUBLICATION','SUMMARY'));
CREATE TABLE agent_context_summary (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_research_run(run_id) ON DELETE CASCADE,
    source_sha256 VARCHAR(64) NOT NULL CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('READY','FAILED')),
    view JSONB NOT NULL CHECK (octet_length(view::text) <= 262144),
    claim_token UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id,source_sha256)
);
CREATE TRIGGER agent_context_summary_fence BEFORE INSERT ON agent_context_summary
    FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='deepresearch_workflow') THEN
        GRANT SELECT,INSERT ON agent_context_summary TO deepresearch_workflow;
    END IF;
END $$;

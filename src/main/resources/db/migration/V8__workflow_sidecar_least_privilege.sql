-- Harden the Python sidecar database role.  The sidecar may execute real
-- workflows, but it must never be able to create or rewrite the canonical
-- Java-issued grant/tenant binding.

ALTER TABLE agent_workflow_run
    ADD COLUMN budget JSONB NOT NULL DEFAULT
        '{"maxTasks":4,"maxConcurrency":2,"maxRevisionRounds":1,"maxModelCalls":24,"maxToolCalls":16,"maxTokens":100000,"maxCostCny":1.0,"deadlineSeconds":120}'::jsonb,
    ADD COLUMN finalize_fingerprint VARCHAR(64),
    ADD COLUMN finalized_claim_token UUID,
    ADD CONSTRAINT ck_agent_workflow_status CHECK (status IN (
        'QUEUED','PLANNING','WORKING','REVIEWING','SYNTHESIZING','FINALIZING',
        'SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED'
    )),
    ADD CONSTRAINT ck_agent_workflow_stage CHECK (stage IN (
        'QUEUED','PLANNING','WORKING','REVIEWING','SYNTHESIZING','FINALIZING',
        'SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED'
    )),
    ADD CONSTRAINT ck_agent_workflow_budget_size
        CHECK (octet_length(budget::text) <= 4096);

ALTER TABLE agent_workflow_event
    ADD COLUMN claim_token UUID,
    ADD CONSTRAINT ck_agent_workflow_event_payload_size
        CHECK (octet_length(safe_payload::text) <= 32768);

ALTER TABLE agent_workflow_tool_receipt
    ADD COLUMN claim_token UUID;

ALTER TABLE agent_workflow_grant
    ADD CONSTRAINT uq_agent_workflow_grant_run UNIQUE (run_id);

ALTER TABLE agent_workflow_grant
    ADD CONSTRAINT uq_agent_workflow_grant_binding
        UNIQUE (grant_id, run_id, subject);

ALTER TABLE agent_workflow_grant
    ADD CONSTRAINT uq_agent_workflow_grant_id_run
        UNIQUE (grant_id, run_id);

ALTER TABLE agent_workflow_run
    ADD CONSTRAINT fk_agent_workflow_canonical_grant
        FOREIGN KEY (grant_id, run_id, user_id)
        REFERENCES agent_workflow_grant (grant_id, run_id, subject)
        DEFERRABLE INITIALLY DEFERRED;

-- Java records the scope chosen for each Planner task.  Reclaims may rotate the
-- claim token for the same deterministic task id, invalidating tokens issued by
-- the old runner without granting a broader scope.
CREATE TABLE agent_workflow_task_grant (
    run_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    grant_id VARCHAR(64) NOT NULL,
    claim_token UUID NOT NULL,
    scopes TEXT[] NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, task_id),
    CONSTRAINT fk_agent_workflow_task_grant_parent
        FOREIGN KEY (grant_id, run_id)
        REFERENCES agent_workflow_grant (grant_id, run_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_agent_workflow_task_grant_scopes
        CHECK (cardinality(scopes) BETWEEN 1 AND 3)
);

CREATE INDEX idx_agent_workflow_task_grant_claim
    ON agent_workflow_task_grant (run_id, claim_token);

CREATE OR REPLACE FUNCTION enforce_workflow_sidecar_run_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user <> 'deepresearch_workflow' THEN
        RETURN NEW;
    END IF;

    IF NEW.status IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED') THEN
        RAISE EXCEPTION 'workflow sidecar cannot write terminal state';
    END IF;

    IF NEW.claim_token IS DISTINCT FROM OLD.claim_token
       AND OLD.claim_token IS NOT NULL
       AND OLD.lease_until >= now() THEN
        RAISE EXCEPTION 'active workflow claim cannot be replaced';
    END IF;

    IF NOT (
        -- FINALIZING is also the fenced hand-off for abnormal outcomes.  A timeout,
        -- cancellation or budget failure may happen at any non-terminal stage; Java
        -- remains the only component allowed to persist the terminal status.
        (OLD.status = 'QUEUED' AND NEW.status IN ('QUEUED','PLANNING','FINALIZING')) OR
        (OLD.status = 'PLANNING' AND NEW.status IN ('PLANNING','WORKING','FINALIZING')) OR
        (OLD.status = 'WORKING' AND NEW.status IN ('WORKING','REVIEWING','FINALIZING')) OR
        (OLD.status = 'REVIEWING' AND NEW.status IN ('REVIEWING','WORKING','SYNTHESIZING','FINALIZING')) OR
        (OLD.status = 'SYNTHESIZING' AND NEW.status IN ('SYNTHESIZING','FINALIZING')) OR
        (OLD.status = 'FINALIZING' AND NEW.status = 'FINALIZING')
    ) THEN
        RAISE EXCEPTION 'illegal workflow sidecar state transition: % -> %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_workflow_sidecar_run_update
BEFORE UPDATE ON agent_workflow_run
FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_run_update();

CREATE OR REPLACE FUNCTION enforce_workflow_sidecar_child_write()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    active BOOLEAN;
BEGIN
    IF current_user <> 'deepresearch_workflow' THEN
        RETURN NEW;
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM agent_workflow_run r
        WHERE r.run_id = NEW.run_id
          AND r.claim_token = NEW.claim_token
          AND r.lease_until > now()
          AND r.cancel_requested = FALSE
          AND r.status IN ('PLANNING','WORKING','REVIEWING','SYNTHESIZING','FINALIZING')
    ) INTO active;
    IF NOT active THEN
        RAISE EXCEPTION 'stale workflow claim cannot write child records';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_workflow_sidecar_event_write
BEFORE INSERT ON agent_workflow_event
FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();

CREATE TRIGGER trg_workflow_sidecar_receipt_insert
BEFORE INSERT ON agent_workflow_tool_receipt
FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();

CREATE TRIGGER trg_workflow_sidecar_receipt_update
BEFORE UPDATE ON agent_workflow_tool_receipt
FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'deepresearch_workflow') THEN
        EXECUTE 'REVOKE ALL PRIVILEGES ON agent_workflow_run, agent_workflow_event, '
                || 'agent_workflow_grant, agent_workflow_tool_receipt FROM deepresearch_workflow';

        EXECUTE 'GRANT SELECT ON agent_workflow_run, agent_workflow_event, '
                || 'agent_workflow_grant, agent_workflow_tool_receipt TO deepresearch_workflow';

        EXECUTE 'GRANT UPDATE '
                || '(claim_token, claimed_by, lease_until, heartbeat_at, status, stage, usage, version, updated_at) '
                || 'ON agent_workflow_run TO deepresearch_workflow';

        EXECUTE 'GRANT INSERT ON agent_workflow_event TO deepresearch_workflow';
        EXECUTE 'GRANT INSERT ON agent_workflow_tool_receipt TO deepresearch_workflow';
        EXECUTE 'GRANT UPDATE (status, safe_result, error_code, completed_at, claim_token) '
                || 'ON agent_workflow_tool_receipt TO deepresearch_workflow';
        EXECUTE 'GRANT USAGE, SELECT ON SEQUENCE agent_workflow_event_event_id_seq '
                || 'TO deepresearch_workflow';
    END IF;
END
$$;

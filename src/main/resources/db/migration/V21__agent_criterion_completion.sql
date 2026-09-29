-- A-owned native completion coverage. Python flags are not publication proofs.
CREATE TABLE agent_research_criterion (
    run_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    criterion_id VARCHAR(128) NOT NULL,
    criterion_index INTEGER NOT NULL CHECK (criterion_index BETWEEN 0 AND 4),
    criterion_text TEXT NOT NULL,
    expected_claim JSONB,
    expected_hash CHAR(64),
    investigation CHAR(64),
    last_call_id VARCHAR(160),
    dependency_snapshot JSONB NOT NULL DEFAULT '{}',
    claim_token UUID NOT NULL,
    PRIMARY KEY (run_id, task_id, criterion_id),
    UNIQUE (run_id, task_id, criterion_index),
    UNIQUE (run_id, task_id, expected_hash),
    FOREIGN KEY (run_id,task_id) REFERENCES agent_research_task(run_id,task_id),
    CHECK ((expected_claim IS NULL AND expected_hash IS NULL AND investigation IS NULL AND last_call_id IS NULL)
        OR (expected_claim IS NOT NULL AND expected_hash ~ '^[0-9a-f]{64}$' AND investigation ~ '^[0-9a-f]{64}$' AND last_call_id IS NOT NULL)),
    CHECK (octet_length(expected_claim::text)<=16384 AND octet_length(dependency_snapshot::text)<=32768)
);
CREATE TABLE agent_research_investigation_progress (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_research_run(run_id),
    investigation CHAR(64) NOT NULL CHECK (investigation ~ '^[0-9a-f]{64}$'),
    current_call_id VARCHAR(160) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt=1),
    claim_token UUID NOT NULL,
    PRIMARY KEY (run_id,investigation),
    FOREIGN KEY (run_id,current_call_id,attempt) REFERENCES agent_research_operation(run_id,operation_key,attempt)
);
CREATE FUNCTION agent_task_dependency_snapshot(run_key TEXT,task_key TEXT) RETURNS JSONB
LANGUAGE sql STABLE SET search_path=pg_catalog,public AS $$
 SELECT COALESCE(jsonb_object_agg(dependency,signature),'{}'::jsonb)
 FROM (
    SELECT dependency, COALESCE((SELECT jsonb_agg(jsonb_build_object(
            'criterion_id',c.criterion_id,'expected_claim',c.expected_claim,
            'current_call_id',p.current_call_id) ORDER BY c.criterion_index)
        FROM agent_research_criterion c LEFT JOIN agent_research_investigation_progress p
          ON p.run_id=c.run_id AND p.investigation=c.investigation
        WHERE c.run_id=run_key AND c.task_id=dependency),'[]'::jsonb) AS signature
    FROM agent_research_task t CROSS JOIN LATERAL unnest(t.dependencies) dependency
    WHERE t.run_id=run_key AND t.task_id=task_key
 ) x
$$;
CREATE FUNCTION enforce_agent_criterion_binding() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
DECLARE criteria TEXT[];
BEGIN
    SELECT acceptance_criteria INTO criteria FROM agent_research_task WHERE run_id=NEW.run_id AND task_id=NEW.task_id;
    IF criteria[NEW.criterion_index+1] IS DISTINCT FROM NEW.criterion_text THEN
        RAISE EXCEPTION 'criterion differs from original native standard';
    END IF;
    IF TG_OP='UPDATE' THEN
        IF ROW(NEW.run_id,NEW.task_id,NEW.criterion_id,NEW.criterion_index,NEW.criterion_text)
            IS DISTINCT FROM ROW(OLD.run_id,OLD.task_id,OLD.criterion_id,OLD.criterion_index,OLD.criterion_text)
            OR (OLD.expected_claim IS NOT NULL AND ROW(NEW.expected_claim,NEW.expected_hash,NEW.investigation)
                IS DISTINCT FROM ROW(OLD.expected_claim,OLD.expected_hash,OLD.investigation)) THEN
            RAISE EXCEPTION 'criterion identity and initial scoped claim are immutable';
        END IF;
        IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    END IF;
    IF NEW.last_call_id IS NOT NULL AND (TG_OP='INSERT' OR NEW IS DISTINCT FROM OLD) THEN
        IF NOT EXISTS (SELECT 1 FROM agent_research_operation o JOIN agent_workflow_run r ON r.run_id=o.run_id
            WHERE o.run_id=NEW.run_id AND o.operation_key=NEW.last_call_id AND o.attempt=1
              AND o.kind='TOOL' AND o.purpose='TOOL' AND o.status='RESERVED'
              AND o.claim_token=NEW.claim_token AND r.claim_token=NEW.claim_token) THEN
            RAISE EXCEPTION 'criterion execution requires a current reserved check operation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM agent_research_investigation_progress p
            WHERE p.run_id=NEW.run_id AND p.investigation=NEW.investigation
              AND p.current_call_id=NEW.last_call_id) THEN
            RAISE EXCEPTION 'criterion must bind the current investigation attempt';
        END IF;
        IF NEW.dependency_snapshot IS DISTINCT FROM agent_task_dependency_snapshot(NEW.run_id,NEW.task_id) THEN
            RAISE EXCEPTION 'criterion dependency snapshot differs from current prerequisites';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE FUNCTION enforce_agent_investigation_progress() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM agent_research_operation o JOIN agent_workflow_run r ON r.run_id=o.run_id
        WHERE o.run_id=NEW.run_id AND o.operation_key=NEW.current_call_id AND o.attempt=1
          AND o.kind='TOOL' AND o.purpose='TOOL' AND o.status='RESERVED'
          AND o.claim_token=NEW.claim_token AND r.claim_token=NEW.claim_token) THEN
        RAISE EXCEPTION 'investigation progress requires a current reserved check operation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_criterion_fence BEFORE INSERT OR UPDATE ON agent_research_criterion
    FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();
CREATE TRIGGER agent_criterion_binding BEFORE INSERT OR UPDATE ON agent_research_criterion
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_criterion_binding();
CREATE TRIGGER agent_investigation_progress_fence BEFORE INSERT OR UPDATE ON agent_research_investigation_progress
    FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();
CREATE TRIGGER agent_investigation_progress_check BEFORE INSERT OR UPDATE ON agent_research_investigation_progress
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_investigation_progress();
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='deepresearch_workflow') THEN
        GRANT SELECT,INSERT ON agent_research_criterion,agent_research_investigation_progress TO deepresearch_workflow;
        GRANT UPDATE (expected_claim,expected_hash,investigation,last_call_id,dependency_snapshot,claim_token) ON agent_research_criterion TO deepresearch_workflow;
        GRANT UPDATE (current_call_id,claim_token) ON agent_research_investigation_progress TO deepresearch_workflow;
    END IF;
END $$;

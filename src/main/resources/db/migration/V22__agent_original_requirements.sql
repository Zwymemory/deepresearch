-- Original question obligations are immutable; model/checkpoint flags cannot grant success.
CREATE TABLE agent_research_requirements (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES agent_research_run(run_id),
    manifest JSONB NOT NULL CHECK (octet_length(manifest::text)<=65536),
    declaration_key VARCHAR(160) NOT NULL,
    declaration_attempt INTEGER NOT NULL CHECK (declaration_attempt BETWEEN 1 AND 2),
    claim_token UUID NOT NULL,
    FOREIGN KEY (run_id,declaration_key,declaration_attempt)
        REFERENCES agent_research_operation(run_id,operation_key,attempt)
);
CREATE TABLE agent_research_requirement_binding (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_research_requirements(run_id),
    requirement_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    criterion_id VARCHAR(128) NOT NULL,
    claim_token UUID NOT NULL,
    PRIMARY KEY (run_id,requirement_id),
    UNIQUE (run_id,criterion_id),
    FOREIGN KEY (run_id,task_id,criterion_id)
        REFERENCES agent_research_criterion(run_id,task_id,criterion_id)
);
-- Same normalized unknown scope/condition and span semantics as the planning helper.
CREATE FUNCTION agent_requirement_declaration(value JSONB) RETURNS JSONB
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,public AS $$
 SELECT jsonb_build_object('text',value->'text','kind',value->'kind',
    'question_spans', (SELECT jsonb_object_agg((span->>'start')||':'||(span->>'end'),true)
                      FROM jsonb_array_elements(value->'question_spans') span),
    'applicability',jsonb_build_object('subject',value->'applicability'->'subject',
        'version',CASE WHEN value->'applicability'->'version'->>'status'='unknown'
                      THEN jsonb_build_object('status','unknown','value',NULL) ELSE value->'applicability'->'version' END,
        'valid_at',CASE WHEN value->'applicability'->'valid_at'->>'status'='unknown'
                       THEN jsonb_build_object('status','unknown','value',NULL) ELSE value->'applicability'->'valid_at' END,
        'conditions',COALESCE((SELECT jsonb_object_agg(condition,true)
                              FROM jsonb_array_elements_text(value->'applicability'->'conditions') condition),'{}'::jsonb)))
$$;
CREATE FUNCTION enforce_agent_requirements() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
DECLARE body JSONB;
BEGIN
    IF TG_OP='UPDATE' AND ROW(NEW.run_id,NEW.manifest,NEW.declaration_key,NEW.declaration_attempt)
        IS DISTINCT FROM ROW(OLD.run_id,OLD.manifest,OLD.declaration_key,OLD.declaration_attempt) THEN
        RAISE EXCEPTION 'original requirements are immutable';
    END IF;
    SELECT o.safe_result->'value' INTO body
      FROM agent_research_operation o JOIN agent_workflow_run r USING(run_id)
      WHERE o.run_id=NEW.run_id AND o.operation_key=NEW.declaration_key
        AND o.attempt=NEW.declaration_attempt AND o.status='SETTLED'
        AND o.kind='MODEL' AND o.purpose='DECISION';
    IF body IS NULL OR jsonb_typeof(body->'requirements') IS DISTINCT FROM 'array'
        OR jsonb_array_length(body->'requirements')=0
        OR NEW.manifest->>'run_id' IS DISTINCT FROM NEW.run_id
        OR jsonb_typeof(NEW.manifest->'requirements') IS DISTINCT FROM 'array'
        OR jsonb_array_length(NEW.manifest->'requirements')<>jsonb_array_length(body->'requirements')
        OR EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.manifest->'requirements') req
                   WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements(body->'requirements') draft
                                     WHERE agent_requirement_declaration(req) = agent_requirement_declaration(draft))) THEN
        RAISE EXCEPTION 'requirements require their exact settled planning response';
    END IF;
    RETURN NEW;
END $$;
CREATE FUNCTION enforce_agent_requirement_binding() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
BEGIN
    IF TG_OP='UPDATE' AND ROW(NEW.run_id,NEW.requirement_id,NEW.task_id,NEW.criterion_id)
        IS DISTINCT FROM ROW(OLD.run_id,OLD.requirement_id,OLD.task_id,OLD.criterion_id) THEN
        RAISE EXCEPTION 'requirement associations are append-only';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM agent_research_requirements r,
                   jsonb_array_elements(r.manifest->'requirements') req
                   WHERE r.run_id=NEW.run_id AND req->>'requirement_id'=NEW.requirement_id) THEN
        RAISE EXCEPTION 'unknown original requirement';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_requirements_fence BEFORE INSERT OR UPDATE ON agent_research_requirements
    FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();
CREATE TRIGGER agent_requirements_immutable BEFORE INSERT OR UPDATE ON agent_research_requirements
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_requirements();
CREATE TRIGGER agent_requirement_binding_fence BEFORE INSERT OR UPDATE ON agent_research_requirement_binding
    FOR EACH ROW EXECUTE FUNCTION enforce_workflow_sidecar_child_write();
CREATE TRIGGER agent_requirement_binding_immutable BEFORE INSERT OR UPDATE ON agent_research_requirement_binding
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_requirement_binding();
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='deepresearch_workflow') THEN
        GRANT SELECT,INSERT ON agent_research_requirements,agent_research_requirement_binding TO deepresearch_workflow;
    END IF;
END $$;

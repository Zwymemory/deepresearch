-- V8's child-write trigger originally referenced agent_workflow_run without a
-- schema qualifier.  A restricted role can normally create temporary tables,
-- and PostgreSQL resolves an unqualified relation through pg_temp first.  Pin
-- both the function search path and the canonical parent relation so a temp
-- shadow can never satisfy the claim/lease fence.

CREATE OR REPLACE FUNCTION public.enforce_workflow_sidecar_child_write()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    active BOOLEAN;
BEGIN
    IF current_user <> 'deepresearch_workflow' THEN
        RETURN NEW;
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM public.agent_workflow_run AS workflow_run
        WHERE workflow_run.run_id = NEW.run_id
          AND workflow_run.claim_token = NEW.claim_token
          AND workflow_run.lease_until > pg_catalog.now()
          AND workflow_run.cancel_requested = FALSE
          AND workflow_run.status IN (
              'PLANNING','WORKING','REVIEWING','SYNTHESIZING','FINALIZING'
          )
    ) INTO active;
    IF NOT active THEN
        RAISE EXCEPTION 'stale workflow claim cannot write child records';
    END IF;
    RETURN NEW;
END
$$;

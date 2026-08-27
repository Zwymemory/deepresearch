ALTER TABLE agent_idempotency_record
    ADD COLUMN IF NOT EXISTS claim_id UUID NOT NULL DEFAULT gen_random_uuid();

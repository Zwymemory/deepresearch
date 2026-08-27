ALTER TABLE agent_session
    ADD COLUMN IF NOT EXISTS summary TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS summary_message_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS summary_updated_at TIMESTAMPTZ;

ALTER TABLE user_memory
    ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_user_memory_user_last_used
    ON user_memory (user_id, last_used_at DESC NULLS LAST);

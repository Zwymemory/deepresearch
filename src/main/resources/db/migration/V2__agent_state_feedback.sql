CREATE TABLE IF NOT EXISTS agent_session (
    session_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL DEFAULT 'default',
    title TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_session_user_updated
    ON agent_session (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS agent_run (
    run_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64),
    user_id VARCHAR(64) NOT NULL DEFAULT 'default',
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    rounds INTEGER NOT NULL,
    finished BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_run_session
        FOREIGN KEY (session_id) REFERENCES agent_session(session_id)
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_run_session_created
    ON agent_run (session_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_message (
    message_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64),
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_message_session
        FOREIGN KEY (session_id) REFERENCES agent_session(session_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_agent_message_run
        FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_message_session_created
    ON agent_message (session_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_step (
    run_id VARCHAR(64) NOT NULL,
    round INTEGER NOT NULL,
    thought TEXT,
    action TEXT,
    action_input TEXT,
    observation TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, round),
    CONSTRAINT fk_agent_step_run
        FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS agent_event (
    run_id VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    type VARCHAR(64) NOT NULL,
    message TEXT NOT NULL,
    round INTEGER,
    action TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq),
    CONSTRAINT fk_agent_event_run
        FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS agent_feedback (
    feedback_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    rating VARCHAR(16) NOT NULL,
    reason TEXT,
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_feedback_run
        FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_agent_feedback_rating_created
    ON agent_feedback (rating, created_at DESC);

CREATE TABLE IF NOT EXISTS user_memory (
    memory_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL DEFAULT 'default',
    memory_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    source TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_memory_user_updated
    ON user_memory (user_id, updated_at DESC);

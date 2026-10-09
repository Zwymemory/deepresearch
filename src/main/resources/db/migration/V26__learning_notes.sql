-- Topic notes point directly to original runs; they do not recursively summarize snapshots.
CREATE TABLE research_learning_topic (
    topic_id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL REFERENCES research_project(project_id),
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    title TEXT NOT NULL,
    correction TEXT NOT NULL DEFAULT '',
    revision BIGINT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE research_learning_entry (
    run_id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    topic_id VARCHAR(64) NOT NULL REFERENCES research_learning_topic(topic_id),
    source_sha256 VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    source_created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (run_id,project_id,tenant_id,owner_id)
        REFERENCES agent_research_run(run_id,project_id,tenant_id,owner_id) ON DELETE CASCADE
);
CREATE INDEX research_learning_project_entries ON research_learning_entry(project_id,source_created_at DESC);

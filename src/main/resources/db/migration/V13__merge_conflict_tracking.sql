-- Merge conflict tracking for analytics and planner feedback loop
CREATE TABLE IF NOT EXISTS merge_conflict (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(36)     NOT NULL,
    task_id         VARCHAR(64)     NOT NULL,
    file_path       VARCHAR(500)    NOT NULL,
    conflict_type   VARCHAR(32)     NOT NULL,
    resolution      VARCHAR(32)     NOT NULL,
    resolver_model  VARCHAR(64),
    resolver_tokens_in  INTEGER,
    resolver_tokens_out INTEGER,
    resolved_at     TIMESTAMP,
    created_at      TIMESTAMP       DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mc_run_id ON merge_conflict(run_id);
CREATE INDEX IF NOT EXISTS idx_mc_file_path ON merge_conflict(file_path);
CREATE INDEX IF NOT EXISTS idx_mc_created_at ON merge_conflict(created_at);

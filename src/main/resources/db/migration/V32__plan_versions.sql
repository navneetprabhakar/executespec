CREATE TABLE plan_version (
    id              VARCHAR(36) PRIMARY KEY,
    run_id          VARCHAR(36) NOT NULL REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    version         INT NOT NULL,
    plan_json       TEXT NOT NULL,
    source          VARCHAR(30) NOT NULL,
    created_by      VARCHAR(36),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE(run_id, version)
);
CREATE INDEX idx_plan_version_run ON plan_version(run_id);

ALTER TABLE pipeline_run ADD COLUMN plan_version INT NOT NULL DEFAULT 1;

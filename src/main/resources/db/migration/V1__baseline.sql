-- V1__baseline.sql
-- Baseline schema for all 14 JPA entities.
-- Uses CREATE TABLE IF NOT EXISTS so it is safe against existing databases.

-- ── pipeline_run ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS pipeline_run (
    run_id                VARCHAR(255)    PRIMARY KEY,
    user_request          TEXT,
    status                VARCHAR(64),
    plan_json             TEXT,
    review_json           TEXT,
    planner_model         VARCHAR(255),
    worker_model          VARCHAR(255),
    reviewer_model        VARCHAR(255),
    source_type           VARCHAR(64),
    source_id             VARCHAR(255),
    started_at            TIMESTAMP WITH TIME ZONE,
    completed_at          TIMESTAMP WITH TIME ZONE,
    total_input_tokens    INTEGER,
    total_output_tokens   INTEGER,
    failure_reason        TEXT,
    repo_url              VARCHAR(2048),
    repo_mode             VARCHAR(32),
    workspace_path        VARCHAR(2048),
    queue_position        INTEGER,
    current_gate          VARCHAR(64),
    skip_approval         BOOLEAN         NOT NULL DEFAULT FALSE,
    auto_restart_count    INTEGER         NOT NULL DEFAULT 0,
    has_frontend          BOOLEAN         NOT NULL DEFAULT FALSE,
    has_api_spec          BOOLEAN         NOT NULL DEFAULT FALSE,
    preview_status        VARCHAR(64),
    preview_serve_dir     VARCHAR(2048)
);

-- ── agent_task ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent_task (
    task_id               VARCHAR(255)    PRIMARY KEY,
    run_id                VARCHAR(255),
    title                 VARCHAR(1024),
    complexity            VARCHAR(32),
    status                VARCHAR(64),
    task_json             TEXT,
    worker_report_json    TEXT,
    assigned_model        VARCHAR(255),
    branch_name           VARCHAR(512),
    started_at            TIMESTAMP WITH TIME ZONE,
    completed_at          TIMESTAMP WITH TIME ZONE,
    duration_ms           BIGINT,
    input_tokens          INTEGER,
    output_tokens         INTEGER,
    compaction_count      INTEGER,
    last_checkpoint_path  TEXT,
    readme_updated        BOOLEAN,
    provider              VARCHAR(64),
    model                 VARCHAR(255),
    last_activity_at      TIMESTAMP WITH TIME ZONE,
    stuck_detected_at     TIMESTAMP WITH TIME ZONE,
    recovery_attempt      INTEGER,
    current_provider      VARCHAR(64),
    current_model         VARCHAR(255),
    last_llm_failure      VARCHAR(255)
);

-- ── agent_message ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent_message (
    message_id            VARCHAR(255)    PRIMARY KEY,
    run_id                VARCHAR(255),
    from_agent            VARCHAR(255),
    to_agent              VARCHAR(255),
    message_type          VARCHAR(64),
    content_summary       TEXT,
    sent_at               TIMESTAMP WITH TIME ZONE,
    token_cost            INTEGER,
    file_lock_conflict    BOOLEAN,
    task_id               VARCHAR(255)
);

-- ── file_lock ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS file_lock (
    file_path             VARCHAR(2048)   PRIMARY KEY,
    run_id                VARCHAR(255),
    task_id               VARCHAR(255),
    claimed_at            TIMESTAMP WITH TIME ZONE
);

-- ── agent_inbox ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent_inbox (
    message_id            VARCHAR(255)    PRIMARY KEY,
    run_id                VARCHAR(255),
    to_agent              VARCHAR(255),
    from_agent            VARCHAR(255),
    message_type          VARCHAR(64),
    content               TEXT,
    delivered             BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMP WITH TIME ZONE,
    delivered_at          TIMESTAMP WITH TIME ZONE,
    from_provider         VARCHAR(64),
    to_provider           VARCHAR(64)
);

-- ── gate_event ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS gate_event (
    id                    VARCHAR(255)    PRIMARY KEY,
    run_id                VARCHAR(255),
    gate_type             VARCHAR(64),
    opened_at             TIMESTAMP WITH TIME ZONE,
    resolved_at           TIMESTAMP WITH TIME ZONE,
    approved              BOOLEAN
);

-- ── compaction_event ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS compaction_event (
    id                    VARCHAR(255)    PRIMARY KEY,
    run_id                VARCHAR(255),
    task_id               VARCHAR(255),
    occurred_at           TIMESTAMP WITH TIME ZONE,
    tokens_before         INTEGER,
    tokens_after          INTEGER
);

-- ── run_recovery ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS run_recovery (
    id                    VARCHAR(255)    PRIMARY KEY,
    original_run_id       VARCHAR(255)    NOT NULL,
    restart_run_id        VARCHAR(255),
    stuck_task_id         VARCHAR(255),
    classification        VARCHAR(64)     NOT NULL,
    classification_detail TEXT,
    action_taken          VARCHAR(64)     NOT NULL,
    original_goal         TEXT            NOT NULL,
    recovered_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    restart_created_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_recovery_original ON run_recovery (original_run_id);
CREATE INDEX IF NOT EXISTS idx_recovery_restart  ON run_recovery (restart_run_id);

-- ── prompt_audit_entry ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS prompt_audit_entry (
    id                    VARCHAR(255)    PRIMARY KEY,
    prompt_file           VARCHAR(512)    NOT NULL,
    version               INTEGER         NOT NULL,
    previous_content      TEXT            NOT NULL,
    new_content           TEXT            NOT NULL,
    diff_summary          TEXT            NOT NULL,
    patterns_matched      TEXT            NOT NULL,
    source_run_ids        TEXT            NOT NULL,
    trigger_type          VARCHAR(64)     NOT NULL,
    applied_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    runs_after            INTEGER         NOT NULL DEFAULT 0,
    failures_after        INTEGER         NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_audit_prompt  ON prompt_audit_entry (prompt_file, version);
CREATE INDEX IF NOT EXISTS idx_audit_applied ON prompt_audit_entry (applied_at);

-- ── run_repo ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS run_repo (
    id                    VARCHAR(255)    PRIMARY KEY,
    run_id                VARCHAR(255)    NOT NULL,
    repo_url              VARCHAR(2048)   NOT NULL,
    repo_owner            VARCHAR(255)    NOT NULL,
    repo_name             VARCHAR(255)    NOT NULL,
    repo_mode             VARCHAR(32)     NOT NULL,
    branch                VARCHAR(255)    NOT NULL DEFAULT 'main',
    created_at            VARCHAR(255)    NOT NULL,
    pushed_at             VARCHAR(255)
);

-- ── deployment ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS deployment (
    id                    VARCHAR(255)    PRIMARY KEY,
    run_id                VARCHAR(255)    NOT NULL,
    run_repo_id           VARCHAR(255),
    version_tag           VARCHAR(255)    NOT NULL,
    strategy              VARCHAR(32)     NOT NULL,
    image_uri             VARCHAR(2048)   NOT NULL,
    service_name          VARCHAR(255)    NOT NULL,
    revision_name         VARCHAR(255),
    service_url           VARCHAR(2048),
    status                VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    gcp_project           VARCHAR(255)    NOT NULL,
    gcp_region            VARCHAR(64)     NOT NULL,
    estimated_cost_usd    DOUBLE PRECISION,
    actual_cost_usd       DOUBLE PRECISION,
    error_message         VARCHAR(2048),
    dockerfile_path       VARCHAR(2048),
    build_log             VARCHAR(8000),
    ttl_expires_at        VARCHAR(255)    NOT NULL,
    created_at            VARCHAR(255)    NOT NULL,
    deployed_at           VARCHAR(255),
    deleted_at            VARCHAR(255)
);

-- ── integration_run ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS integration_run (
    id                    BIGSERIAL       PRIMARY KEY,
    run_id                VARCHAR(255)    NOT NULL,
    source_type           VARCHAR(64)     NOT NULL,
    source_id             VARCHAR(255)    NOT NULL,
    source_url            VARCHAR(2048),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_integration_run_run_id ON integration_run (run_id);

-- ── integration_event_log ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS integration_event_log (
    id                    BIGSERIAL       PRIMARY KEY,
    source_type           VARCHAR(64)     NOT NULL,
    event_id              VARCHAR(255)    NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_integration_event_log_source_event UNIQUE (source_type, event_id)
);

CREATE INDEX IF NOT EXISTS idx_integration_event_log_lookup ON integration_event_log (source_type, event_id);

-- ── model_pricing ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS model_pricing (
    provider              VARCHAR(255)    NOT NULL,
    model                 VARCHAR(255)    NOT NULL,
    input_cost_per1m      DOUBLE PRECISION NOT NULL,
    output_cost_per1m     DOUBLE PRECISION NOT NULL,
    notes                 TEXT,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (provider, model)
);

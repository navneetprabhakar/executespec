-- Preview registry: source of truth for VM-based preview deployments.
-- VM-local previews.json is a cache; this table is authoritative.

CREATE TABLE preview_registry (
    id                VARCHAR(64)  PRIMARY KEY,
    execution_id      VARCHAR(64)  NOT NULL,
    project_name      VARCHAR(255),
    url               VARCHAR(512) NOT NULL,
    vm_id             VARCHAR(64)  NOT NULL,
    vm_ip             VARCHAR(45)  NOT NULL,
    port              INTEGER      NOT NULL DEFAULT 0,
    status            VARCHAR(20)  NOT NULL DEFAULT 'deploying',
    healthy           BOOLEAN      DEFAULT FALSE,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMP    NOT NULL,
    torn_down_at      TIMESTAMP,
    ttl_hours         INTEGER      NOT NULL DEFAULT 2,
    deploy_duration_s INTEGER,
    error_message     TEXT
);

CREATE INDEX idx_preview_registry_status ON preview_registry (status);
CREATE INDEX idx_preview_registry_vm_id ON preview_registry (vm_id);
CREATE INDEX idx_preview_registry_expires_at ON preview_registry (expires_at);
CREATE INDEX idx_preview_registry_execution_id ON preview_registry (execution_id);

CREATE TABLE preview_metrics (
    id              BIGSERIAL    PRIMARY KEY,
    event_type      VARCHAR(30)  NOT NULL,
    preview_id      VARCHAR(64),
    vm_id           VARCHAR(64),
    message         TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_preview_metrics_event_type ON preview_metrics (event_type);
CREATE INDEX idx_preview_metrics_created_at ON preview_metrics (created_at);

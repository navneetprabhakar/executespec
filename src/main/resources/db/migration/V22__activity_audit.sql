-- Activity audit trail for all user management events
CREATE TABLE activity_audit (
    id          VARCHAR(36)  PRIMARY KEY,
    event_type  VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(32)  NOT NULL,
    entity_id   VARCHAR(255) NOT NULL,
    org_id      VARCHAR(255),
    actor_id    VARCHAR(255),
    old_value   TEXT,
    new_value   TEXT,
    metadata    TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_activity_audit_org_time     ON activity_audit (org_id, created_at DESC);
CREATE INDEX idx_activity_audit_entity       ON activity_audit (entity_type, entity_id);
CREATE INDEX idx_activity_audit_actor        ON activity_audit (actor_id, created_at DESC);
CREATE INDEX idx_activity_audit_event_type   ON activity_audit (event_type);

-- V65: gate_record — durable persistence for approval gate decisions
-- Dual-written alongside Redis so a DB restart does not lose gate state.
-- Redis remains the primary signal path; DB is the fallback on Redis unavailability.

CREATE TABLE gate_record (
    gate_key      VARCHAR(255)  PRIMARY KEY,
    run_id        VARCHAR(255)  NOT NULL,
    gate_type     VARCHAR(30)   NOT NULL,   -- PLAN, MERGE, CLARIFICATION
    status        VARCHAR(30)   NOT NULL DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED, or MergeDecision name
    comment       TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ
);

CREATE INDEX idx_gate_record_run_id   ON gate_record (run_id);
CREATE INDEX idx_gate_record_pending  ON gate_record (status) WHERE resolved_at IS NULL;

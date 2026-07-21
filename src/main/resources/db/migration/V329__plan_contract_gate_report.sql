-- V329 (RR-819) — durable plan-approval contract gate report + typed acknowledgement.
--
-- Before this table, the "user must type BREAKING to acknowledge a consumer-breaking contract
-- change" requirement existed only as javadoc on PlanBreakingChangeReport.requiresTypedAck and as a
-- "[REQUIRES ACK]" suffix inside a MINOR run issue produced AFTER the worker had already written
-- the change. No API, frontend or CLI consumer of requiresTypedAck existed, so the mandatory
-- friction was not a gate: a consumer-breaking API/schema change could be approved and published
-- with no acknowledgement and no record that one was owed.
--
-- This row is written while the run sits at AWAITING_APPROVAL and is the server's own record of
-- what the approver was shown. report_digest binds the acknowledgement to that exact content, so an
-- acknowledgement collected against one set of contracts cannot be replayed against another.

CREATE TABLE IF NOT EXISTS plan_contract_gate_report (
    run_id                  VARCHAR(64)  PRIMARY KEY,
    project_id              VARCHAR(64),
    report_json             TEXT         NOT NULL,
    report_digest           VARCHAR(64)  NOT NULL,
    requires_typed_ack      BOOLEAN      NOT NULL DEFAULT FALSE,
    contract_count          INTEGER      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    acknowledged_at         TIMESTAMPTZ,
    acknowledged_by_user_id VARCHAR(64),
    acknowledgement_text    VARCHAR(64),

    -- An acknowledgement is a three-field fact or it is nothing. A half-written row must not be
    -- readable as consent.
    CONSTRAINT plan_contract_gate_ack_complete CHECK (
        (acknowledged_at IS NULL AND acknowledged_by_user_id IS NULL
             AND acknowledgement_text IS NULL)
        OR (acknowledged_at IS NOT NULL AND acknowledged_by_user_id IS NOT NULL
             AND acknowledgement_text IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_plan_contract_gate_project
    ON plan_contract_gate_report (project_id, created_at DESC);

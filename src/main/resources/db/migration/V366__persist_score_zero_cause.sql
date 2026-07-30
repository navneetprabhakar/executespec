-- Persist the exact deterministic gate(s) that caused a new validation headline to be zero.
-- Historical rows remain NULL and are presented as unattributed rather than reverse-inferred.
ALTER TABLE validation_report
    ADD COLUMN score_zero_cause_json JSONB;

COMMENT ON COLUMN validation_report.score_zero_cause_json IS
    'Schema-versioned deterministic scoring gates captured when verified_score is generated as zero; NULL means nonzero or historical/unattributed.';

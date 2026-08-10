-- Freeze the initial acceptance probe, including unavailable/green outcomes, before a BUILD call.
ALTER TABLE workspace_generation_attempt
    ADD COLUMN observed_initial_failure_evidence_json JSONB,
    ADD COLUMN initial_failure_observed_at TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION preserve_observed_initial_build_failure()
RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.observed_initial_failure_evidence_json IS NULL)
        <> (NEW.initial_failure_observed_at IS NULL) THEN
        RAISE EXCEPTION 'workspace generation observed initial failure fields must be written together';
    END IF;
    IF (OLD.observed_initial_failure_evidence_json IS NOT NULL
        OR OLD.initial_failure_observed_at IS NOT NULL) AND (
        NEW.observed_initial_failure_evidence_json IS DISTINCT FROM OLD.observed_initial_failure_evidence_json
        OR NEW.initial_failure_observed_at IS DISTINCT FROM OLD.initial_failure_observed_at
    ) THEN
        RAISE EXCEPTION 'workspace generation observed initial failure is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_observed_initial_failure_immutable
BEFORE UPDATE OF observed_initial_failure_evidence_json, initial_failure_observed_at
ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION preserve_observed_initial_build_failure();

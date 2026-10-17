ALTER TABLE pipeline_run ADD COLUMN build_execution_policy_json JSONB;

CREATE FUNCTION preserve_build_execution_policy() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.build_execution_policy_json IS NOT NULL
       AND NEW.build_execution_policy_json IS DISTINCT FROM OLD.build_execution_policy_json THEN
        RAISE EXCEPTION 'BUILD execution policy is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pipeline_run_build_execution_policy_immutable
BEFORE UPDATE OF build_execution_policy_json ON pipeline_run
FOR EACH ROW EXECUTE FUNCTION preserve_build_execution_policy();

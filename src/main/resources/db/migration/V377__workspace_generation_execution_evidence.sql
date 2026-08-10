-- Additive, customer-inspectable BUILD attempt evidence. Historical V374/V375 rows remain valid.
ALTER TABLE workspace_generation_attempt
    ADD COLUMN task_contract_json JSONB,
    ADD COLUMN initial_failure_evidence_json JSONB,
    ADD COLUMN requested_provider VARCHAR(32),
    ADD COLUMN requested_model VARCHAR(160),
    ADD COLUMN effective_provider VARCHAR(32),
    ADD COLUMN effective_model VARCHAR(160),
    ADD COLUMN worker_iterations INTEGER,
    ADD COLUMN worker_tool_calls INTEGER,
    ADD COLUMN model_calls BIGINT,
    ADD COLUMN input_tokens BIGINT,
    ADD COLUMN output_tokens BIGINT,
    ADD COLUMN cache_read_tokens BIGINT,
    ADD COLUMN cache_write_tokens BIGINT,
    ADD COLUMN reasoning_tokens BIGINT,
    ADD COLUMN cost_usd NUMERIC(16, 8),
    ADD CONSTRAINT workspace_generation_execution_counts_chk CHECK (
        (worker_iterations IS NULL OR worker_iterations >= 0)
        AND (worker_tool_calls IS NULL OR worker_tool_calls >= 0)
        AND (model_calls IS NULL OR model_calls >= 0)
        AND (input_tokens IS NULL OR input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0)
        AND (cache_read_tokens IS NULL OR cache_read_tokens >= 0)
        AND (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
        AND (reasoning_tokens IS NULL OR reasoning_tokens >= 0)
        AND (cost_usd IS NULL OR cost_usd >= 0)
    );

CREATE OR REPLACE FUNCTION preserve_workspace_generation_execution_evidence()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.task_contract_json IS NOT NULL
        AND NEW.task_contract_json IS DISTINCT FROM OLD.task_contract_json THEN
        RAISE EXCEPTION 'workspace generation task contract is immutable';
    END IF;
    IF OLD.initial_failure_evidence_json IS NOT NULL
        AND NEW.initial_failure_evidence_json IS DISTINCT FROM OLD.initial_failure_evidence_json THEN
        RAISE EXCEPTION 'workspace generation initial failure evidence is immutable';
    END IF;
    IF OLD.requested_provider IS NOT NULL AND (
        NEW.requested_provider IS DISTINCT FROM OLD.requested_provider
        OR NEW.requested_model IS DISTINCT FROM OLD.requested_model
    ) THEN
        RAISE EXCEPTION 'workspace generation requested route is immutable';
    END IF;
    IF OLD.effective_provider IS NOT NULL AND (
        NEW.effective_provider IS DISTINCT FROM OLD.effective_provider
        OR NEW.effective_model IS DISTINCT FROM OLD.effective_model
        OR NEW.worker_iterations IS DISTINCT FROM OLD.worker_iterations
        OR NEW.worker_tool_calls IS DISTINCT FROM OLD.worker_tool_calls
        OR NEW.model_calls IS DISTINCT FROM OLD.model_calls
        OR NEW.input_tokens IS DISTINCT FROM OLD.input_tokens
        OR NEW.output_tokens IS DISTINCT FROM OLD.output_tokens
        OR NEW.cache_read_tokens IS DISTINCT FROM OLD.cache_read_tokens
        OR NEW.cache_write_tokens IS DISTINCT FROM OLD.cache_write_tokens
        OR NEW.reasoning_tokens IS DISTINCT FROM OLD.reasoning_tokens
        OR NEW.cost_usd IS DISTINCT FROM OLD.cost_usd
    ) THEN
        RAISE EXCEPTION 'workspace generation execution evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_execution_evidence_immutable
BEFORE UPDATE OF task_contract_json, initial_failure_evidence_json,
    requested_provider, requested_model, effective_provider, effective_model,
    worker_iterations, worker_tool_calls, model_calls, input_tokens, output_tokens,
    cache_read_tokens, cache_write_tokens, reasoning_tokens, cost_usd
ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION preserve_workspace_generation_execution_evidence();

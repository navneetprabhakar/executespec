-- Preserve the exact existing branch selected for a run across plan approval, pause, and crash
-- recovery. Null keeps the ordinary generated-branch + new-PR publication contract.
ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS branch_override VARCHAR(200),
    ADD COLUMN IF NOT EXISTS checkout_branch VARCHAR(200),
    ADD COLUMN IF NOT EXISTS publication_repo_full_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS publication_pr_number INTEGER,
    ADD COLUMN IF NOT EXISTS publication_head_sha VARCHAR(40);

ALTER TABLE pipeline_run
    ADD CONSTRAINT chk_pipeline_run_publication_pr_identity
    CHECK (
        (branch_override IS NULL AND publication_repo_full_name IS NULL
            AND publication_pr_number IS NULL AND publication_head_sha IS NULL)
        OR
        (branch_override IS NOT NULL AND checkout_branch IS NOT NULL
            AND checkout_branch = branch_override
            AND publication_repo_full_name IS NOT NULL AND publication_pr_number > 0
            AND publication_head_sha ~ '^[0-9a-fA-F]{40}$')
    );

CREATE OR REPLACE FUNCTION prevent_pipeline_run_branch_intent_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.branch_override IS DISTINCT FROM NEW.branch_override
       OR OLD.checkout_branch IS DISTINCT FROM NEW.checkout_branch
       OR OLD.publication_repo_full_name IS DISTINCT FROM NEW.publication_repo_full_name
       OR OLD.publication_pr_number IS DISTINCT FROM NEW.publication_pr_number
       OR OLD.publication_head_sha IS DISTINCT FROM NEW.publication_head_sha THEN
        RAISE EXCEPTION 'pipeline_run branch and publication intent is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pipeline_run_branch_intent_immutable
    BEFORE UPDATE ON pipeline_run
    FOR EACH ROW
    EXECUTE FUNCTION prevent_pipeline_run_branch_intent_update();

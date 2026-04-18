-- Security Guardrail Level — three-level runtime-switchable posture.
--
-- See docs/plans/guardrail-levels-execution-plan.md. This migration is P1
-- scaffolding only: seeds the feature-flag row. Subsequent PRs wire
-- InputSanitizer, CodeAbuseScanner, RateLimitFilter, ShellSandboxPolicy,
-- and FileSandboxPolicy to read the flag. Until those land, the flag is
-- a diagnostic that reports LOW (current behaviour everywhere).
--
-- Values: LOW | MEDIUM | HIGH. Default LOW keeps current behaviour
-- unchanged (zero-risk rollout). Operators flip via the existing
-- FeatureFlagController admin UI.

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('security.guardrail_level', 'LOW', 'STRING', 'PLATFORM',
     'Three-level security posture (LOW | MEDIUM | HIGH). LOW is today''s behaviour; MEDIUM/HIGH enforce additional rejection in InputSanitizer, CodeAbuseScanner, ShellSandboxPolicy, FileSandboxPolicy, and RateLimitFilter.')
ON CONFLICT (flag_key) DO NOTHING;

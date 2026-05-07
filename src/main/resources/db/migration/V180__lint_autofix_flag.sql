-- 2026-05-07 — Phase L1: deterministic auto-formatter pass between executor and reviewer.
--
-- Default ON because:
--   1. The pass is no-op when no formatter is detected (workspace has no package.json /
--      pom.xml / pyproject.toml / go.mod / Cargo.toml / build.gradle*).
--   2. Per-tool failures (binary missing, syntax error in source, etc.) are caught by LintPass
--      and logged at WARN — the run never aborts.
--   3. The win is a ~3K-token-per-task drop on reviewer input + cleaner diffs in the PR. Both
--      are unambiguously good for every run.
--
-- Ops can flip this OFF per-environment via FeatureFlagController admin UI if a host turns out
-- to have a pathological formatter config that takes minutes (the 5-minute per-tool timeout +
-- 10-minute total budget already cap that, but the flag is the cleaner kill switch).

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('lint.autofix.enabled', 'true', 'BOOLEAN', 'PLATFORM',
     'Run deterministic formatters (prettier --write, mvn spotless:apply, black, gofmt -w, cargo fmt) against the workspace between executor and reviewer. Cuts ~3K tokens per task off reviewer input by absorbing trailing-comma / indent / import-order drift up-front. Per-tool 5-min timeout + 10-min total budget; failures non-fatal.')
ON CONFLICT (flag_key) DO NOTHING;

-- =====================================================================
-- V255: Seed the harness-resolution Phase 1-3 feature flags, ALL default OFF.
--
-- Source: docs/plans/executespec-codegen-harness-adversarial-assessment-2026-07-04.md
-- (resolution path) + infra/benchmark/HARNESS-RESOLUTION-LOG-2026-07-04.md (§3 flag
-- triage — every flag has a graduation criterion + expiry; nothing flips ON in prod
-- without passing its k>=3 gate vs the Phase-0 baseline).
--
-- worker.progress-v2.enabled — evidence-based worker progress engine: novelty-based
--   progress (a NEW (tool,args) signature counts; repetition does not), repetition-based
--   force (same signature failing 5x consecutively), PATCH pre-edit exploration ceiling
--   (total-iteration, replaces the 6-non-write-iteration nudge), bounded
--   tool_choice=required retries on text-only stops (replaces the 2-strike kill), and a
--   v2 nudge without the "an imperfect edit beats zero" directive.
--
-- worker.lean-toolset.enabled — bind ONLY the canonical core toolset to server-side
--   workers (compat/alias tools stay for local-hands sessions). Kills the duplicated
--   tool surface (readFile+read_file, shell+bash, ...) that splits model behavior,
--   doubles schema tokens, and feeds the RL-2026-05-061 schema-stall class.
--
-- worker.auto-verify.enabled — server-side edit-burst test execution with failure-output
--   injection (no model-visible schema change) + VBC refusals upgraded to carry failing
--   test detail. Supersedes build.test-exec-gate.enabled (V209) on graduation.
--
-- worker.localization.enabled — pre-task repo-orientation step (deterministic tree/grep
--   layer + bounded LIGHTWEIGHT read-only localization call) injected as an advisory
--   block in the worker's first user message.
--
-- execution.run-journal.enabled — rolling per-task journal (summary + files touched)
--   plus plan outline injected into subsequent task prompts; bounded once-per-run
--   re-plan on structural failure.
--
-- All five: OFF = byte-identical behavior to pre-V255.
-- =====================================================================
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES
    ('worker.progress-v2.enabled', 'false', 'BOOLEAN', 'GLOBAL',
     'Harness-resolution Phase 1: evidence-based worker progress (novelty progress, repetition force, exploration ceiling, bounded text-only retries). Default OFF; k>=3 gate before flipping.'),
    ('worker.lean-toolset.enabled', 'false', 'BOOLEAN', 'GLOBAL',
     'Harness-resolution Phase 1: bind only the canonical core toolset to server-side workers; compat aliases remain for local-hands sessions. Default OFF; k>=3 gate before flipping.'),
    ('worker.auto-verify.enabled', 'false', 'BOOLEAN', 'GLOBAL',
     'Harness-resolution Phase 2: server-side edit-burst test execution with failure-output injection + detailed VBC refusals (schema-free; supersedes build.test-exec-gate.enabled on graduation). Default OFF; k>=3 gate before flipping.'),
    ('worker.localization.enabled', 'false', 'BOOLEAN', 'GLOBAL',
     'Harness-resolution Phase 3: pre-task repo-orientation (deterministic layer + bounded LIGHTWEIGHT localization call) injected as an advisory block. Default OFF; k>=3 gate before flipping.'),
    ('execution.run-journal.enabled', 'false', 'BOOLEAN', 'GLOBAL',
     'Harness-resolution Phase 3: rolling run journal + plan outline in task prompts; bounded once-per-run re-plan on structural failure. Default OFF; k>=3 gate before flipping.')
ON CONFLICT (flag_key) DO UPDATE
    SET description = EXCLUDED.description;

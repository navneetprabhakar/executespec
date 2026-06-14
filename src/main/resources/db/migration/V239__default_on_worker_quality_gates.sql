-- =====================================================================
-- V239: Default-ON for the evidence-safe worker quality gates (RL-2026-06).
--
-- Founder decision 2026-06-11 (after the TB 2.0 full-89 failure autopsy): the
-- self-reported-completion class (worker calls completeTask, deliverable wrong
-- or absent) is the dominant fixable failure mode, and the safe half of the
-- "flag graveyard" graduates to default ON. Every flag below is bounded +
-- fail-open + carries NO model-visible schema change (immune by construction
-- to the RL-2026-05-061 schema-stall class that reverted V217/V209).
--
--   * worker.verify-before-complete.enabled (V235, VBC) — server-RUNS the
--     project's own tests at the first completeTask, refuses if red. The
--     never-trust-the-model's-own-word gate. Remote/TB runs fail-open.
--   * worker.edit-diagnostics.enabled (V232, B1) — post-edit py_compile;
--     closes the edit→"what you broke" loop. Bounded + fail-open.
--   * worker.salvage-force-tool-choice.enabled (V224) — the Lever-B salvage
--     forces a tool call; only runs on already-failing zero-write runs, so it
--     cannot regress a healthy run (4/10 zero-edit salvage failures observed
--     cross-provider on the 2026-05-30 mixed10).
--   * worker.critic-pass.enabled (V231) — PATCH critic: one fresh-context
--     acceptance review before the first completeTask. TB-validated
--     4/13 → 8/13 (pass@2), 0 regressions.
--   * codeintel.inject-arch.enabled (V228) — feed the project's existing
--     cross-repo architecture doc to the planner (bounded ~8k, fail-safe on
--     greenfield/bench). It was generated but never consumed by a run.
--
-- Deliberately NOT flipped here (each has a documented blocker):
--   * V217 worker.build-tdd + V209 build.test-exec-gate — KNOWN regression:
--     flipped ON 2026-05-31, REVERTED 2026-06-01 (RL-2026-05-061) after the
--     runWorkspaceTests tool in the schema caused reasoning-only no-edit
--     stalls on native DeepSeek. Needs the owed cross-provider eval first.
--   * V233 worker.background-jobs — explicit pre-flip TODOs unfinished
--     (run-end JobRegistry.cleanup wiring + container/remote routing).
--   * V234 worker.code-outline — binds a NEW tool into the model schema (the
--     exact RL-2026-05-061 sensitivity class); needs eval.
--   * V216/V222 compaction + V221 retry-context — change worker context
--     behavior; graduation candidates for the next eval round.
--   * V220 cross-provider fallback + V226 router failover — mid-run provider
--     switches contaminate per-model benchmark attribution; revisit after the
--     TB 2.1 campaign (V226 stays a strong product-reliability candidate).
--   * V227 verify-boot/buildpack — inert until preview Phase 0 lands.
-- =====================================================================
UPDATE feature_flag
SET flag_value = 'true'
WHERE flag_key IN (
    'worker.verify-before-complete.enabled',
    'worker.edit-diagnostics.enabled',
    'worker.salvage-force-tool-choice.enabled',
    'worker.critic-pass.enabled',
    'codeintel.inject-arch.enabled'
);

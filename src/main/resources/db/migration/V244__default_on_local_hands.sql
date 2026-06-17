-- =====================================================================
-- V244: Default-ON for CLI local-hands execution (worker.local-hands.enabled).
--
-- Product decision 2026-06-17: the CLI makes LOCAL execution the default and
-- requires the server path to be explicit. Bare `executespec run "<spec>"` now
-- routes to local-hands — the server brain plans and every worker tool call is
-- proxied back to the client working tree (nothing cloned or pushed server-side)
-- — while remote execution is the explicit `run server "<spec>"` path.
--
-- For that default to function end-to-end the submit gate
-- (RunController: localExecutionRequested && worker.local-hands.enabled, V241)
-- must ACCEPT local mode. With the flag OFF it fail-closes localExecution=true
-- with HTTP 409 before quota/billing, and the CLI falls back to guiding the user
-- to `run server`. Flip it ON so local is genuinely the default, not a guide.
--
-- Safe + reversible: the transport is the same remote-tool session shipped in
-- RL-2026-06-014; this only stops the 409 pre-rejection. Toggle back anytime via
-- the feature-flag console (PLATFORM scope).
-- =====================================================================
UPDATE feature_flag
SET flag_value = 'true'
WHERE flag_key = 'worker.local-hands.enabled';

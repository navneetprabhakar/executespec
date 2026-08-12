-- Crash recovery must cover both sides of the external-dispatch boundary.
-- V389 indexed only DISPATCHED commands; a JVM killed after durable ADMITTED but before the
-- DISPATCHED write left an intent that no startup sweep could ever settle. Keep the V389 index for
-- rolling-binary compatibility and add a head migration that supports the combined ordered sweep.

CREATE INDEX idx_sandbox_command_unsettled_recovery
    ON sandbox_command_execution(state, COALESCE(dispatched_at, admitted_at), id)
    WHERE state IN ('ADMITTED', 'DISPATCHED');

COMMENT ON INDEX idx_sandbox_command_unsettled_recovery IS
    'Supports conservative startup recovery: stale ADMITTED becomes UNAVAILABLE; stale DISPATCHED becomes INDETERMINATE.';

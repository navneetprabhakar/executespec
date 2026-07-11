-- Graduate the bounded server-owned edit-burst verification gate.
--
-- V255 seeded this flag OFF while the implementation was characterized.  The gate is now
-- fail-safe (unwired/no-runner/remote/zero-test/error paths do not block completion), bounded per
-- task, and covered by WorkerAgentAutoVerifyTest, so new and existing installations use it by
-- default.  Operators can still disable it explicitly through the feature-flag surface.
UPDATE feature_flag
SET flag_value = 'true',
    description = 'Graduated server-side edit-burst test execution with structured failure injection; bounded and fail-safe when no runner is available.'
WHERE flag_key = 'worker.auto-verify.enabled';

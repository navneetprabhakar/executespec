-- =====================================================================
-- V173 — widen created_by_user_id columns on the review tables.
--
-- V172 declared pr_review.created_by_user_id and
-- code_review_scan.created_by_user_id as VARCHAR(36), inheriting the
-- shape from the legacy run-id convention. That breaks for any user
-- whose id has a non-UUID prefix (e.g. the seeded super-admin
-- 'admin-00000000-0000-0000-0000-000000000001' is 42 chars).
--
-- Other tables that hold an app_user.id reference (V4, V23, V24, V26,
-- V28, V129, V136) standardised on VARCHAR(64)/VARCHAR(255). Widen
-- both review columns to VARCHAR(255) for parity. Same width the
-- resolved_by_user_id column on code_review_issue should also adopt
-- — fixing here too because it has the same data shape.
--
-- Idempotent: ALTER TABLE … ALTER COLUMN … TYPE is safe to re-run on
-- a column that's already at the target width.
-- =====================================================================

ALTER TABLE pr_review
  ALTER COLUMN created_by_user_id TYPE VARCHAR(255);

ALTER TABLE code_review_scan
  ALTER COLUMN created_by_user_id TYPE VARCHAR(255);

ALTER TABLE code_review_issue
  ALTER COLUMN resolved_by_user_id TYPE VARCHAR(255);

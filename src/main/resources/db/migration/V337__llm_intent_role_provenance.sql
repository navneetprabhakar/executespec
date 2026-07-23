-- RR-430 — distinguish a human authorization role captured before a provider attempt from one
-- reconstructed at migration time. V301 backfilled human_role_snapshot for every historical row
-- from membership/global-role rows AS THEY EXISTED at upgrade time, then made the field non-null,
-- with no column recording whether the value was contemporaneous or inferred. Historical audit
-- exports therefore presented reconstructed roles with the same authority as true snapshots.
--
-- Additive, nullable column only. Values: CAPTURED_AT_DISPATCH | BACKFILLED_AT_MIGRATION | UNKNOWN.
-- Legacy rows are left NULL and read as UNKNOWN — we cannot prove a pre-V337 row's role was captured
-- at dispatch rather than reconstructed at V301, and asserting either would recreate the defect.
-- The append-only trigger on this table (V293) is not touched: ADD COLUMN is DDL and does not fire
-- the row-level BEFORE UPDATE/DELETE guard, and no backfill UPDATE is performed.
ALTER TABLE llm_usage_attempt_intent
  ADD COLUMN IF NOT EXISTS role_provenance VARCHAR(40);

COMMENT ON COLUMN llm_usage_attempt_intent.role_provenance IS
  'How human_role_snapshot was obtained: CAPTURED_AT_DISPATCH (recorded before provider dispatch), '
  'BACKFILLED_AT_MIGRATION (reconstructed by a data migration), or UNKNOWN. '
  'NULL legacy rows (pre-V337) are read as UNKNOWN.';

-- V79: retire V1 harness routing flags.
--
-- harness.version + harness.v2.canary_percent were the rollout gate during
-- V1→V2 migration. V2 is now the only harness; the flags no longer influence
-- routing. Delete the feature_flag rows so the admin UI doesn't surface them.
-- The pipeline_run.harness_version column is left in place — it remains a
-- useful audit signal (all new rows will be "v2") and historical V1 rows
-- can still be distinguished.

DELETE FROM feature_flag WHERE flag_key IN ('harness.version', 'harness.v2.canary_percent');

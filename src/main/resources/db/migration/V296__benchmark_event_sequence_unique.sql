-- V296 — serialize benchmark event ordering across parallel harness publishers.
-- The service locks the parent benchmark_run row before allocating seq; this unique constraint
-- remains the database-level backstop against duplicate event sequence values.  Do not silently
-- delete or renumber historical audit events if an unmanaged database already contains a clash:
-- fail the upgrade with a diagnostic so an operator can reconcile the source rows explicitly.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM benchmark_event
        GROUP BY benchmark_run_id, seq
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V296 refused: duplicate benchmark_event (benchmark_run_id, seq) rows exist; reconcile audit history before retrying';
    END IF;
END
$$;

ALTER TABLE benchmark_event
    ADD CONSTRAINT uq_benchmark_event_sequence UNIQUE (benchmark_run_id, seq);

-- E1.10 — Enterprise readiness, Phase E1 baseline (activity foundation bundle)
--
-- Customer-facing project activity feed, partitioned by month on
-- (occurred_at). This is a NEW table — activity_audit (V22) stays as-is,
-- compliance-focused, write-only from the user-management code paths.
--
-- The 3-tier event taxonomy (HIGH_SIGNAL → ACTIVITY, ADMIN_TOGGLE →
-- ACTIVITY w/ admin filter, AUDIT-only → AUDIT) is enforced at the
-- application layer by ActivityEventEmitter, which dual-writes to
-- activity_audit when the event type's surfaces include AUDIT.
--
-- Monthly partitions are added in this migration for 2026-04 through
-- 2027-03 (12 partitions); ActivityFeedPartitionJob runs daily and
-- creates the next month's partition before old ones fill. Retention
-- enforcement (drop partitions older than tier limit) ships in E4.6.
--
-- Reverse migration: drop the parent table (cascades to all partitions
-- via PostgreSQL's PARTITION OF semantics):
--   DROP TABLE IF EXISTS activity_feed CASCADE;

CREATE TABLE IF NOT EXISTS activity_feed (
  id              UUID NOT NULL DEFAULT gen_random_uuid(),
  project_id      UUID NOT NULL,
  occurred_at     TIMESTAMP NOT NULL DEFAULT now(),
  actor_user_id   UUID,
  actor_type      VARCHAR(16) NOT NULL,    -- USER | SYSTEM
  event_type      VARCHAR(64) NOT NULL,
  target_type     VARCHAR(32),             -- RUN | LAUNCH | PR | CONTRACT | ...
  target_id       VARCHAR(128),
  summary         TEXT NOT NULL,
  payload         JSONB,
  schema_version  INTEGER NOT NULL DEFAULT 1,
  created_at      TIMESTAMP NOT NULL DEFAULT now(),
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Initial 12 monthly partitions (2026-04 … 2027-03).
-- ActivityFeedPartitionJob keeps the rolling next-3-months window topped up.
CREATE TABLE IF NOT EXISTS activity_feed_y2026m04 PARTITION OF activity_feed
  FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2026m05 PARTITION OF activity_feed
  FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2026m06 PARTITION OF activity_feed
  FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2026m07 PARTITION OF activity_feed
  FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2026m08 PARTITION OF activity_feed
  FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2026m09 PARTITION OF activity_feed
  FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2026m10 PARTITION OF activity_feed
  FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2026m11 PARTITION OF activity_feed
  FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2026m12 PARTITION OF activity_feed
  FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2027m01 PARTITION OF activity_feed
  FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2027m02 PARTITION OF activity_feed
  FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE IF NOT EXISTS activity_feed_y2027m03 PARTITION OF activity_feed
  FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');

CREATE INDEX IF NOT EXISTS idx_activity_feed_project_time
  ON activity_feed(project_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_activity_feed_event_type
  ON activity_feed(event_type);
CREATE INDEX IF NOT EXISTS idx_activity_feed_target
  ON activity_feed(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_activity_feed_actor
  ON activity_feed(actor_user_id);

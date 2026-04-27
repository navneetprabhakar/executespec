-- M-019 (Wave 3) — append-only hash chain on activity_audit so a
-- forensic auditor can prove no row was tampered or deleted after
-- the fact.
--
-- Each row's row_hash = SHA-256(prev_hash || canonical_row_content),
-- where canonical_row_content is the deterministic concatenation of
-- the immutable fields (id|eventType|entityType|entityId|actorId|
-- orgId|oldValue|newValue|metadata|createdAtIso). The first row's
-- prev_hash is '0' repeated 64 times (genesis).
--
-- chain_seq is a strictly-monotonic counter so the verifier can walk
-- the chain in deterministic order without sorting on createdAt
-- (which has microsecond ties under load).
--
-- All three columns are nullable on the table so existing rows
-- (200+ historical entries on dev) don't blow up; they're treated as
-- the "pre-chain" prefix and the verifier reports the first chained
-- row as the genesis. New rows always populate all three.
--
-- Reverse migration:
--   ALTER TABLE activity_audit DROP COLUMN IF EXISTS chain_seq;
--   ALTER TABLE activity_audit DROP COLUMN IF EXISTS row_hash;
--   ALTER TABLE activity_audit DROP COLUMN IF EXISTS prev_hash;

ALTER TABLE activity_audit
    ADD COLUMN IF NOT EXISTS prev_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS row_hash  CHAR(64),
    ADD COLUMN IF NOT EXISTS chain_seq BIGINT;

-- Sequence for chain_seq — tied to the row insertion order. We don't
-- backfill the historical rows; chain_seq stays NULL on those.
CREATE SEQUENCE IF NOT EXISTS activity_audit_chain_seq;

-- Lookup index for the verifier walk (ORDER BY chain_seq).
CREATE INDEX IF NOT EXISTS idx_activity_audit_chain_seq
    ON activity_audit (chain_seq)
    WHERE chain_seq IS NOT NULL;

-- Quick "show me the latest" lookup used by the writer to fetch
-- prev_hash. Partial because pre-chain rows have NULL chain_seq.
CREATE INDEX IF NOT EXISTS idx_activity_audit_chain_tail
    ON activity_audit (chain_seq DESC)
    WHERE chain_seq IS NOT NULL;

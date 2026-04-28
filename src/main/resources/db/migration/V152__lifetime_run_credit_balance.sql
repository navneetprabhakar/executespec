-- R-161 — lifetime run credit balance for the v3 Free tier.
--
-- Greenfield design §7.5: Free tier is "5 lifetime runs + 5 per friend
-- invited (cap 25)" instead of "5 runs / month forever". The monthly
-- reset framing was punitive; the lifetime framing reads generous and
-- powers the referral growth loop. Defaults applied per D-002 (5
-- lifetime, +5 per invite, cap 25).
--
-- Cohabitation: this migration is purely additive. v2 surfaces ignore
-- the column entirely; v2's monthly run-count enforcement keeps working
-- on personal_run_count. v3 surfaces (R-142 entitlements + R-161
-- LifetimeCreditService) read this column. No data flows in the
-- reverse direction so the migration is safe to roll back by simply
-- dropping the column.
--
-- Backfill: every existing user receives 5 (the baseline grant). Paid
-- tiers receive the same column value but the v3 service treats them
-- as unlimited regardless of balance — the column only gates Free.

ALTER TABLE app_user
    ADD COLUMN lifetime_run_credit_balance INT NOT NULL DEFAULT 5;

ALTER TABLE app_user
    ADD COLUMN lifetime_run_credit_cap INT NOT NULL DEFAULT 25;

-- Optional: track who referred a given user so the +5-per-invite
-- mechanism (R-162) can credit the inviter without a separate
-- referral table for the simple case. R-162 may upgrade to a full
-- referral_event ledger if needed.
ALTER TABLE app_user
    ADD COLUMN referred_by_user_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_app_user_referred_by
    ON app_user (referred_by_user_id)
    WHERE referred_by_user_id IS NOT NULL;

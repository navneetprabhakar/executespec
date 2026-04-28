-- R-162 follow-up — persist referral invites so they survive JVM restart.
--
-- Wave 2's ReferralService kept pending invites in an in-memory
-- ConcurrentHashMap with TODO comments noting the persistence gap.
-- This migration closes that gap.
--
-- Lifecycle on a row:
--   1. INSERT — referrer calls POST /api/v3/referrals/invite. We
--      record (referrer_user_id, referee_email, token, created_at).
--      accepted_at + granted_at + accepted_by_user_id stay NULL.
--   2. UPDATE accepted_at on referee signup — the auth flow finds
--      the row by referee_email and stamps the binding.
--   3. UPDATE granted_at on referee's first ready run — V2RunOrchestrator's
--      terminate hook calls ReferralService.onRefereeFirstReadyRun
--      which finds the accepted-but-not-granted row, grants +5
--      credits to the referrer, and stamps granted_at.
--
-- Idempotency: granted_at is the once-only marker. The credit grant
-- only fires on rows where accepted_at IS NOT NULL AND granted_at
-- IS NULL.
--
-- Why a token column: future email pipeline will send the referee
-- a magic-link signup URL containing the token. The column is
-- nullable because today's recordInvite() doesn't yet send email
-- and skipping the token keeps the row valid.
--
-- Migration is purely additive (one new table, no edits to
-- existing tables). Rolling back is `DROP TABLE referral_invite;`.

CREATE TABLE IF NOT EXISTS referral_invite (
    id                 VARCHAR(64) PRIMARY KEY,
    referrer_user_id   VARCHAR(64)  NOT NULL,
    referee_email      VARCHAR(320) NOT NULL,
    token              VARCHAR(64),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    accepted_at        TIMESTAMPTZ,
    accepted_by_user_id VARCHAR(64),
    granted_at         TIMESTAMPTZ
);

-- Most queries filter by referee_email (binding on signup) or by
-- referrer_user_id (inviter's pending-count UI). Skip duplicate
-- pending invites from the same referrer to the same email.
CREATE UNIQUE INDEX IF NOT EXISTS ix_referral_invite_referrer_email
    ON referral_invite (referrer_user_id, lower(referee_email));

CREATE INDEX IF NOT EXISTS ix_referral_invite_email_lower
    ON referral_invite (lower(referee_email));

-- For the inviter's UI: "show me my pending count" — filter on
-- (referrer_user_id, granted_at IS NULL).
CREATE INDEX IF NOT EXISTS ix_referral_invite_referrer_pending
    ON referral_invite (referrer_user_id)
    WHERE granted_at IS NULL;

-- ============================================================================
-- V21: Tier-based permissions — feature gating, domain enforcement, member limits
-- ============================================================================

-- ── tier_config: add feature columns ────────────────────────────────────────
ALTER TABLE tier_config ADD COLUMN IF NOT EXISTS max_org_members      INT NOT NULL DEFAULT -1;
ALTER TABLE tier_config ADD COLUMN IF NOT EXISTS max_chat_messages    INT NOT NULL DEFAULT 10;
ALTER TABLE tier_config ADD COLUMN IF NOT EXISTS concurrent_run_limit INT NOT NULL DEFAULT 1;

-- ── org: add domain enforcement and member limit columns ────────────────────
ALTER TABLE org ADD COLUMN IF NOT EXISTS domain_enforcement_mode VARCHAR(20) NOT NULL DEFAULT 'OPEN';
ALTER TABLE org ADD COLUMN IF NOT EXISTS max_members             INT;
ALTER TABLE org ADD COLUMN IF NOT EXISTS block_common_domains    BOOLEAN NOT NULL DEFAULT FALSE;

-- ── Seed tier_config values ─────────────────────────────────────────────────
UPDATE tier_config SET max_org_members = -1, max_chat_messages = 0,  concurrent_run_limit = 1  WHERE tier = 'FREE';
UPDATE tier_config SET max_org_members = 10, max_chat_messages = 10, concurrent_run_limit = 3  WHERE tier = 'PRO';
UPDATE tier_config SET max_org_members = -1, max_chat_messages = 10, concurrent_run_limit = 10 WHERE tier = 'ENTERPRISE';

-- Update FREE monthly run limit from 10 → 5 (fixed)
UPDATE tier_config SET monthly_run_limit = 5 WHERE tier = 'FREE';

-- ENTERPRISE orgs: always enforce domains + block common domains
UPDATE org SET domain_enforcement_mode = 'ENFORCED', block_common_domains = TRUE WHERE tier = 'ENTERPRISE';

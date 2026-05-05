-- =====================================================================
-- V172 — Code Review + PR Review agents.
--
-- Two new agents, both gated to PAID tiers (PRO / TEAM / MAX / ENTERPRISE):
--
-- 1. PR Review Agent — runs against a single PR's diff. Stores per-line
--    comments + an overall verdict (APPROVE / REQUEST_CHANGES / COMMENT)
--    in our DB; Slice 3 will optionally mirror to GitHub via the Reviews
--    API. Trigger: manual button, eventually webhook on PR open.
--
-- 2. Code Review Agent — runs across a whole repo. Two-stage scan:
--    (a) static layer (semgrep) for the boring stuff, fast + free; (b)
--    LLM augmentation pass that triages findings + suggests fixes + adds
--    issues semgrep can't find (architectural / business-logic). Trigger:
--    manual button per repo, eventually scheduled cron.
--
-- Both agents persist their cost + tokens per-row so per-tier billing
-- gating ("PRO gets 10 PR reviews/month") can be enforced cheaply.
--
-- Idempotent: every column / index uses IF NOT EXISTS where supported.
-- =====================================================================

-- ── PR Review Agent ────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS pr_review (
  id                      UUID PRIMARY KEY,
  project_id              VARCHAR(36) NOT NULL,
  repo_full_name          VARCHAR(255) NOT NULL,
  pr_number               INT NOT NULL,
  head_sha                VARCHAR(40) NOT NULL,
  status                  VARCHAR(16) NOT NULL,    -- QUEUED | RUNNING | COMPLETE | FAILED
  verdict                 VARCHAR(32),             -- APPROVE | REQUEST_CHANGES | COMMENT | null
  summary                 TEXT,
  posted_to_github_at     TIMESTAMP,
  github_review_id        BIGINT,
  cost_usd                DOUBLE PRECISION DEFAULT 0,
  total_input_tokens      BIGINT DEFAULT 0,
  total_output_tokens     BIGINT DEFAULT 0,
  duration_ms             BIGINT,
  failure_reason          TEXT,
  started_at              TIMESTAMP NOT NULL DEFAULT NOW(),
  finished_at             TIMESTAMP,
  created_by_user_id      VARCHAR(36),
  CONSTRAINT chk_pr_review_status CHECK (
    status IN ('QUEUED', 'RUNNING', 'COMPLETE', 'FAILED')
  ),
  CONSTRAINT chk_pr_review_verdict CHECK (
    verdict IS NULL OR verdict IN ('APPROVE', 'REQUEST_CHANGES', 'COMMENT')
  )
);

-- Hot path: looking up the latest review for a (repo, PR, sha) tuple.
-- Re-running the agent on the same SHA is idempotent at the entity level
-- (UNIQUE), but we DO allow multiple rows when SHA changes (force-push +
-- new review). Index on (repo, pr) for the dashboard's per-PR fetch.
CREATE INDEX IF NOT EXISTS idx_pr_review_repo_pr_started
  ON pr_review(repo_full_name, pr_number, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_pr_review_project_started
  ON pr_review(project_id, started_at DESC);

-- One review row per (repo, PR, head_sha) — re-runs against the same
-- SHA short-circuit at the service layer instead of duplicating rows.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pr_review_repo_pr_sha
  ON pr_review(repo_full_name, pr_number, head_sha);

CREATE TABLE IF NOT EXISTS pr_review_comment (
  id                      UUID PRIMARY KEY,
  pr_review_id            UUID NOT NULL REFERENCES pr_review(id) ON DELETE CASCADE,
  file_path               VARCHAR(500) NOT NULL,
  line_number             INT NOT NULL,
  side                    VARCHAR(8) NOT NULL,     -- LEFT | RIGHT (per GitHub Reviews API)
  body                    TEXT NOT NULL,
  category                VARCHAR(32),             -- BUG | SECURITY | PERFORMANCE | STYLE | LOGIC | TEST_GAP
  severity                VARCHAR(16),             -- CRITICAL | MAJOR | MINOR | INFO
  posted_to_github_at     TIMESTAMP,
  github_comment_id       BIGINT,
  created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_pr_review_comment_side CHECK (side IN ('LEFT', 'RIGHT')),
  CONSTRAINT chk_pr_review_comment_severity CHECK (
    severity IS NULL
    OR severity IN ('CRITICAL', 'MAJOR', 'MINOR', 'INFO')
  )
);
CREATE INDEX IF NOT EXISTS idx_pr_review_comment_review
  ON pr_review_comment(pr_review_id);

-- ── Code Review Agent ──────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS code_review_scan (
  id                      UUID PRIMARY KEY,
  project_id              VARCHAR(36) NOT NULL,
  repo_id                 UUID,
  repo_full_name          VARCHAR(255) NOT NULL,
  scanned_sha             VARCHAR(40) NOT NULL,
  status                  VARCHAR(24) NOT NULL,    -- QUEUED | CLONING | STATIC_RUNNING | LLM_RUNNING | COMPLETE | FAILED
  trigger_source          VARCHAR(16) NOT NULL,    -- MANUAL | SCHEDULED | POST_MERGE
  total_files_scanned     INT DEFAULT 0,
  total_loc               INT DEFAULT 0,
  static_findings_count   INT DEFAULT 0,
  llm_findings_count      INT DEFAULT 0,
  llm_filtered_out_count  INT DEFAULT 0,
  cost_usd                DOUBLE PRECISION DEFAULT 0,
  total_input_tokens      BIGINT DEFAULT 0,
  total_output_tokens     BIGINT DEFAULT 0,
  duration_ms             BIGINT,
  failure_reason          TEXT,
  -- Markdown report — mirror of the structured issues for chat / docs / download. Generated at
  -- the end of the LLM_RUNNING phase. Treated as a project artifact analogous to
  -- ProjectArchitectureDoc / ProjectGeneratedDoc; kept alongside the structured
  -- code_review_issue rows so callers can pick whichever shape they need.
  report_markdown         TEXT,
  started_at              TIMESTAMP NOT NULL DEFAULT NOW(),
  finished_at             TIMESTAMP,
  created_by_user_id      VARCHAR(36),
  CONSTRAINT chk_code_review_scan_status CHECK (
    status IN ('QUEUED', 'CLONING', 'STATIC_RUNNING', 'LLM_RUNNING', 'COMPLETE', 'FAILED')
  ),
  CONSTRAINT chk_code_review_scan_trigger CHECK (
    trigger_source IN ('MANUAL', 'SCHEDULED', 'POST_MERGE')
  )
);

CREATE INDEX IF NOT EXISTS idx_code_review_scan_project_started
  ON code_review_scan(project_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_code_review_scan_repo
  ON code_review_scan(repo_id, started_at DESC)
  WHERE repo_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS code_review_issue (
  id                      UUID PRIMARY KEY,
  scan_id                 UUID NOT NULL REFERENCES code_review_scan(id) ON DELETE CASCADE,
  fingerprint             VARCHAR(64) NOT NULL,    -- sha256(rule||file||normalized_line||msg_prefix)
  severity                VARCHAR(16) NOT NULL,    -- CRITICAL | MAJOR | MINOR | INFO
  category                VARCHAR(32) NOT NULL,    -- BUG | SECURITY | PERFORMANCE | STYLE | LOGIC | etc
  source                  VARCHAR(16) NOT NULL,    -- STATIC | LLM | HYBRID
  rule_id                 VARCHAR(128),            -- semgrep rule id; LLM-NATIVE for LLM-only finds
  file_path               VARCHAR(500) NOT NULL,
  line_start              INT NOT NULL,
  line_end                INT NOT NULL,
  message                 TEXT NOT NULL,
  suggested_fix           TEXT,
  status                  VARCHAR(16) NOT NULL DEFAULT 'OPEN',  -- OPEN | RESOLVED | DISMISSED | FALSE_POSITIVE
  resolved_at             TIMESTAMP,
  resolved_by_user_id     VARCHAR(36),
  created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_code_review_issue_severity CHECK (
    severity IN ('CRITICAL', 'MAJOR', 'MINOR', 'INFO')
  ),
  CONSTRAINT chk_code_review_issue_source CHECK (
    source IN ('STATIC', 'LLM', 'HYBRID')
  ),
  CONSTRAINT chk_code_review_issue_status CHECK (
    status IN ('OPEN', 'RESOLVED', 'DISMISSED', 'FALSE_POSITIVE')
  )
);

CREATE INDEX IF NOT EXISTS idx_code_review_issue_scan_severity
  ON code_review_issue(scan_id, severity);

CREATE INDEX IF NOT EXISTS idx_code_review_issue_fingerprint
  ON code_review_issue(fingerprint);

CREATE INDEX IF NOT EXISTS idx_code_review_issue_open
  ON code_review_issue(scan_id, status)
  WHERE status = 'OPEN';

-- ── Tier gating columns on tier_config ────────────────────────────────
-- Both agents are PAID-only. tier_config.code_review_enabled controls the
-- whole-repo scan; tier_config.pr_review_enabled controls per-PR review.
-- FREE tier flipped to FALSE; PRO/TEAM/MAX/ENTERPRISE flipped to TRUE.
-- Per-month quota columns are added for future enforcement (PRO has tighter
-- caps than TEAM/MAX/ENTERPRISE); -1 = unlimited per the established
-- tier_config convention.

ALTER TABLE tier_config
  ADD COLUMN IF NOT EXISTS pr_review_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS code_review_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS pr_review_monthly_quota INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS code_review_monthly_quota INT NOT NULL DEFAULT 0;

-- Seed per-tier values. Idempotent: re-running just keeps the latest seed.
-- The tier_config PK column is `tier` (see V6), not `id` — every UPDATE must
-- key on `tier` or it'll silently match nothing.
UPDATE tier_config
  SET pr_review_enabled       = FALSE,
      code_review_enabled     = FALSE,
      pr_review_monthly_quota = 0,
      code_review_monthly_quota = 0
WHERE tier = 'FREE';

UPDATE tier_config
  SET pr_review_enabled       = TRUE,
      code_review_enabled     = TRUE,
      pr_review_monthly_quota = 10,
      code_review_monthly_quota = 4
WHERE tier = 'PRO';

UPDATE tier_config
  SET pr_review_enabled       = TRUE,
      code_review_enabled     = TRUE,
      pr_review_monthly_quota = -1,
      code_review_monthly_quota = -1
WHERE tier IN ('TEAM', 'MAX', 'ENTERPRISE');

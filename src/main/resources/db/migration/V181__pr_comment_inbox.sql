-- 2026-05-07 — Phase P1: incoming PR review comments inbox.
--
-- When a reviewer leaves a comment on a PR ExecuteSpec opened, GitHub fires a
-- `pull_request_review_comment.created` webhook. The handler resolves the comment to a runId
-- via `pull_request_record.(repo_full_name, pr_number) → run_id` and inserts a row here.
--
-- The v3 RunDetail page renders pending rows as a chip in the action row + a section in the
-- side-panel ChatPane. Phase P2 wires an "Address with chat" button that feeds the comment
-- into ApplyService and pushes the fix back to the same branch.
--
-- Distinct from `pr_review_comment` (V172) which holds OUTGOING ExecuteSpec→PR reviews from
-- the PrReviewAgent. This table is the INBOUND mirror — comments humans leave on our PRs.

CREATE TABLE IF NOT EXISTS pr_comment_inbox (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(64) NOT NULL,
    repo_full_name  VARCHAR(255) NOT NULL,
    pr_number       INT NOT NULL,
    -- GitHub's numeric review-comment id; unique per PR. Used for dedup so a webhook retry
    -- doesn't insert duplicate rows.
    comment_id      BIGINT NOT NULL,
    -- File path the comment was anchored to. Nullable for PR-level (not file-anchored) comments.
    file_path       TEXT,
    -- 1-based line number. Nullable for PR-level / multi-line comments.
    line_number     INT,
    -- Comment body — full text. We don't truncate; rendering is the UI's problem.
    body            TEXT NOT NULL,
    -- GitHub login of the comment author. Used for display.
    author          VARCHAR(255) NOT NULL,
    -- Direct link to the comment thread on github.com.
    github_url      TEXT NOT NULL,
    -- PENDING | ADDRESSED | DISMISSED. PENDING is "user hasn't decided yet"; ADDRESSED is set
    -- by Phase P2's apply flow; DISMISSED is set by the user clicking "Dismiss" in the UI.
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- Set when status flips from PENDING to ADDRESSED — references the run that applied the fix.
    addressed_run_id VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pr_comment_inbox_dedup UNIQUE (repo_full_name, pr_number, comment_id)
);

-- Hot-path: list pending comments for a run (the v3 RunDetail page chip + side-panel ChatPane).
CREATE INDEX IF NOT EXISTS idx_pr_comment_inbox_run_status
    ON pr_comment_inbox (run_id, status, created_at DESC);

-- Cross-link: count pending comments per project (Pulse "Health" rollup line).
CREATE INDEX IF NOT EXISTS idx_pr_comment_inbox_status_repo
    ON pr_comment_inbox (status, repo_full_name);

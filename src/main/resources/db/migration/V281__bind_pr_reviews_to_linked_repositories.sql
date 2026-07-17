-- Review admission hardening: retain the canonical linked repository identity used by
-- POST /api/pr-reviews and scope idempotency to the owning project + immutable linked repo id. The old global
-- (repo_full_name, pr_number, head_sha) key could reuse another tenant's review row when two
-- projects linked the same public repository.

ALTER TABLE pr_review
  ADD COLUMN IF NOT EXISTS repo_id UUID REFERENCES project_repo(id) ON DELETE SET NULL;

-- Best-effort historical backfill. More than one matching link is possible; the oldest durable
-- link is a stable choice. New writes always persist repo_id at admission.
UPDATE pr_review review
SET repo_id = (
  SELECT link.repo_id
  FROM project_repo_link link
  JOIN project_repo repo ON repo.id = link.repo_id
  WHERE link.project_id = review.project_id
    AND LOWER(repo.github_repo_full_name) = LOWER(review.repo_full_name)
  ORDER BY link.linked_at ASC, link.id ASC
  LIMIT 1
)
WHERE review.repo_id IS NULL;

DROP INDEX IF EXISTS uq_pr_review_repo_pr_sha;

-- New rows use immutable linked-repository identity. A repo rename updates its display/full name
-- but must neither bypass idempotency nor hide the existing review history.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pr_review_project_repo_pr_sha
  ON pr_review(project_id, repo_id, pr_number, head_sha)
  WHERE repo_id IS NOT NULL;

-- Historical rows that cannot be backfilled remain protected from duplicate legacy inserts, but
-- their mutable name-based identity is deliberately isolated from all new repo_id-backed rows.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pr_review_project_repo_name_pr_sha_legacy
  ON pr_review(project_id, repo_full_name, pr_number, head_sha)
  WHERE repo_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_pr_review_project_repo_pr_started
  ON pr_review(project_id, repo_id, pr_number, started_at DESC);

# `prreview/` — PR Review agent

> Last reviewed: 2026-05-12 (1.0.0-M1). **Merged to dev/main.**
>
> Paid-tier feature (FREE → 403; PRO=10/month, TEAM/ENTERPRISE=unlimited). Schema in V172.
> Phase 1 of the [`finding/FindingPatchSubmitter`](../finding/) autofix surfaces — `PrReviewAutofixService` dispatches a patch-mode run on demand from the PR Review verdict popover, pushing the patch to the PR head branch via `branchOverride`.

## What it does

Runs a senior-engineer-style review against a single PR's diff. PLANNING-tier model
(Sonnet 5 on Anthropic through 2026-08-31; GPT-5.4 / Gemini 3.5 Flash on their providers)
for quality. Single-call agent: one
`submitReview` tool call, no multi-turn loop, no shell, no file mutations.

Output shape:
- `verdict ∈ {APPROVE, COMMENT, REQUEST_CHANGES}`
- `summary` — 2–4 sentences
- `comments[]` — line-anchored, each with `filePath`, `lineNumber`, `side` (LEFT/RIGHT
  per GitHub Reviews API convention), `category`, `severity`, `body`

## Files

- `PrReviewAgent.java` — single-call agent. PLANNING-tier via `ModelResolver.resolve("PrReviewAgent", provider)`. Emits `submitReview` tool call; orchestrator parses args directly.
- `PrReviewService.java` — orchestrator. Tier gate (`assertTierAndQuota`) → idempotency check (per `(repo, pr, head_sha)` triple) → persist QUEUED row → dispatch on dedicated 4-thread pool → SSE on `pr-review-{prReviewId}`. Cost attribution via `RunCostTracker.snapshot("pr-review-{id}")`.
- `PrReviewController.java` — REST surface. `POST /api/pr-reviews` returns 202 + QUEUED row; FREE → 403, quota exhausted → 429, GitHub fetch fail → 502. List + detail endpoints under `/api/pr-reviews` and `/api/projects/{id}/pr-reviews`.
- `entity/PrReview.java`, `entity/PrReviewComment.java` — JPA entities for `pr_review` + `pr_review_comment` (V172).
- `repository/PrReviewRepository.java`, `repository/PrReviewCommentRepository.java` — Spring Data JPA repos. `countByUserSince(userId, monthStart)` powers the per-tier monthly quota gate.

## Schema

**V172** creates:

```sql
pr_review               -- one row per (repo, PR, head_sha) review run
  ├── verdict ∈ {APPROVE, REQUEST_CHANGES, COMMENT}
  ├── status ∈ {QUEUED, RUNNING, COMPLETE, FAILED}
  ├── posted_to_github_at + github_review_id (slice 3 — GitHub mirroring)
  ├── cost_usd + total_input_tokens + total_output_tokens
  └── unique (repo_full_name, pr_number, head_sha)

pr_review_comment       -- one row per line-anchored comment
  ├── side ∈ {LEFT, RIGHT}     (GitHub Reviews API convention)
  ├── category, severity        (free-form for now)
  ├── posted_to_github_at + github_comment_id
  └── FK pr_review (CASCADE)
```

Plus 4 columns added to `tier_config`: `pr_review_enabled`, `code_review_enabled`,
`pr_review_monthly_quota`, `code_review_monthly_quota`.

## Tier gating

Driven by `tier_config.pr_review_enabled` (boolean) + `pr_review_monthly_quota` (int;
-1 = unlimited, 0 = blocked). Default seed:

| Tier | Enabled | Monthly quota |
|------|---------|---------------|
| FREE | false | 0 |
| PRO | true | 10 |
| TEAM / ENTERPRISE | true | -1 |

`PrReviewService.assertTierAndQuota(user)` is the single chokepoint — throws
`TierBlockedException` (mapped to 403) or `QuotaExhaustedException` (mapped to 429).

## Idempotency

A unique index on `(repo_full_name, pr_number, head_sha)` ensures re-running on the
same SHA returns the existing row instead of starting a new review. The service short-
circuits when an existing row is in COMPLETE or RUNNING; FAILED + QUEUED-but-stalled
rows are re-queued.

## Outstanding (slice 3+)

- GitHub Reviews API mirroring — push the verdict + comments back to the PR via
  `POST /repos/{o}/{r}/pulls/{n}/reviews` so they show up inline. Both `posted_to_github_at`
  + `github_review_id` columns are already on the entity for this.
- Webhook-triggered auto-review on PR open — separate feature per user direction;
  PR review will be available manually-only until then.

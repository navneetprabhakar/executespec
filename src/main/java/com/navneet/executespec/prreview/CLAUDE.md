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
- `PrReviewService.java` — orchestrator. Tier gate (`assertTierAndQuota`) → project-scoped idempotency check → persist QUEUED row → dispatch on dedicated 4-thread pool → SSE on `pr-review-{prReviewId}`. Cost attribution via `RunCostTracker.snapshot("pr-review-{id}")`.
- `PrReviewController.java` — REST surface. `POST /api/pr-reviews` requires `projectId + repoId + prNumber`, write-guards the project, and canonicalises the optional `repoFullName` assertion through the linked `ProjectRepo` before tier/quota checks. FREE → 403, quota exhausted → 429, GitHub fetch fail → 502. Direct detail and by-PR list reads are project-read-guarded.
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
  └── V281 repo_id FK + unique (project_id, repo_id, pr_number, head_sha) for new rows

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

A partial unique index on `(project_id, repo_id, pr_number, head_sha)` ensures re-running on the
same SHA in the same tenant project returns the existing row even if the repository is renamed. The service short-
circuits when an existing row is in COMPLETE or RUNNING; FAILED + QUEUED-but-stalled
rows are re-queued. V281 replaces the old global repo/PR/SHA key, which could otherwise reuse a
different project's row when both tenants linked the same public repository, and backfills the new
`repo_id` column where a historical link can be resolved. Historical rows that cannot be backfilled
remain under a separate project/name/PR/SHA partial unique index and are not used by new-write
lookups.

## Outstanding (slice 3+)

`PrReviewService` has an explicit Spring-selected production constructor in addition to its
package-private compatibility constructor. Keep the production constructor explicit whenever the
dependency list changes; Lombok does not generate `@RequiredArgsConstructor` when another
constructor already exists. The benchmark-profile boot gate caught this contract on 2026-07-18.

- GitHub Reviews API mirroring — push the verdict + comments back to the PR via
  `POST /repos/{o}/{r}/pulls/{n}/reviews` so they show up inline. Both `posted_to_github_at`
  + `github_review_id` columns are already on the entity for this.
- Webhook-triggered auto-review on PR open — separate feature per user direction;
  PR review will be available manually-only until then.

Provider selection follows the owning project's `ProjectProviderResolver` policy (project setting,
then legacy creator preference, then Anthropic). The review path no longer hardcodes Anthropic, so
explicit project-level Anthropic/OpenAI/DeepSeek routing remains auditable.

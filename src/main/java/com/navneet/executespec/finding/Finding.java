package com.navneet.executespec.finding;

import java.util.List;
import java.util.UUID;

/**
 * Input shape for {@link FindingPatchSubmitter#submit(Finding)}. Every project-side
 * "Fix this" affordance — PR Review autofix, Tech Debt fix-by-category, Code Review
 * autofix, CI auto-fix — adapts its source data into a {@code Finding} and lets the
 * shared submitter handle dispatch / idempotency / push.
 *
 * @param type {@link FindingType} discriminator. Forms the prefix on the run row's
 *     {@code triggered_by_finding} column.
 * @param id stable per-finding identifier ({@code prReviewId}, {@code projectId|category},
 *     {@code codeReviewIssueId}, {@code checkRunId}). Combined with {@link #type} into the
 *     dedupe key. Must not contain a {@code :} (the type/id separator).
 * @param projectId the project this finding lives under. The patch run is project-scoped
 *     so {@code RunOrchestrator}'s multi-repo clone path picks up the linked repos.
 * @param selectedRepoIds repos to include in the workspace. For PR / CI findings this is
 *     a single repo (the PR's repo); for cross-repo tech-debt or code-review it's all
 *     project repos. Empty list means "let the orchestrator pick" — currently only
 *     legitimate when the spec is repo-agnostic which is rarely the case for findings.
 * @param userPrompt the synthesised user request the patch agent reads as its first user
 *     message. Caller is responsible for embedding the finding's identifying details so
 *     the agent can act on it (file paths + line numbers + assertion text + suggested
 *     fix).
 * @param branchOverride PR-shaped findings push commits to the PR's head branch by setting
 *     this. Tech-debt-shaped findings leave it null so the run lands on a fresh
 *     {@code executespec/<runShort>} branch + opens a new PR.
 * @param userId the user dispatching the patch (used for tier check + run ownership).
 * @param aiProvider optional override; defaults to project's configured provider.
 */
public record Finding(
    FindingType type,
    String id,
    String projectId,
    List<UUID> selectedRepoIds,
    String userPrompt,
    String branchOverride,
    String userId,
    String aiProvider) {

  public Finding {
    if (type == null) throw new IllegalArgumentException("type required");
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
    if (id.indexOf(':') >= 0) {
      throw new IllegalArgumentException(
          "id must not contain ':' (used as type/id separator on the run row)");
    }
    if (projectId == null || projectId.isBlank())
      throw new IllegalArgumentException("projectId required");
    if (userPrompt == null || userPrompt.isBlank())
      throw new IllegalArgumentException("userPrompt required");
    if (selectedRepoIds == null) selectedRepoIds = List.of();
    if (userId == null || userId.isBlank())
      throw new IllegalArgumentException("userId required");
  }

  /** {@code <TYPE>:<id>} — stamped on {@code pipeline_run.triggered_by_finding}. */
  public String findingKey() {
    return type.name() + ":" + id;
  }
}

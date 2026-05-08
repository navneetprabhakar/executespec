package com.navneet.executespec.finding;

/**
 * Categories of project-side findings that a patch-mode run can address. The enum value
 * doubles as the type-prefix on {@code pipeline_run.triggered_by_finding} ({@code
 * <TYPE>:<id>}) so a single grep / index lookup can answer "what kind of finding fired
 * this run?"
 *
 * <p>Adding a new type means: extend the enum, add the corresponding caller (a service
 * that synthesises a focused user prompt + calls {@link FindingPatchSubmitter}), and
 * surface a "Fix" affordance in the UI surface where that finding lives.
 *
 * <p>Out of scope for now: build-mode runs are never finding-triggered. The triggered-by
 * column is null for those.
 */
public enum FindingType {

  /**
   * A {@code PrReview} verdict + comments. The patch run addresses each comment in-place
   * on the PR's head branch (via {@code branchOverride}), commits, lets CI re-run.
   */
  PR_REVIEW,

  /**
   * A {@code TechDebt} category bucket (e.g. "unused imports", "naming violations"). The
   * patch run sweeps the project's linked repos for the category and produces one
   * focused PR per category. Not per-issue: typed batches keep the diff coherent.
   */
  TECH_DEBT,

  /**
   * A single {@code CodeReviewAgent} issue (security / performance / logic). Per-issue or
   * per-severity batch dispatch — driven from the code-review report panel.
   */
  CODE_REVIEW_ISSUE,

  /**
   * A failed {@code check_run} on a PR opened by ExecuteSpec. The CI auto-fix listener
   * captures the failure log and dispatches a patch run with the head branch as
   * {@code branchOverride}. Bounded by per-PR cooldown to prevent infinite loops.
   */
  CI_FAILURE
}

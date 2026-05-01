package com.navneet.executespec.migration.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * R-338 — Phase 1 plan-gate response for a migration recipe across the user's selected repos.
 *
 * <p>Returned by {@code POST /api/migrations/{slug}/dry-run}. No LLM calls in v1; file count + cost
 * estimate come from the recipe's declared bands or the {@code migration_dry_run_cache} table;
 * sample diff comes from cache when present. The user's "Apply" click triggers Phase 2 (V2
 * pipeline; wired in W4 / W5).
 */
public record MigrationDryRunDto(
    String slug,
    String name,
    BigDecimal estCostLowTotal,
    BigDecimal estCostHighTotal,
    Integer estFilesLowTotal,
    Integer estFilesHighTotal,
    String sampleDiff,
    List<RepoApplicability> perRepo) {

  /**
   * Per-repo applicability assessment.
   *
   * @param repoId the project_repo id this row evaluates.
   * @param fullName e.g. "owner/repo" for display.
   * @param status APPLICABLE | INCOMPATIBLE_STACK | UNKNOWN_STACK | NOT_LINKED. Drives the per-row
   *     chip in the UI.
   * @param reason human-readable detail when not APPLICABLE.
   * @param estFilesLow, estFilesHigh per-repo file count band.
   * @param estCostLow, estCostHigh per-repo cost band.
   */
  public record RepoApplicability(
      UUID repoId,
      String fullName,
      String status,
      String reason,
      Integer estFilesLow,
      Integer estFilesHigh,
      BigDecimal estCostLow,
      BigDecimal estCostHigh) {}
}

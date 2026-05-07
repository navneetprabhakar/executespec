package com.navneet.executespec.landing;

import java.util.List;

/**
 * Selling-point #6 — public anonymized /exec snapshot.
 *
 * <p>Linear's "share-a-roadmap" pattern, applied to the strongest in-app surface (per the audit,
 * /exec is the conversion vehicle for the PgM/CFO persona). Returned by {@code GET
 * /api/v3/public/exec-snapshot}.
 *
 * <p>Window: last 30 days. All identifiers stripped (project names become "Project A / B / C"; spec
 * text truncated to 80 chars).
 *
 * @param windowDays integer days the snapshot covers (always 30 for now)
 * @param totalRuns runs in window
 * @param successRatePercent share with verifiedScore >= 80, 0-100
 * @param totalSpendUsd total LLM spend in window
 * @param activeProjects distinct project count in window
 * @param needsAttentionCount runs with NEEDS_REVIEW or FAILED verdict
 * @param spendByProject anonymized "Project A: 4 runs · $7.85" rows
 * @param topWins top 5 best-verifiedScore wins, anonymized spec text
 */
public record V3ExecSnapshot(
    int windowDays,
    long totalRuns,
    Integer successRatePercent,
    Double totalSpendUsd,
    long activeProjects,
    long needsAttentionCount,
    List<ProjectSpend> spendByProject,
    List<TopWin> topWins) {
  public record ProjectSpend(String anonymousName, long runs, Double spendUsd) {}

  public record TopWin(String spec, Integer verifiedScore, Double costUsd, String when) {}
}

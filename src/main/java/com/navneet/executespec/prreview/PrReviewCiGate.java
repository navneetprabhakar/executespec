package com.navneet.executespec.prreview;

import java.util.Locale;

/**
 * Deterministic, post-model CI verdict gate for the PR Review agent.
 *
 * <p>Persona register P1 #4 (observed live 2026-08-04): the agent APPROVED a pull request whose CI
 * was red because nothing fed the head commit's check-run status into the review and nothing
 * checked the verdict against it afterwards. This gate closes that by construction:
 *
 * <ul>
 *   <li><b>FAILURE</b> (any failing/errored check-run on the head SHA at review time) — the final
 *       verdict must not be an approval. {@code APPROVE} is capped to {@code REQUEST_CHANGES}, and
 *       an explicit structured finding ({@code CI failing on head <sha> (...)}) is prepended to the
 *       summary regardless of verdict. Tighten-only: {@code COMMENT} and {@code REQUEST_CHANGES}
 *       keep their verdicts and only gain the finding.
 *   <li><b>UNKNOWN / PENDING / NEUTRAL</b> (no webhook, no check runs, still running, or only
 *       neutral conclusions) — the verdict is never capped, but the summary must state that CI is
 *       unverified so the review cannot read as "checks passed".
 *   <li><b>SUCCESS</b> — the review is returned byte-identical; green CI changes nothing.
 * </ul>
 *
 * <p>The gate runs AFTER the model's {@code submitReview} tool call is parsed, so no model output
 * can override it. Statuses mirror {@link
 * com.navneet.executespec.repo.GitHubRepoService.CheckRunRollup}; a missing/blank status is treated
 * as UNKNOWN, never as passing.
 */
public final class PrReviewCiGate {

  public static final String STATUS_FAILURE = "FAILURE";
  public static final String STATUS_SUCCESS = "SUCCESS";
  public static final String STATUS_UNKNOWN = "UNKNOWN";

  /** CI evidence for the PR head at review time. {@code null} evidence is UNKNOWN, not green. */
  public record CiEvidence(String status, int failingCount, String headSha) {}

  private PrReviewCiGate() {}

  /**
   * Applies the tighten-only CI gate to a parsed agent review. Never loosens a verdict and never
   * mutates the comment list.
   */
  public static PrReviewAgent.AgentReview apply(
      PrReviewAgent.AgentReview review, CiEvidence evidence) {
    if (review == null) return null;
    String status = normalizeStatus(evidence);
    if (STATUS_SUCCESS.equals(status)) {
      return review;
    }
    String sha = evidence == null || evidence.headSha() == null ? "" : evidence.headSha().trim();
    String shortSha = sha.length() > 12 ? sha.substring(0, 12) : sha;
    if (shortSha.isBlank()) shortSha = "unknown";

    if (STATUS_FAILURE.equals(status)) {
      int failing = Math.max(1, evidence == null ? 1 : evidence.failingCount());
      String finding =
          "CI failing on head "
              + shortSha
              + " ("
              + failing
              + " failing check-run"
              + (failing == 1 ? "" : "s")
              + "). Approval withheld by the deterministic CI gate until checks pass.";
      String verdict = "APPROVE".equals(review.verdict()) ? "REQUEST_CHANGES" : review.verdict();
      return new PrReviewAgent.AgentReview(
          verdict,
          prepend(finding, review.summary()),
          review.comments(),
          review.durationMs(),
          review.provider(),
          review.model());
    }

    // UNKNOWN / PENDING / NEUTRAL / anything unrecognized: unverified note, verdict untouched.
    String note =
        "CI status for head "
            + shortSha
            + " is unverified ("
            + status
            + "); this review does not assume checks passed.";
    return new PrReviewAgent.AgentReview(
        review.verdict(),
        prepend(note, review.summary()),
        review.comments(),
        review.durationMs(),
        review.provider(),
        review.model());
  }

  private static String normalizeStatus(CiEvidence evidence) {
    if (evidence == null || evidence.status() == null || evidence.status().isBlank()) {
      return STATUS_UNKNOWN;
    }
    return evidence.status().trim().toUpperCase(Locale.ROOT);
  }

  private static String prepend(String line, String summary) {
    return summary == null || summary.isBlank() ? line : line + "\n\n" + summary;
  }
}

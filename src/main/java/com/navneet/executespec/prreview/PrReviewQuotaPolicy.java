package com.navneet.executespec.prreview;

import java.time.Instant;
import java.time.ZoneOffset;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.prreview.repository.PrReviewRepository;

/**
 * Tier + monthly-quota admission for PR Review, extracted verbatim from {@code PrReviewService} to
 * keep that orchestrator under the 600-line production-class ceiling. Pure policy: no Spring bean,
 * no state — the service passes its own repositories.
 */
final class PrReviewQuotaPolicy {

  private PrReviewQuotaPolicy() {}

  /**
   * Validates that the supplied user is on a paid tier with the {@code pr_review_enabled} feature
   * flag flipped on AND has remaining monthly quota. Throws on rejection so the caller (HTTP
   * controller) can map to 403/429 cleanly.
   */
  static void assertTierAndQuota(
      AppUser user, TierConfigRepository tierConfigRepo, PrReviewRepository reviewRepo) {
    if (user == null) throw new PrReviewService.TierBlockedException("authentication required");
    String tier = user.getTier();
    if (tier == null || "FREE".equalsIgnoreCase(tier)) {
      throw new PrReviewService.TierBlockedException(
          "PR review is a paid feature — upgrade to PRO or above to enable.");
    }
    TierConfig cfg = tierConfigRepo.findById(tier).orElse(null);
    if (cfg == null || !Boolean.TRUE.equals(cfg.getPrReviewEnabled())) {
      throw new PrReviewService.TierBlockedException(
          "PR review not enabled for tier " + tier + " — contact support if this is wrong.");
    }
    int quota = cfg.getPrReviewMonthlyQuota() == null ? 0 : cfg.getPrReviewMonthlyQuota();
    if (quota >= 0) {
      // -1 = unlimited; 0 = blocked; positive = capped.
      Instant monthStart =
          Instant.now()
              .atZone(ZoneOffset.UTC)
              .toLocalDate()
              .withDayOfMonth(1)
              .atStartOfDay()
              .toInstant(ZoneOffset.UTC);
      long usedThisMonth = reviewRepo.countByUserSince(user.getId(), monthStart);
      if (usedThisMonth >= quota) {
        throw new PrReviewService.QuotaExhaustedException(
            "Monthly PR-review quota exhausted ("
                + usedThisMonth
                + "/"
                + quota
                + "). Resets on the 1st UTC.");
      }
    }
  }
}

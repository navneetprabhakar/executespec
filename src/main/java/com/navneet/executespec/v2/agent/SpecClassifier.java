package com.navneet.executespec.v2.agent;

import java.util.List;
import java.util.Locale;

/**
 * Bug #3 fix — deterministic classifier that decides {@link Classification#SINGLE_SHOT} / {@link
 * Classification#MEDIUM} / {@link Classification#MULTI_TASK} from the user spec alone, so the
 * planner isn't forced into a fixed 4-task cap for every non-trivial request.
 *
 * <p>Signals used:
 *
 * <ul>
 *   <li><b>Length</b> — very short requests are SINGLE_SHOT candidates, long ones are MULTI_TASK.
 *   <li><b>Bullet count</b> — 10+ bullets or numbered items is strong MULTI_TASK signal.
 *   <li><b>Domain keywords</b> — presence of {@code dashboard}, {@code billing}, {@code inventory},
 *       {@code reports}, {@code appointments}, etc. Five or more distinct domains pushes to
 *       MULTI_TASK regardless of length.
 *   <li><b>Comprehensive markers</b> — "comprehensive", "full-stack", "multi-", "end-to-end" force
 *       MULTI_TASK.
 * </ul>
 *
 * <p>Kept pure-static so it's trivially testable and doesn't need Spring wiring.
 */
public final class SpecClassifier {

  private SpecClassifier() {}

  private static final List<String> DOMAIN_KEYWORDS =
      List.of(
          "dashboard",
          "report",
          "analytics",
          "billing",
          "invoice",
          "payment",
          "checkout",
          "subscription",
          "inventory",
          "stock",
          "catalog",
          "appointment",
          "booking",
          "calendar",
          "scheduling",
          "authentication",
          "authorization",
          "login",
          "role-based",
          "rbac",
          "notification",
          "email",
          "slack",
          "search",
          "filter",
          "admin",
          "settings",
          "profile",
          "chat",
          "messaging",
          "api",
          "webhook",
          "integration",
          "upload",
          "file storage",
          "media",
          "pharmacy",
          "prescription",
          "consultation",
          "checkout",
          "return",
          "order management");

  private static final List<String> COMPREHENSIVE_MARKERS =
      List.of(
          "comprehensive",
          "full-stack",
          "end-to-end",
          "complete system",
          "multi-",
          "multi doctor",
          "multi-module",
          "multi-role",
          "enterprise",
          "production-ready");

  /**
   * @param spec raw user request
   * @return {@link Classification} matching the spec's scope
   */
  public static Classification classify(String spec) {
    if (spec == null || spec.isBlank()) return Classification.SINGLE_SHOT;
    String lower = spec.toLowerCase(Locale.ROOT);
    int length = spec.length();

    // Tiny, one-idea requests — skip the planner LLM call entirely.
    if (length < 200 && countBullets(spec) <= 1) {
      return Classification.SINGLE_SHOT;
    }

    // Comprehensive language is a strong forcing function.
    for (String marker : COMPREHENSIVE_MARKERS) {
      if (lower.contains(marker)) return Classification.MULTI_TASK;
    }

    int distinctDomains = distinctDomains(lower);
    int bullets = countBullets(spec);

    // 5+ distinct domains or 12+ structured items — unambiguously multi-task.
    if (distinctDomains >= 5 || bullets >= 12 || length > 2000) {
      return Classification.MULTI_TASK;
    }

    // Moderate length with meaningful structure — still fits in MEDIUM's 4-task cap.
    return Classification.MEDIUM;
  }

  static int distinctDomains(String lower) {
    int hits = 0;
    for (String k : DOMAIN_KEYWORDS) {
      if (lower.contains(k)) hits++;
    }
    return hits;
  }

  static int countBullets(String spec) {
    int count = 0;
    for (String line : spec.split("\n")) {
      String t = line.trim();
      if (t.startsWith("- ") || t.startsWith("* ") || t.matches("^\\d+[.)]\\s+.*")) {
        count++;
      }
    }
    return count;
  }
}

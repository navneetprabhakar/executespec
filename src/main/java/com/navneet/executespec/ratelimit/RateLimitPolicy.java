package com.navneet.executespec.ratelimit;

import java.util.Map;
import java.util.Set;

import com.navneet.executespec.security.guardrail.GuardrailLevel;
import com.navneet.executespec.security.guardrail.GuardrailLevelResolver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Defines rate limit thresholds per endpoint group and user tier.
 *
 * <p>Groups:
 *
 * <ul>
 *   <li>AUTH — login, register, forgot-password (keyed by IP)
 *   <li>RUNS — POST /api/runs (keyed by userId)
 *   <li>WEBHOOK — all webhook endpoints (keyed by IP, high limit)
 *   <li>PUBLIC_SHARE — unauthenticated project-share reads (always keyed by IP)
 *   <li>READ — all GET requests (keyed by userId)
 *   <li>WRITE — all POST/PUT/DELETE not in other groups (keyed by userId)
 * </ul>
 *
 * <p>Guardrail-level aware as of Plan 1 P3. {@link GuardrailLevel#LOW} is the default and returns
 * today's limits unchanged; {@link GuardrailLevel#MEDIUM} halves WRITE / DEPLOY / PREVIEW capacity
 * (the three most abuse-prone groups); {@link GuardrailLevel#HIGH} quarters every group (intended
 * as an incident-response knob, not steady-state).
 */
@Component
public class RateLimitPolicy {

  /** MEDIUM-level multiplier for these groups; all others stay ×1.0. */
  private static final Set<String> MEDIUM_HALVED_GROUPS =
      Set.of("WRITE", "DEPLOY", "PREVIEW", "PUBLIC_SHARE");

  private final GuardrailLevelResolver guardrailLevelResolver;

  @Autowired
  public RateLimitPolicy(GuardrailLevelResolver guardrailLevelResolver) {
    this.guardrailLevelResolver = guardrailLevelResolver;
  }

  /** Back-compat constructor — assumes LOW (current behaviour). */
  public RateLimitPolicy() {
    this.guardrailLevelResolver = null;
  }

  /** Endpoint group → tier → max requests per minute. */
  private static final Map<String, Map<String, Integer>> LIMITS =
      Map.ofEntries(
          Map.entry(
              "AUTH",
              Map.of(
                  "login", 10,
                  "register", 5,
                  "forgot", 3,
                  // Token-redemption steps, matched to real legitimate frequency: reset-password is
                  // as rare as the forgot-password request that issues its token; verify-email is
                  // once per signup. refresh is deliberately looser — every active session calls it
                  // on access-token expiry, so a tight per-IP cap would break shared-NAT offices,
                  // and a high-entropy refresh JWT is not brute-forceable anyway. All three are
                  // still far below the WRITE(60)/READ(300) buckets they used to fall into.
                  "reset", 3,
                  "verify", 5,
                  "refresh", 30,
                  "DEFAULT", 10)),
          Map.entry(
              "RUNS",
              Map.of(
                  "FREE", 5,
                  "PRO", 20,
                  "TEAM", 60,
                  "ENTERPRISE", 60,
                  "DEFAULT", 5)),
          Map.entry("WEBHOOK", Map.of("DEFAULT", 100)),
          // Public bearer links are deliberately isolated from the broad authenticated READ
          // budget. These calls are IP-keyed by RateLimitFilter and never inherit an org tier.
          Map.entry("PUBLIC_SHARE", Map.of("FREE", 60, "DEFAULT", 60)),
          Map.entry(
              "READ",
              Map.of(
                  "FREE", 300,
                  "PRO", 600,
                  "TEAM", 2000,
                  "ENTERPRISE", 2000,
                  "DEFAULT", 300)),
          Map.entry(
              "WRITE",
              Map.of(
                  "FREE", 60,
                  "PRO", 200,
                  "TEAM", 1000,
                  "ENTERPRISE", 1000,
                  "DEFAULT", 60)),
          Map.entry(
              "PREVIEW",
              Map.of(
                  "FREE", 30,
                  "PRO", 60,
                  "TEAM", 200,
                  "ENTERPRISE", 200,
                  "DEFAULT", 30)),
          Map.entry(
              "DEPLOY",
              Map.of(
                  "FREE", 5,
                  "PRO", 20,
                  "TEAM", 60,
                  "ENTERPRISE", 60,
                  "DEFAULT", 5)));

  /**
   * Get the rate limit (requests per minute) for the given group and tier. Reads the current
   * guardrail level from the feature flag (or LOW if the resolver isn't injected — e.g. plain test
   * construction) and applies the level-specific multiplier before returning.
   *
   * @param group endpoint group (AUTH, RUNS, WEBHOOK, READ, WRITE)
   * @param tier user tier or auth sub-type (FREE, PRO, ENTERPRISE, login, register, etc.)
   * @return max requests per minute
   */
  public int getLimit(String group, String tier) {
    GuardrailLevel level =
        guardrailLevelResolver != null ? guardrailLevelResolver.current() : GuardrailLevel.LOW;
    return getLimit(group, tier, level);
  }

  /**
   * Get the rate limit at an explicit guardrail level. Exposed for testing and for future callers
   * that want to override the flag-resolved level.
   */
  public int getLimit(String group, String tier, GuardrailLevel level) {
    Map<String, Integer> groupLimits = LIMITS.getOrDefault(group, Map.of("DEFAULT", 60));
    int base = groupLimits.getOrDefault(tier, groupLimits.getOrDefault("DEFAULT", 60));
    return applyLevelMultiplier(base, group, level == null ? GuardrailLevel.LOW : level);
  }

  private static int applyLevelMultiplier(int base, String group, GuardrailLevel level) {
    // HIGH quarters everything — incident-response knob.
    if (level.atLeastHigh()) {
      return Math.max(1, base / 4);
    }
    // MEDIUM halves the abuse-prone write/deploy/preview groups.
    if (level.atLeastMedium() && MEDIUM_HALVED_GROUPS.contains(group)) {
      return Math.max(1, base / 2);
    }
    return base;
  }

  /** Classify a request into an endpoint group. */
  public String classifyGroup(String method, String path) {
    // reset-password / refresh / verify-email consume the higher-value token, so they belong in the
    // tight AUTH bucket alongside forgot-password. Previously they fell through to WRITE (60/min)
    // and READ (300/min) — 20x more permissive than the 3/min forgot-password step that merely
    // *issues* the token they redeem (RR-1642).
    if (path.startsWith("/api/auth/login")
        || path.startsWith("/api/auth/register")
        || path.startsWith("/api/auth/forgot")
        || path.startsWith("/api/auth/reset-password")
        || path.startsWith("/api/auth/refresh")
        || path.startsWith("/api/auth/verify-email")) {
      return "AUTH";
    }
    if (path.contains("/webhook")) {
      return "WEBHOOK";
    }
    if ("POST".equalsIgnoreCase(method) && path.equals("/api/runs")) {
      return "RUNS";
    }
    if (path.contains("/preview/")) {
      return "PREVIEW";
    }
    if (("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))
        && path.startsWith("/api/shared/")) {
      return "PUBLIC_SHARE";
    }
    if (path.contains("/deploy") || path.contains("/push-github")) {
      return "DEPLOY";
    }
    // HEAD is semantically a GET without the body (RFC 7231) — classify both as READ.
    // Prior behaviour wrongly sent HEAD to the WRITE bucket (which is ~3× smaller),
    // causing browser preflight/asset-check patterns to hit tighter caps than plain GETs.
    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
      return "READ";
    }
    return "WRITE";
  }

  /** Get the sub-type for AUTH group (login, register, forgot, reset, verify, refresh). */
  public String getAuthSubType(String path) {
    if (path.contains("/login")) return "login";
    if (path.contains("/register")) return "register";
    // Check reset-password before the looser /forgot match so it gets its own bucket.
    if (path.contains("/reset-password")) return "reset";
    if (path.contains("/forgot")) return "forgot";
    if (path.contains("/verify-email")) return "verify";
    if (path.contains("/refresh")) return "refresh";
    return "DEFAULT";
  }
}

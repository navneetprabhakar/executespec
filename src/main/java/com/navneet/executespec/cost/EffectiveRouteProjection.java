package com.navneet.executespec.cost;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-side projection of the physical route that actually served a run, derived only from the
 * canonical {@link RunStepUsage} physical-attempt ledger.
 *
 * <p>This exists because the requested route and the served route can legitimately diverge —
 * cross-provider failover, managed-AI tier locks and model step-down all rebuild the request before
 * dispatch. Any artifact that reports the requested route as though it were the served route is
 * wrong, and a benchmark campaign built on such an artifact is not comparable.
 *
 * <p>A multi-route run is represented as {@link Attribution#MIXED}. When every successful route
 * used the same provider, {@code provider} retains that common provider while {@code model} remains
 * null; cross-provider mixing leaves both null. Exact-route consumers must continue to require
 * {@link Attribution#LEDGER}.
 */
public record EffectiveRouteProjection(String provider, String model, Attribution attribution) {

  /** How much the ledger can say about the physical route of a run. */
  public enum Attribution {
    /** Exactly one successful (provider, model) route served the run. */
    LEDGER,
    /** More than one distinct successful route served the run. */
    MIXED,
    /** No successful physical attempt is recorded, so no route can be claimed. */
    UNATTRIBUTED
  }

  private static final String SUCCESS = "SUCCESS";

  public static final EffectiveRouteProjection UNATTRIBUTED =
      new EffectiveRouteProjection(null, null, Attribution.UNATTRIBUTED);

  /**
   * Projects the distinct successful routes in {@code rows}. Failed attempts are excluded: an
   * attempt that never produced a usable response did not serve the run.
   */
  public static EffectiveRouteProjection from(List<RunStepUsage> rows) {
    if (rows == null || rows.isEmpty()) {
      return UNATTRIBUTED;
    }
    Set<String> distinct = new LinkedHashSet<>();
    Set<String> providers = new LinkedHashSet<>();
    String firstProvider = null;
    String firstModel = null;
    for (RunStepUsage row : rows) {
      if (row == null || !SUCCESS.equalsIgnoreCase(row.getAttemptStatus())) {
        continue;
      }
      String provider = normalize(row.getProvider());
      String model = normalizeModel(row.getModel());
      if (provider == null || model == null) {
        continue;
      }
      providers.add(provider);
      if (distinct.add(provider + "\u001f" + model) && distinct.size() == 1) {
        firstProvider = provider;
        firstModel = model;
      }
    }
    if (distinct.isEmpty()) {
      return UNATTRIBUTED;
    }
    if (distinct.size() > 1) {
      return new EffectiveRouteProjection(
          providers.size() == 1 ? providers.iterator().next() : null, null, Attribution.MIXED);
    }
    return new EffectiveRouteProjection(firstProvider, firstModel, Attribution.LEDGER);
  }

  /** True when the ledger proves exactly one route and it differs from what was requested. */
  public boolean divergesFrom(String requestedProvider, String requestedModel) {
    if (attribution == Attribution.MIXED) {
      return true;
    }
    if (attribution != Attribution.LEDGER) {
      return false;
    }
    return !equalsNormalized(provider, requestedProvider) || !equalsTrimmed(model, requestedModel);
  }

  /**
   * Divergence against a possibly-partial request. Unlike {@link #divergesFrom}, a blank requested
   * provider or model is treated as "not requested" rather than as a mismatch — an AUTO-routed run
   * pins no model, so comparing against a null model would report every run as diverged.
   */
  public boolean divergesFromRequestedRoute(String requestedProvider, String requestedModel) {
    if (attribution == Attribution.MIXED) {
      // Provider-only requests remain true when tier-specific models stayed on that provider.
      // Exact model pins still diverge because MIXED proves more than one model served the run.
      if (isPresent(requestedModel)) return true;
      return isPresent(requestedProvider) && !equalsNormalized(provider, requestedProvider);
    }
    if (attribution != Attribution.LEDGER) {
      return false;
    }
    if (isPresent(requestedProvider) && !equalsNormalized(provider, requestedProvider)) {
      return true;
    }
    return isPresent(requestedModel) && !equalsTrimmed(model, requestedModel);
  }

  private static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean equalsNormalized(String left, String right) {
    String a = normalize(left);
    String b = normalize(right);
    return a == null ? b == null : a.equals(b);
  }

  private static boolean equalsTrimmed(String left, String right) {
    String a = left == null ? null : left.trim();
    String b = right == null ? null : right.trim();
    return a == null ? b == null : a.equals(b);
  }

  /**
   * The recorder writes the literal {@code "unknown"} when neither the caller nor the provider
   * response named a provider/model ({@code RunStepUsageRecorder}'s {@code firstNonBlank(…,
   * "unknown")}). That is an ABSENCE marker, not a route. Admitting it produced ledger-grade
   * "proof" of a provider called {@code unknown} and made {@link #divergesFrom} fire against every
   * real requested provider (RR-1560); treating it as absent degrades the projection to {@link
   * Attribution#UNATTRIBUTED}, which is what the enum already exists to say.
   */
  private static final Set<String> ABSENT_SENTINELS = Set.of("unknown", "null", "none", "n/a", "-");

  private static String normalize(String provider) {
    if (provider == null || provider.isBlank()) {
      return null;
    }
    String normalized = provider.trim().toLowerCase(Locale.ROOT);
    if (ABSENT_SENTINELS.contains(normalized)) {
      return null;
    }
    return "google".equals(normalized) ? "gemini" : normalized;
  }

  /** Same sentinel rule for the model half of the route; casing is otherwise preserved. */
  private static String normalizeModel(String model) {
    if (model == null || model.isBlank()) {
      return null;
    }
    String trimmed = model.trim();
    return ABSENT_SENTINELS.contains(trimmed.toLowerCase(Locale.ROOT)) ? null : trimmed;
  }
}

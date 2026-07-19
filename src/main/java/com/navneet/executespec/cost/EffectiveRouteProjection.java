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
 * <p>A multi-route run is represented as {@link Attribution#MIXED} with {@code provider}/{@code
 * model} left null. Collapsing a mixed run to one of its providers would defeat the entire point of
 * this projection, so it is deliberately not representable here.
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
    String firstProvider = null;
    String firstModel = null;
    for (RunStepUsage row : rows) {
      if (row == null || !SUCCESS.equalsIgnoreCase(row.getAttemptStatus())) {
        continue;
      }
      String provider = normalize(row.getProvider());
      String model = row.getModel() == null ? null : row.getModel().trim();
      if (provider == null || model == null || model.isEmpty()) {
        continue;
      }
      if (distinct.add(provider + "\u001f" + model) && distinct.size() == 1) {
        firstProvider = provider;
        firstModel = model;
      }
    }
    if (distinct.isEmpty()) {
      return UNATTRIBUTED;
    }
    if (distinct.size() > 1) {
      return new EffectiveRouteProjection(null, null, Attribution.MIXED);
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
      return true;
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

  private static String normalize(String provider) {
    if (provider == null || provider.isBlank()) {
      return null;
    }
    String normalized = provider.trim().toLowerCase(Locale.ROOT);
    return "google".equals(normalized) ? "gemini" : normalized;
  }
}

package com.navneet.executespec.agent;

import com.navneet.executespec.cost.EffectiveRouteProjection;
import com.navneet.executespec.router.ResolvedModel;

/** Fail-closed physical-route attestation for strict planner repair and escalation calls. */
public final class ExactPlannerRouteAttestation {

  private ExactPlannerRouteAttestation() {}

  static void verify(ResolvedModel requested, EffectiveRouteProjection physical) {
    String failure = mismatch(requested, physical);
    if (failure != null) throw new MismatchException(failure);
  }

  static String mismatch(ResolvedModel requested, EffectiveRouteProjection physical) {
    if (physical == null || physical.attribution() != EffectiveRouteProjection.Attribution.LEDGER) {
      return "exact planner repair has no single physical-route attestation";
    }
    if (physical.divergesFrom(requested.providerName(), requested.getModel())) {
      return "exact planner repair route mismatch: approved "
          + requested.providerName()
          + "/"
          + requested.getModel()
          + " but physical attempt used "
          + physical.provider()
          + "/"
          + physical.model();
    }
    return null;
  }

  public static final class MismatchException extends IllegalStateException {
    public MismatchException(String message) {
      super(message);
    }
  }
}

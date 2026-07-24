package com.navneet.executespec.integration;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.reviewer.ReviewerResult;

/** Admission policy for the asynchronous, independently generated HTTP test suite. */
final class IndependentTestGenAdmissionPolicy {

  private IndependentTestGenAdmissionPolicy() {}

  /**
   * Independent generated tests are a validation stage, not a repair lottery. Do not spend another
   * planner plus N parallel test-worker calls when semantic review already failed or left known
   * unresolved defects. CLEAN/FIXED outputs still receive the suite, including PARTIAL runs whose
   * only missing evidence is that suite itself.
   */
  static boolean shouldKickoff(
      String status, Plan.RuntimeSurface runtimeSurface, ReviewerResult reviewResult) {
    if ("FAILED".equals(status)
        || runtimeSurface != Plan.RuntimeSurface.HTTP
        || reviewResult == null) return false;
    return reviewResult.overall() == ReviewerResult.Overall.CLEAN
        || reviewResult.overall() == ReviewerResult.Overall.FIXED_ISSUES;
  }
}

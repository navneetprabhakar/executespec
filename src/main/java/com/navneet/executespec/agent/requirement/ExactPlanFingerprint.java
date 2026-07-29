package com.navneet.executespec.agent.requirement;

/** Shared identity of the exact persisted legacy plan JSON bytes used by server and clients. */
public final class ExactPlanFingerprint {
  private ExactPlanFingerprint() {}

  public static String sha256(String exactPlanJson) {
    if (exactPlanJson == null || exactPlanJson.isBlank()) return null;
    return RequirementIrValidator.sha256(exactPlanJson);
  }
}

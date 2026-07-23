package com.navneet.executespec.testgen.model;

public enum TestPlanStatus {
  PENDING,
  PLANNING,
  GENERATING,
  COMPILED,
  EXECUTING,
  COMPLETED,
  /**
   * The workspace no longer matches the V277 fingerprint this plan was generated against (RR-1583),
   * so its results cannot be published as evidence for the current diff.
   */
  STALE,
  FAILED
}

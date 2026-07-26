package com.navneet.executespec.harness;

/** Keeps edit-burst verification and the final smoke gate from disagreeing about test execution. */
final class WorkerTestGatePolicy {

  private WorkerTestGatePolicy() {}

  static boolean shouldRunTests(boolean legacyTestGateEnabled, boolean autoVerifyEnabled) {
    return legacyTestGateEnabled || autoVerifyEnabled;
  }
}

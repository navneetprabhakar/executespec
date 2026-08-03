package com.navneet.executespec.harness;

/** Deterministic build/test smoke outcome used by task execution gates. */
record SmokeTestResult(
    boolean passed, int exitCode, String output, long latencyMs, boolean testsVerified) {}

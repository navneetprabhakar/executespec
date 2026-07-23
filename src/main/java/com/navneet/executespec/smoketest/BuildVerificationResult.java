package com.navneet.executespec.smoketest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical interpretation of buildProject evidence across worker, executor, and reviewer. */
public record BuildVerificationResult(Status status, Integer exitCode, String reason) {

  private static final Pattern EXIT = Pattern.compile("(?m)^exit=(-?\\d+)\\s*$");

  public enum Status {
    PASSED,
    FAILED,
    UNAVAILABLE,
    MALFORMED
  }

  public boolean passed() {
    return status == Status.PASSED;
  }

  public static BuildVerificationResult parse(String rawEvidence) {
    if (rawEvidence == null || rawEvidence.isBlank()) {
      return new BuildVerificationResult(Status.MALFORMED, null, "build evidence is empty");
    }
    String evidence = rawEvidence.replace("\\n", "\n");
    Matcher matcher = EXIT.matcher(evidence);
    Integer exit = matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    if (evidence.contains("smokeSignal=UNAVAILABLE") || evidence.contains("noOp=true")) {
      return new BuildVerificationResult(
          Status.UNAVAILABLE, exit, "no executable build command was available");
    }
    if (exit == null) {
      return new BuildVerificationResult(Status.MALFORMED, null, "build exit code is missing");
    }
    if (exit == 0) {
      return new BuildVerificationResult(Status.PASSED, 0, "executable build command passed");
    }
    return new BuildVerificationResult(Status.FAILED, exit, "build command failed");
  }
}

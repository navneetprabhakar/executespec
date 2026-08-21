package com.navneet.executespec.engine;

import java.nio.charset.StandardCharsets;

import org.apache.commons.codec.digest.DigestUtils;

/** Provider-neutral comparison of consecutive deterministic verification observations. */
public final class DeterministicFailureDelta {

  private DeterministicFailureDelta() {}

  public static Snapshot snapshot(
      int passed, int failed, int errors, int total, String failureEvidence) {
    String normalized =
        failureEvidence == null
            ? ""
            : failureEvidence.replaceAll("\\b\\d+(?:\\.\\d+)?s\\b", "<duration>").trim();
    return new Snapshot(
        passed,
        failed,
        errors,
        total,
        DigestUtils.sha256Hex(normalized.getBytes(StandardCharsets.UTF_8)));
  }

  public static Kind compare(Snapshot previous, Snapshot current) {
    if (current == null) throw new IllegalArgumentException("current snapshot required");
    if (current.failed() == 0 && current.errors() == 0) return Kind.PASSED;
    if (previous == null) return Kind.INITIAL_FAILURE;
    int previousRed = previous.failed() + previous.errors();
    int currentRed = current.failed() + current.errors();
    if (currentRed < previousRed) return Kind.IMPROVED;
    if (currentRed > previousRed) return Kind.REGRESSED;
    if (previous.signature().equals(current.signature())) return Kind.UNCHANGED;
    return Kind.CHANGED;
  }

  public enum Kind {
    INITIAL_FAILURE,
    IMPROVED,
    REGRESSED,
    CHANGED,
    UNCHANGED,
    PASSED
  }

  public record Snapshot(int passed, int failed, int errors, int total, String signature) {
    public Snapshot {
      if (passed < 0 || failed < 0 || errors < 0 || total < 0) {
        throw new IllegalArgumentException("verification counts must be non-negative");
      }
      if (signature == null || !signature.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("verification signature invalid");
      }
    }
  }
}

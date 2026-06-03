package com.navneet.executespec.deployment.preview.verify;

/**
 * Pure, deterministic classifier for a single app-boot verification attempt — the
 * verification-as-spine core of the preview rebuild (RL-2026-05-066, Phase 1 "verifyBoot").
 *
 * <p>This is the boot-verification analogue of {@code smoketest/TestOutputParser}: it takes the raw
 * signals from one boot attempt (did the image build, did the process/container start, did the
 * declared health endpoint respond, within the timeout) and produces a single deterministic verdict
 * + a model-facing reason. It performs NO I/O — the {@code PreviewVerifier} runner (the {@code
 * WorkspaceTestRunner} analogue, wired separately) gathers the signals; this class only judges
 * them. Spring-free so it is fully unit-testable in isolation.
 *
 * <p>Design intent: the agent authors a run-recipe and this gives a HARD, deterministic "boots +
 * responds" signal that feeds the BUILD scoring slot — replacing the LLM's guess about whether the
 * app works. A failed boot is true information (the code isn't done), not a preview bug.
 */
public final class BootHealthEvaluator {

  private BootHealthEvaluator() {}

  /** Raw signals from one boot attempt. {@code healthHttpStatus} is null when nothing responded. */
  public record BootSignals(
      boolean buildSucceeded,
      boolean started,
      Integer healthHttpStatus,
      String healthPath,
      long elapsedMs,
      long timeoutMs) {}

  /** Deterministic boot outcome. Ordered most-broken → healthy. */
  public enum Verdict {
    /** The image/build step failed — nothing to boot. */
    BUILD_FAILED,
    /** Built, but the process/container never came up. */
    DID_NOT_START,
    /** Started but the health endpoint never responded before the timeout. */
    TIMED_OUT,
    /** Started + responded, but with a non-success status (e.g. 5xx). */
    UNHEALTHY,
    /** Started + the health endpoint returned a success status. */
    HEALTHY
  }

  /** The verdict + a boolean convenience + a short model-facing reason. */
  public record BootVerification(Verdict verdict, boolean healthy, String reason) {}

  /**
   * A success HTTP status for a health probe: 2xx or 3xx (a redirect still means "the app is up").
   */
  private static boolean isSuccess(int status) {
    return status >= 200 && status < 400;
  }

  public static BootVerification evaluate(BootSignals s) {
    if (s == null) {
      return new BootVerification(Verdict.DID_NOT_START, false, "no boot signals captured");
    }
    if (!s.buildSucceeded()) {
      return new BootVerification(
          Verdict.BUILD_FAILED, false, "image/build step failed — nothing to boot");
    }
    String path = (s.healthPath() == null || s.healthPath().isBlank()) ? "/" : s.healthPath();
    if (!s.started()) {
      return new BootVerification(
          Verdict.DID_NOT_START, false, "built, but the process/container did not start");
    }
    if (s.healthHttpStatus() == null) {
      // Started but nothing answered. Distinguish "ran out of time" from "answered nothing".
      if (s.timeoutMs() > 0 && s.elapsedMs() >= s.timeoutMs()) {
        return new BootVerification(
            Verdict.TIMED_OUT,
            false,
            "started but " + path + " did not respond within " + s.timeoutMs() + "ms");
      }
      return new BootVerification(
          Verdict.TIMED_OUT, false, "started but " + path + " never responded");
    }
    int status = s.healthHttpStatus();
    if (isSuccess(status)) {
      return new BootVerification(Verdict.HEALTHY, true, "boots; " + path + " responded " + status);
    }
    return new BootVerification(
        Verdict.UNHEALTHY, false, "started but " + path + " responded " + status);
  }
}

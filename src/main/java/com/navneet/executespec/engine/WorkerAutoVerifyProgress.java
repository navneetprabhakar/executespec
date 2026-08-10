package com.navneet.executespec.engine;

import java.time.Duration;
import java.util.Optional;

import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.tool.ExecutionContext;

/** Keeps deterministic auto-verification delta policy out of the worker orchestration loop. */
final class WorkerAutoVerifyProgress {

  private DeterministicFailureDelta.Snapshot previous;
  private int unchangedStreak;

  WorkerAutoVerifyProgress() {
    this(null);
  }

  WorkerAutoVerifyProgress(DeterministicFailureDelta.Snapshot initialFailure) {
    this.previous = initialFailure;
  }

  Result observe(WorkspaceTestRunner runner, ExecutionContext context, int editsInBurst) {
    if (runner == null) return null;
    long started = System.nanoTime();
    Optional<WorkspaceTestRunner.DetailedResult> result =
        runner.runTestsDetailed(context.runId(), context.workspaceRoot());
    if (result.isEmpty() || result.get().summary().getTotalTests() == 0) return null;

    WorkspaceTestRunner.DetailedResult detail = result.get();
    TestExecutionSummary summary = detail.summary();
    DeterministicFailureDelta.Snapshot current =
        DeterministicFailureDelta.snapshot(
            summary.getPassed(),
            summary.getFailed(),
            summary.getErrors(),
            summary.getTotalTests(),
            detail.outputTail());
    DeterministicFailureDelta.Kind delta = DeterministicFailureDelta.compare(previous, current);
    previous = current;
    unchangedStreak = delta == DeterministicFailureDelta.Kind.UNCHANGED ? unchangedStreak + 1 : 0;

    String message =
        summary.getFailed() == 0 && summary.getErrors() == 0
            ? "NARROWEST AVAILABLE CHECK — "
                + WorkerCompletionPolicy.passingAutoVerifyObservation(
                    detail.command(), editsInBurst, summary.getPassed(), summary.getTotalTests())
            : "AUTO-VERIFY [NARROWEST_AVAILABLE] — the platform ran `"
                + detail.command()
                + "` after your last "
                + editsInBurst
                + " edit(s): "
                + summary.getFailed()
                + " failed / "
                + summary.getErrors()
                + " errored of "
                + summary.getTotalTests()
                + "."
                + WorkerAgent.verificationFailureEvidence(detail)
                + "\nFix these failures (or, if one is genuinely pre-existing and unrelated to"
                + " your change, say so explicitly) before moving on.";
    return new Result(
        delta,
        current.signature(),
        unchangedStreak,
        message,
        "NARROWEST_AVAILABLE",
        Duration.ofNanos(System.nanoTime() - started));
  }

  record Result(
      DeterministicFailureDelta.Kind delta,
      String signature,
      int unchangedStreak,
      String message,
      String commandClass,
      Duration elapsed) {
    boolean resetsProgress() {
      return delta == DeterministicFailureDelta.Kind.IMPROVED
          || delta == DeterministicFailureDelta.Kind.CHANGED
          || delta == DeterministicFailureDelta.Kind.PASSED;
    }
  }
}

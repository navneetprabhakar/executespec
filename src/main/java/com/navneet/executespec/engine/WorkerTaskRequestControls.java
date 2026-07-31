package com.navneet.executespec.engine;

/** Central copy/normalization rules for worker attempts and retries. */
public final class WorkerTaskRequestControls {

  private WorkerTaskRequestControls() {}

  public static WorkerTaskRequest withRuntimeDefaults(
      WorkerTaskRequest request, long startEpochMs, long defaultTokenCap, double defaultCostCap) {
    boolean terminal = Boolean.TRUE.equals(request.terminalTaskMode());
    return request.toBuilder()
        .workerStartedAtEpochMs(
            request.workerStartedAtEpochMs() != null && request.workerStartedAtEpochMs() > 0
                ? request.workerStartedAtEpochMs()
                : startEpochMs)
        .taskTotalTokenCap(
            minPositive(request.taskTotalTokenCap(), terminal ? null : positive(defaultTokenCap)))
        .dollarCap(minPositive(request.dollarCap(), terminal ? null : positive(defaultCostCap)))
        .build();
  }

  public static WorkerTaskRequest preserveAcrossRetry(
      WorkerTaskRequest primary, WorkerTaskRequest retry) {
    return retry.toBuilder()
        .taskTotalTokenCap(primary.taskTotalTokenCap())
        .dollarCap(primary.dollarCap())
        .workerStartedAtEpochMs(primary.workerStartedAtEpochMs())
        .strictProvider(primary.strictProvider())
        .zeroWriteSalvageAllowed(primary.zeroWriteSalvageAllowed())
        .build();
  }

  private static Long minPositive(Long a, Long b) {
    if (a == null || a <= 0) return b;
    if (b == null || b <= 0) return a;
    return Math.min(a, b);
  }

  private static Double minPositive(Double a, Double b) {
    if (a == null || a <= 0) return b;
    if (b == null || b <= 0) return a;
    return Math.min(a, b);
  }

  private static Long positive(long value) {
    return value > 0 ? value : null;
  }

  private static Double positive(double value) {
    return value > 0 ? value : null;
  }
}

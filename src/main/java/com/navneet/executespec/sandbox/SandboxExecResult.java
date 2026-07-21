package com.navneet.executespec.sandbox;

import java.time.Duration;

/**
 * The outcome of one {@link SandboxExecRequest}.
 *
 * <p>The status is the load-bearing field, not the exit code. A caller that reads {@link #exitCode}
 * without reading {@link #status} will read {@code -1} for a sandbox that never ran the command at
 * all and may mistake it for a failing command. {@link #completed()} exists so the correct check is
 * the short one.
 *
 * <p><strong>Why this is a value and not an exception for the unavailable case.</strong> The
 * alternative to a sandbox is host execution. If unavailability arrived as an exception, the
 * natural shape of a call site is a {@code catch} that falls back — which is precisely the outcome
 * this seam exists to prevent. An explicit {@link Status#UNAVAILABLE} makes "the sandbox could not
 * run this" something the caller has to handle in the same expression it handles success, and the
 * honest handling is to fail closed.
 *
 * @param status what actually happened; see {@link Status}
 * @param exitCode the command's exit status, meaningful only when {@link Status#COMPLETED}
 * @param stdout captured standard output, bounded
 * @param stderr captured standard error, bounded
 * @param outputTruncated true when either stream hit the capture bound
 * @param duration wall-clock spent, for cost and timeout attribution
 * @param detail a short non-secret explanation for the non-completed statuses; null when COMPLETED
 */
public record SandboxExecResult(
    Status status,
    int exitCode,
    String stdout,
    String stderr,
    boolean outputTruncated,
    Duration duration,
    String detail) {

  /** What happened to the command. */
  public enum Status {
    /** The command ran to completion inside the sandbox and {@code exitCode} is its exit status. */
    COMPLETED,

    /**
     * The wall-clock ceiling elapsed first. The client process tree was destroyed on the host; see
     * the class note on {@link #TIMED_OUT_IN_CONTAINER_CAVEAT}.
     */
    TIMED_OUT,

    /**
     * No sandbox executed anything: the runtime is disabled, the container engine is absent, or
     * dispatch is switched off. This is never a statement about the command. A caller must fail
     * closed; it must not run the command on the host instead.
     */
    UNAVAILABLE,

    /**
     * The sandbox exists but is not in a state that may execute — it is still provisioning, has
     * been asked to cancel, or is already terminal. Also never a statement about the command.
     */
    REFUSED
  }

  /**
   * What a {@link Status#TIMED_OUT} does and does not guarantee.
   *
   * <p>The runtime destroys the container-CLI client process and its descendants on the host. That
   * reliably stops the host-side process tree and releases this thread. It does <em>not</em> prove
   * the process inside the container is dead: the exec'd process is a child of the container's own
   * init, not of the client. It remains bounded by the container's CPU, memory and PID ceilings and
   * by the container wall-clock, and it is definitively destroyed when the sandbox is released.
   *
   * <p>The correct caller response to a timeout is therefore to treat the sandbox as spent and
   * release it, not to issue another command into it.
   */
  public static final String TIMED_OUT_IN_CONTAINER_CAVEAT =
      "host client tree destroyed; in-container process bounded by container limits until release";

  /** True only when the command actually ran and produced an exit status. */
  public boolean completed() {
    return status == Status.COMPLETED;
  }

  /**
   * True when the command ran and succeeded. The only expression that should gate a green claim.
   */
  public boolean succeeded() {
    return completed() && exitCode == 0;
  }

  /**
   * True when no sandbox executed the command. Distinct from a failing command, and the case in
   * which falling back to host execution would defeat the containment this seam provides.
   */
  public boolean unexecuted() {
    return status == Status.UNAVAILABLE || status == Status.REFUSED;
  }

  public static SandboxExecResult completed(
      int exitCode, String stdout, String stderr, boolean truncated, Duration duration) {
    return new SandboxExecResult(
        Status.COMPLETED, exitCode, stdout, stderr, truncated, duration, null);
  }

  public static SandboxExecResult timedOut(
      String stdout, String stderr, boolean truncated, Duration duration) {
    return new SandboxExecResult(
        Status.TIMED_OUT, -1, stdout, stderr, truncated, duration, TIMED_OUT_IN_CONTAINER_CAVEAT);
  }

  public static SandboxExecResult unavailable(String detail) {
    return new SandboxExecResult(Status.UNAVAILABLE, -1, "", "", false, Duration.ZERO, detail);
  }

  public static SandboxExecResult refused(String detail) {
    return new SandboxExecResult(Status.REFUSED, -1, "", "", false, Duration.ZERO, detail);
  }
}

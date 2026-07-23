package com.navneet.executespec.sandbox;

/**
 * The one abstraction over sandbox execution.
 *
 * <p>Every path that starts, inspects or destroys a sandbox goes through an implementation of this
 * interface, reached only via {@link RunSandboxRuntimeDispatcher}. {@code
 * SandboxRuntimeDispatchBoundaryTest} fails the build if a controller or agent executes a container
 * or process directly instead.
 *
 * <p>Implementations must not perform lifecycle persistence themselves. The dispatcher owns the
 * fenced {@link RunSandboxStore} transitions, so a runtime cannot advertise a state it does not
 * hold the lease for.
 */
public interface RunSandboxRuntime {

  /** The isolation profile this runtime implements. */
  SandboxIsolationProfile profile();

  /**
   * Whether this runtime can execute right now. False when its flag is off or its prerequisites
   * (for example a container engine) are absent.
   */
  boolean available();

  /**
   * Creates the sandbox. Must be safe to call again after a crash: an implementation reattaches to
   * an existing handle rather than leaking a second sandbox.
   *
   * @return the handle and image digest of what was actually started
   */
  Provisioned provision(RunSandbox sandbox, SandboxRuntimeSpec spec);

  /**
   * Runs one command inside an already-provisioned sandbox and waits for it, bounded.
   *
   * <p>This is the method that makes the sandbox useful rather than merely allocatable: without it,
   * the only way to run a customer's build or test command is on the application host, where it
   * executes as the service account with the service account's network reach. Provisioning a
   * network-isolated container and then not being able to run anything in it is not containment.
   *
   * <p>Contract every implementation must honour:
   *
   * <ul>
   *   <li>{@code request.argv()} is executed as a vector. An implementation must never join it into
   *       a string for a shell.
   *   <li>The result is bounded. Output is capped and a runaway child cannot exhaust the heap.
   *   <li>{@code request.timeout()} must be reachable — an implementation that drains the child's
   *       output to EOF before waiting has no timeout at all — and expiry must destroy the host
   *       process tree, not only the direct child.
   *   <li>Unavailability is reported as {@link SandboxExecResult.Status#UNAVAILABLE}, never as a
   *       zero exit code and never by executing on the host instead.
   * </ul>
   *
   * <p>Implementations do not check ownership; {@link RunSandboxRuntimeDispatcher#exec} enforces
   * the lease fence before calling, for the same reason runtimes do not write lifecycle state.
   */
  SandboxExecResult exec(RunSandbox sandbox, SandboxExecRequest request);

  /**
   * Destroys the sandbox and everything it spawned. Must be idempotent and must succeed for a
   * handle that is already gone, because reconciliation calls it for orphans whose owning JVM died.
   * Must never delete lifecycle rows or retained artifacts.
   */
  void destroy(RunSandbox sandbox);

  /** Whether the underlying sandbox is still live. Used by reconciliation to find orphans. */
  boolean alive(RunSandbox sandbox);

  /** What a runtime actually started. */
  record Provisioned(String runtimeHandle, String imageDigest) {}

  /** Thrown when a runtime cannot execute; never used to report an ordinary sandbox failure. */
  class RuntimeUnavailableException extends RuntimeException {
    public RuntimeUnavailableException(String message) {
      super(message);
    }
  }
}

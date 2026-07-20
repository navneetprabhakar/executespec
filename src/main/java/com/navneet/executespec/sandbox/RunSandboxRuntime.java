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

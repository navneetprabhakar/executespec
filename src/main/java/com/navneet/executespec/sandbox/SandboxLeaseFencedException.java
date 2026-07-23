package com.navneet.executespec.sandbox;

import java.util.UUID;

/**
 * Thrown when a caller presents a lease that is no longer the live fence for a sandbox — the JVM
 * that held it was superseded by a takeover, or the lease expired. The caller must stop acting on
 * the sandbox; it no longer owns it.
 */
public class SandboxLeaseFencedException extends RuntimeException {

  private final transient UUID sandboxId;

  public SandboxLeaseFencedException(UUID sandboxId) {
    super("sandbox lease is no longer live for " + sandboxId);
    this.sandboxId = sandboxId;
  }

  public UUID sandboxId() {
    return sandboxId;
  }
}

package com.navneet.executespec.sandbox;

import org.springframework.stereotype.Component;

/**
 * The default runtime: it executes nothing.
 *
 * <p>This is what the dispatcher resolves to whenever no hardened runtime is both selected and
 * available, which is the state the whole of Phase 5 ships in. {@link #provision} refuses rather
 * than silently doing nothing, so a caller can never believe it has a sandbox it does not have.
 *
 * <p>{@link #destroy} succeeds, because cleanup and reconciliation must keep working when execution
 * is disabled.
 */
@Component
public class NotEnabledSandboxRuntime implements RunSandboxRuntime {

  @Override
  public SandboxIsolationProfile profile() {
    return SandboxIsolationProfile.NOT_ENABLED;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public Provisioned provision(RunSandbox sandbox, SandboxRuntimeSpec spec) {
    throw new RuntimeUnavailableException(
        "no sandbox runtime is enabled; sandbox " + sandbox.id() + " cannot be provisioned");
  }

  @Override
  public void destroy(RunSandbox sandbox) {
    // Nothing was started, so nothing needs destroying. Deliberately a success: reconciliation
    // and cancellation must be able to settle rows created before the runtime was disabled.
  }

  @Override
  public boolean alive(RunSandbox sandbox) {
    return false;
  }
}

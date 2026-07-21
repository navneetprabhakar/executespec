package com.navneet.executespec.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The single entry point to sandbox execution.
 *
 * <p>It resolves which {@link RunSandboxRuntime} applies, enforces the dispatch flag, and pairs
 * every runtime call with the fenced {@link RunSandboxStore} transition that records it. Runtimes
 * never write lifecycle state and callers never touch a runtime directly.
 *
 * <p>Flag separation is deliberate and is the rollback contract:
 *
 * <ul>
 *   <li>{@code sandbox.runtime.dispatch.enabled} gates {@link #provision} and {@link #exec} — the
 *       two operations that start work. Turning it off stops new execution; it does not strand live
 *       sandboxes, because teardown is deliberately outside the gate.
 *   <li>{@link #release} and {@link #reconcileOrphan} never consult a flag, so cancellation,
 *       teardown and orphan cleanup keep working while dispatch is disabled.
 *   <li>{@code sandbox.runtime.container-strict-v1.enabled} selects the hardened runtime. Off
 *       resolves to {@link NotEnabledSandboxRuntime}, which executes nothing.
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunSandboxRuntimeDispatcher {

  public static final String FLAG_DISPATCH = "sandbox.runtime.dispatch.enabled";
  public static final String FLAG_CONTAINER_STRICT_V1 =
      "sandbox.runtime.container-strict-v1.enabled";

  private final RunSandboxStore store;
  private final FeatureFlagService featureFlags;
  private final List<RunSandboxRuntime> runtimes;

  /** The profile a new sandbox would be admitted under given the current flags. */
  public SandboxIsolationProfile selectedProfile() {
    if (featureFlags.isEnabled(FLAG_CONTAINER_STRICT_V1)
        && runtimeFor(SandboxIsolationProfile.CONTAINER_STRICT_V1)
            .filter(RunSandboxRuntime::available)
            .isPresent()) {
      return SandboxIsolationProfile.CONTAINER_STRICT_V1;
    }
    return SandboxIsolationProfile.NOT_ENABLED;
  }

  public boolean dispatchEnabled() {
    return featureFlags.isEnabled(FLAG_DISPATCH);
  }

  /**
   * Starts the sandbox under the caller's live lease and records READY. A runtime failure is
   * classified and persisted as a terminal FAILED rather than swallowed.
   */
  public RunSandbox provision(RunSandbox sandbox, RunSandbox.Lease lease, SandboxRuntimeSpec spec) {
    if (!dispatchEnabled()) {
      throw new RunSandboxRuntime.RuntimeUnavailableException(
          FLAG_DISPATCH + " is off; sandbox " + sandbox.id() + " will not be provisioned");
    }
    RunSandboxRuntime runtime = requireRuntime(sandbox.isolationProfile());
    if (!spec.configDigest().equals(sandbox.configDigest())) {
      // The admitted policy and the policy about to be enforced must be the same object.
      store.fail(lease, "CONFIG_DIGEST_MISMATCH", "resolved runtime config differs from admission");
      throw new IllegalStateException("sandbox config digest mismatch for " + sandbox.id());
    }
    try {
      RunSandboxRuntime.Provisioned started = runtime.provision(sandbox, spec);
      return store.markReady(lease, started.runtimeHandle(), started.imageDigest());
    } catch (SandboxLeaseFencedException fenced) {
      // Ownership was lost mid-provision. Do not write state we no longer own; destroy what this
      // JVM started so the takeover does not inherit a stray sandbox, then rethrow.
      log.warn("sandbox {} lost its lease during provisioning", sandbox.id(), fenced);
      safeDestroy(runtime, sandbox);
      throw fenced;
    } catch (RuntimeException failure) {
      log.error("sandbox {} failed to provision", sandbox.id(), failure);
      safeDestroy(runtime, sandbox);
      store.fail(lease, classify(failure), failure.getMessage());
      throw failure;
    }
  }

  /**
   * Runs one command inside a sandbox this JVM currently owns.
   *
   * <p>This is the seam that makes provisioning worth doing. Without it the only way to run a
   * customer's build or test command is on the application host, as the service account, with the
   * host's network reach — including the cloud metadata endpoint, which a stripped environment does
   * not hide.
   *
   * <p>Three gates, in this order, each for a different reason:
   *
   * <ol>
   *   <li>{@link #FLAG_DISPATCH}. Turning dispatch off must stop new execution. It stops new
   *       commands as well as new sandboxes; it does not stop {@link #release} or {@link
   *       #reconcileOrphan}, so a live sandbox is never stranded by the switch that silences it.
   *   <li>The lease fence, via {@link RunSandboxStore#authorizeExec}. A JVM that lost a takeover
   *       must not execute into the container the new owner is now responsible for. A fenced caller
   *       gets {@link SandboxLeaseFencedException} rather than a status, because "you no longer own
   *       this" is not a command outcome and must not be reachable by reading an exit code.
   *   <li>State. Only READY executes. Provisioning, cancelling and terminal sandboxes are {@link
   *       SandboxExecResult.Status#REFUSED}.
   * </ol>
   *
   * <p>Unlike {@link #provision}, an unavailable runtime here does <em>not</em> fail the sandbox: a
   * command that could not run is not evidence that the sandbox is broken, and failing the row
   * would destroy a workspace over a transient engine hiccup.
   *
   * @return what happened; callers must read {@link SandboxExecResult#status()} and must never
   *     treat {@link SandboxExecResult#unexecuted()} as licence to run the command on the host
   */
  public SandboxExecResult exec(
      RunSandbox sandbox, RunSandbox.Lease lease, SandboxExecRequest request) {
    if (!dispatchEnabled()) {
      return SandboxExecResult.unavailable(FLAG_DISPATCH + " is off; nothing was executed");
    }
    RunSandboxStore.SandboxExecAuthorization authorization = store.authorizeExec(lease);
    if (authorization == RunSandboxStore.SandboxExecAuthorization.FENCED) {
      log.warn("sandbox {} refused a command from a stale or foreign owner", sandbox.id());
      throw new SandboxLeaseFencedException(sandbox.id());
    }
    if (authorization == RunSandboxStore.SandboxExecAuthorization.NOT_READY) {
      return SandboxExecResult.refused("sandbox " + sandbox.id() + " is not READY");
    }
    RunSandboxRuntime runtime = requireRuntime(sandbox.isolationProfile());
    try {
      return runtime.exec(sandbox, request);
    } catch (RunSandboxRuntime.RuntimeUnavailableException unavailable) {
      return SandboxExecResult.unavailable(unavailable.getMessage());
    }
  }

  /**
   * Tears the sandbox down and settles it in the given terminal state. Intentionally flag-free:
   * this must work when dispatch is disabled.
   */
  public RunSandbox release(RunSandbox sandbox, RunSandbox.Lease lease, SandboxState terminal) {
    RunSandbox releasing = store.beginRelease(lease);
    RunSandboxRuntime runtime = requireRuntime(sandbox.isolationProfile());
    try {
      runtime.destroy(releasing);
    } catch (RuntimeException failure) {
      log.error("sandbox {} teardown failed", sandbox.id(), failure);
      // Teardown failure is a real failure, not a clean stop: a container may still be live.
      store.fail(lease, "TEARDOWN_FAILED", failure.getMessage());
      throw failure;
    }
    return store.finishRelease(lease, terminal);
  }

  /**
   * Settles a sandbox whose owning JVM is gone. Acquires the expired lease first so exactly one
   * reconciler can act, destroys any surviving runtime, then records a terminal state. Artifacts
   * and the lifecycle row are preserved; only the runtime is destroyed.
   */
  public Optional<RunSandbox> reconcileOrphan(
      RunSandbox orphan, String owner, Duration leaseDuration) {
    Optional<RunSandbox.Lease> claimed = store.tryAcquire(orphan.id(), owner, leaseDuration);
    if (claimed.isEmpty()) {
      // Another reconciler won, or the sandbox became terminal. Both are correct outcomes.
      return Optional.empty();
    }
    RunSandbox.Lease lease = claimed.get();
    RunSandbox current = store.get(orphan.id()).orElseThrow();
    RunSandboxRuntime runtime = requireRuntime(current.isolationProfile());
    try {
      runtime.destroy(current);
    } catch (RuntimeException failure) {
      log.error("orphan sandbox {} teardown failed", orphan.id(), failure);
      return Optional.of(store.fail(lease, "ORPHAN_TEARDOWN_FAILED", failure.getMessage()));
    }
    SandboxState terminal =
        current.state() == SandboxState.CANCEL_REQUESTED
            ? SandboxState.CANCELLED
            : SandboxState.FAILED;
    if (current.state() != SandboxState.RELEASING) {
      store.beginRelease(lease);
    }
    return Optional.of(
        terminal == SandboxState.CANCELLED
            ? store.finishRelease(lease, SandboxState.CANCELLED)
            : store.fail(lease, "ORPHANED_RUNTIME", "owning runtime disappeared"));
  }

  private void safeDestroy(RunSandboxRuntime runtime, RunSandbox sandbox) {
    try {
      runtime.destroy(sandbox);
    } catch (RuntimeException cleanupFailure) {
      // Reported, never swallowed silently; the caller's original failure must still surface.
      log.error("sandbox {} cleanup after failure did not complete", sandbox.id(), cleanupFailure);
    }
  }

  private RunSandboxRuntime requireRuntime(SandboxIsolationProfile profile) {
    return runtimeFor(profile)
        .orElseThrow(
            () ->
                new RunSandboxRuntime.RuntimeUnavailableException(
                    "no runtime registered for isolation profile " + profile));
  }

  private Optional<RunSandboxRuntime> runtimeFor(SandboxIsolationProfile profile) {
    return runtimes.stream().filter(runtime -> runtime.profile() == profile).findFirst();
  }

  private static String classify(RuntimeException failure) {
    if (failure instanceof RunSandboxRuntime.RuntimeUnavailableException) {
      return "RUNTIME_UNAVAILABLE";
    }
    if (failure instanceof IllegalArgumentException) {
      return "INVALID_RUNTIME_SPEC";
    }
    return "RUNTIME_ERROR";
  }
}

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
      // RR-327 — ownership was lost mid-provision. This branch used to destroy the container, which
      // is precisely the wrong action: a failed fenced transition is proof that ANOTHER JVM now
      // owns
      // this sandbox, and the takeover deliberately reattaches to the container of the same name.
      // Cleaning up here deletes the winner's live container. Nothing leaks by leaving it: the new
      // owner adopts the very container this JVM started, and if that owner also dies, orphan
      // reconciliation claims the lease first and destroys it under a fence it holds.
      log.warn(
          "sandbox {} lost its lease during provisioning; leaving the container to its new owner",
          sandbox.id(),
          fenced);
      throw fenced;
    } catch (RuntimeException failure) {
      log.error("sandbox {} failed to provision", sandbox.id(), failure);
      // Only clean up while this JVM still demonstrably owns the sandbox. A check is not a hold —
      // the lease can lapse between here and the destroy — which is why the runtime independently
      // proves the container's fencing labels before removing anything.
      if (!store.holdsLiveLease(lease)) {
        log.warn(
            "sandbox {} failed to provision and this JVM no longer holds its lease; leaving both"
                + " the container and the lifecycle row to the current owner",
            sandbox.id());
        throw failure;
      }
      RunSandbox releasing =
          store.beginRelease(lease, SandboxState.FAILED, classify(failure), failure.getMessage());
      try {
        runtime.destroy(releasing);
      } catch (RuntimeException cleanupFailure) {
        log.error(
            "sandbox {} cleanup after provisioning failure did not complete",
            sandbox.id(),
            cleanupFailure);
        store.recordCleanupFailure(lease, cleanupFailure.getMessage());
        failure.addSuppressed(cleanupFailure);
        throw failure;
      }
      store.completeRelease(lease);
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
    RunSandbox releasing =
        sandbox.state() == SandboxState.RELEASING
            ? sandbox
            : store.beginRelease(lease, terminal, null, null);
    if (releasing.state() == terminal) {
      return releasing;
    }
    try {
      requireRuntime(sandbox.isolationProfile()).destroy(releasing);
    } catch (RuntimeException failure) {
      log.error("sandbox {} teardown failed", sandbox.id(), failure);
      // Keep RELEASING and expire the lease immediately. A terminal row would falsely assert that
      // the runtime is gone and would disappear from every cleanup candidate query.
      store.recordCleanupFailure(lease, failure.getMessage());
      throw failure;
    }
    return store.completeRelease(lease);
  }

  /**
   * Destroys a sandbox after an execution failure and records the classified failure.
   *
   * <p>A timed-out {@code docker exec} client does not prove its in-container process died. The
   * sandbox must therefore be destroyed before the row becomes terminal; merely marking FAILED
   * would leave the process alive until the container's outer sleep exits.
   */
  public RunSandbox failAndRelease(
      RunSandbox sandbox, RunSandbox.Lease lease, String failureClass, String detail) {
    RunSandbox releasing =
        sandbox.state() == SandboxState.RELEASING
            ? sandbox
            : store.beginRelease(lease, SandboxState.FAILED, failureClass, detail);
    if (releasing.state() == SandboxState.FAILED) {
      return releasing;
    }
    try {
      requireRuntime(sandbox.isolationProfile()).destroy(releasing);
    } catch (RuntimeException failure) {
      log.error("sandbox {} failure teardown failed", sandbox.id(), failure);
      store.recordCleanupFailure(lease, failure.getMessage());
      throw failure;
    }
    return store.completeRelease(lease);
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
    RunSandbox releasing =
        current.state() == SandboxState.RELEASING
            ? current
            : store.beginRelease(
                lease,
                current.state() == SandboxState.CANCEL_REQUESTED
                    ? SandboxState.CANCELLED
                    : SandboxState.FAILED,
                current.state() == SandboxState.CANCEL_REQUESTED ? null : "ORPHANED_RUNTIME",
                current.state() == SandboxState.CANCEL_REQUESTED
                    ? null
                    : "owning runtime disappeared");
    try {
      requireRuntime(current.isolationProfile()).destroy(releasing);
    } catch (RuntimeException failure) {
      log.error("orphan sandbox {} teardown failed", orphan.id(), failure);
      return Optional.of(store.recordCleanupFailure(lease, failure.getMessage()));
    }
    return Optional.of(store.completeRelease(lease));
  }

  /**
   * Claims and destroys an expired sandbox before recording EXPIRED.
   *
   * <p>Expiry must not call {@code forceTerminal} for a sandbox that ever had a runtime handle:
   * doing so makes the row terminal before destroying the container, after which orphan
   * reconciliation can no longer discover the leaked runtime.
   */
  public Optional<RunSandbox> reconcileExpired(
      RunSandbox expired, String owner, Duration leaseDuration) {
    Optional<RunSandbox.Lease> claimed = store.tryAcquire(expired.id(), owner, leaseDuration);
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    RunSandbox.Lease lease = claimed.get();
    RunSandbox current = store.get(expired.id()).orElseThrow();
    RunSandbox releasing =
        current.state() == SandboxState.RELEASING
            ? current
            : store.beginRelease(lease, SandboxState.EXPIRED, null, null);
    try {
      requireRuntime(current.isolationProfile()).destroy(releasing);
      return Optional.of(store.completeRelease(lease));
    } catch (RuntimeException failure) {
      log.error("expired sandbox {} teardown failed", expired.id(), failure);
      return Optional.of(store.recordCleanupFailure(lease, failure.getMessage()));
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

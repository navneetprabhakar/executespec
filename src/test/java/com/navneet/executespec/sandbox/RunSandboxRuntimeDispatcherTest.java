package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Flag independence, fencing behaviour and failure classification at the one dispatch seam. */
class RunSandboxRuntimeDispatcherTest {

  private RunSandboxStore store;
  private FeatureFlagService flags;
  private RecordingRuntime strict;
  private RunSandboxRuntimeDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    store = mock(RunSandboxStore.class);
    flags = mock(FeatureFlagService.class);
    strict = new RecordingRuntime();
    dispatcher =
        new RunSandboxRuntimeDispatcher(
            store, flags, List.of(new NotEnabledSandboxRuntime(), strict));
  }

  @Test
  void bothRuntimeFlagsOffResolvesToTheNoOpProfile() {
    assertThat(dispatcher.selectedProfile()).isEqualTo(SandboxIsolationProfile.NOT_ENABLED);
    assertThat(dispatcher.dispatchEnabled()).isFalse();
  }

  @Test
  void strictProfileIsSelectedOnlyWhenItsFlagIsOnAndTheRuntimeIsAvailable() {
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1)).thenReturn(true);
    strict.available = false;
    assertThat(dispatcher.selectedProfile()).isEqualTo(SandboxIsolationProfile.NOT_ENABLED);

    strict.available = true;
    assertThat(dispatcher.selectedProfile()).isEqualTo(SandboxIsolationProfile.CONTAINER_STRICT_V1);
  }

  @Test
  void provisionIsRefusedWhileTheDispatchFlagIsOff() {
    RunSandbox sandbox = sandbox(SandboxState.PROVISIONING);
    assertThatThrownBy(() -> dispatcher.provision(sandbox, lease(sandbox), spec()))
        .isInstanceOf(RunSandboxRuntime.RuntimeUnavailableException.class)
        .hasMessageContaining(RunSandboxRuntimeDispatcher.FLAG_DISPATCH);
    assertThat(strict.provisioned).isEmpty();
  }

  @Test
  void theNoOpRuntimeRefusesToProvisionRatherThanPretendingItStartedSomething() {
    RunSandbox sandbox = sandbox(SandboxState.PROVISIONING);
    assertThatThrownBy(() -> new NotEnabledSandboxRuntime().provision(sandbox, spec()))
        .isInstanceOf(RunSandboxRuntime.RuntimeUnavailableException.class);
  }

  @Test
  void releaseAndReconciliationKeepWorkingWhileDispatchIsDisabled() {
    // This is the rollback contract: disabling execution must not strand a live sandbox.
    RunSandbox sandbox = sandbox(SandboxState.READY);
    RunSandbox.Lease lease = lease(sandbox);
    RunSandbox releasing = sandbox(SandboxState.RELEASING, SandboxState.CANCELLED, null);
    when(store.beginRelease(lease, SandboxState.CANCELLED, null, null)).thenReturn(releasing);
    when(store.completeRelease(lease)).thenReturn(sandbox(SandboxState.CANCELLED));

    assertThat(dispatcher.dispatchEnabled()).isFalse();
    assertThat(dispatcher.release(sandbox, lease, SandboxState.CANCELLED).state())
        .isEqualTo(SandboxState.CANCELLED);
    assertThat(strict.destroyed).containsExactly(releasing.id());
  }

  @Test
  void aTeardownFailureStaysReleasingAndIsImmediatelyRetryable() {
    RunSandbox sandbox = sandbox(SandboxState.READY);
    RunSandbox.Lease lease = lease(sandbox);
    when(store.beginRelease(lease, SandboxState.TERMINATED, null, null))
        .thenReturn(sandbox(SandboxState.RELEASING, SandboxState.TERMINATED, null));
    strict.destroyFailure = new IllegalStateException("container still running");

    assertThatThrownBy(() -> dispatcher.release(sandbox, lease, SandboxState.TERMINATED))
        .isInstanceOf(IllegalStateException.class);
    verify(store).recordCleanupFailure(lease, "container still running");
    verify(store, never()).completeRelease(any());
  }

  @Test
  void commandTimeoutDestroysRuntimeBeforeRecordingTheFailure() {
    RunSandbox sandbox = sandbox(SandboxState.READY);
    RunSandbox.Lease lease = lease(sandbox);
    RunSandbox releasing = sandbox(SandboxState.RELEASING, SandboxState.FAILED, "COMMAND_TIMEOUT");
    when(store.beginRelease(
            lease, SandboxState.FAILED, "COMMAND_TIMEOUT", "sandbox command exceeded PT1M"))
        .thenReturn(releasing);
    when(store.completeRelease(lease)).thenReturn(sandbox(SandboxState.FAILED));

    assertThat(
            dispatcher.failAndRelease(
                sandbox, lease, "COMMAND_TIMEOUT", "sandbox command exceeded PT1M"))
        .extracting(RunSandbox::state)
        .isEqualTo(SandboxState.FAILED);
    assertThat(strict.destroyed).containsExactly(releasing.id());
  }

  @Test
  void aConfigDigestMismatchFailsTheSandboxInsteadOfEnforcingUnadmittedPolicy() {
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_DISPATCH)).thenReturn(true);
    RunSandbox sandbox = sandbox(SandboxState.PROVISIONING, "sha256:a-different-policy");
    RunSandbox.Lease lease = lease(sandbox);

    assertThatThrownBy(() -> dispatcher.provision(sandbox, lease, spec()))
        .isInstanceOf(IllegalStateException.class);
    verify(store).fail(eq(lease), eq("CONFIG_DIGEST_MISMATCH"), anyString());
    assertThat(strict.provisioned).isEmpty();
  }

  /**
   * RR-327 — the stale owner must not clean up. A failed fenced transition proves another JVM owns
   * this sandbox, and the takeover reattaches to the container of the same name, so destroying it
   * here deletes the winner's live container. Nothing leaks: the new owner adopts it.
   */
  @Test
  void losingTheLeaseMidProvisionDestroysNothingBecauseTheContainerIsNowSomeoneElses() {
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_DISPATCH)).thenReturn(true);
    RunSandbox sandbox = sandbox(SandboxState.PROVISIONING);
    RunSandbox.Lease lease = lease(sandbox);
    when(store.markReady(any(), anyString(), anyString()))
        .thenThrow(new SandboxLeaseFencedException(sandbox.id()));

    assertThatThrownBy(() -> dispatcher.provision(sandbox, lease, spec()))
        .isInstanceOf(SandboxLeaseFencedException.class);
    assertThat(strict.destroyed).isEmpty();
    verify(store, never()).fail(any(), anyString(), anyString());
  }

  /**
   * A provisioning failure only licenses cleanup while this JVM still demonstrably owns the
   * sandbox. With the lease gone, both the container and the lifecycle row belong to the current
   * owner.
   */
  @Test
  void aProvisionFailureAfterTheLeaseLapsedTouchesNeitherTheContainerNorTheRow() {
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_DISPATCH)).thenReturn(true);
    RunSandbox sandbox = sandbox(SandboxState.PROVISIONING);
    RunSandbox.Lease lease = lease(sandbox);
    strict.provisionFailure = new IllegalStateException("container did not start");
    when(store.holdsLiveLease(lease)).thenReturn(false);

    assertThatThrownBy(() -> dispatcher.provision(sandbox, lease, spec()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(strict.destroyed).isEmpty();
    verify(store, never()).fail(any(), anyString(), anyString());
  }

  @Test
  void aProvisionFailureUnderALiveLeaseStillTearsDownAndFailsTheRow() {
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_DISPATCH)).thenReturn(true);
    RunSandbox sandbox = sandbox(SandboxState.PROVISIONING);
    RunSandbox.Lease lease = lease(sandbox);
    strict.provisionFailure = new IllegalStateException("container did not start");
    when(store.holdsLiveLease(lease)).thenReturn(true);
    when(store.beginRelease(lease, SandboxState.FAILED, "RUNTIME_ERROR", "container did not start"))
        .thenReturn(sandbox(SandboxState.RELEASING, SandboxState.FAILED, "RUNTIME_ERROR"));

    assertThatThrownBy(() -> dispatcher.provision(sandbox, lease, spec()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(strict.destroyed).containsExactly(sandbox.id());
    verify(store).completeRelease(lease);
  }

  @Test
  void aProvisionCleanupFailurePersistsRetryableIntentInsteadOfTerminalFailure() {
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_DISPATCH)).thenReturn(true);
    RunSandbox sandbox = sandbox(SandboxState.PROVISIONING);
    RunSandbox.Lease lease = lease(sandbox);
    strict.provisionFailure = new IllegalStateException("container did not start");
    strict.destroyFailure = new IllegalStateException("daemon unavailable");
    when(store.holdsLiveLease(lease)).thenReturn(true);
    when(store.beginRelease(lease, SandboxState.FAILED, "RUNTIME_ERROR", "container did not start"))
        .thenReturn(sandbox(SandboxState.RELEASING, SandboxState.FAILED, "RUNTIME_ERROR"));

    assertThatThrownBy(() -> dispatcher.provision(sandbox, lease, spec()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("container did not start");
    verify(store).recordCleanupFailure(lease, "daemon unavailable");
    verify(store, never()).completeRelease(any());
  }

  @Test
  void reconciliationSkipsSandboxesAnotherNodeAlreadyClaimed() {
    RunSandbox orphan = sandbox(SandboxState.READY);
    when(store.tryAcquire(eq(orphan.id()), anyString(), any())).thenReturn(Optional.empty());

    assertThat(dispatcher.reconcileOrphan(orphan, "jvm-b", Duration.ofMinutes(1))).isEmpty();
    assertThat(strict.destroyed).isEmpty();
  }

  @Test
  void aReconciledCancelRequestBecomesCancelledNotFailed() {
    RunSandbox orphan = sandbox(SandboxState.CANCEL_REQUESTED);
    RunSandbox.Lease lease = lease(orphan);
    when(store.tryAcquire(eq(orphan.id()), anyString(), any())).thenReturn(Optional.of(lease));
    when(store.get(orphan.id())).thenReturn(Optional.of(orphan));
    when(store.beginRelease(lease, SandboxState.CANCELLED, null, null))
        .thenReturn(sandbox(SandboxState.RELEASING, SandboxState.CANCELLED, null));
    when(store.completeRelease(lease)).thenReturn(sandbox(SandboxState.CANCELLED));

    assertThat(dispatcher.reconcileOrphan(orphan, "jvm-b", Duration.ofMinutes(1)))
        .get()
        .extracting(RunSandbox::state)
        .isEqualTo(SandboxState.CANCELLED);
    assertThat(strict.destroyed).containsExactly(orphan.id());
  }

  @Test
  void expiryClaimsAndDestroysTheRuntimeBeforeRecordingExpired() {
    RunSandbox expired = sandbox(SandboxState.READY);
    RunSandbox.Lease lease = lease(expired);
    when(store.tryAcquire(eq(expired.id()), anyString(), any())).thenReturn(Optional.of(lease));
    when(store.get(expired.id())).thenReturn(Optional.of(expired));
    when(store.beginRelease(lease, SandboxState.EXPIRED, null, null))
        .thenReturn(sandbox(SandboxState.RELEASING, SandboxState.EXPIRED, null));
    when(store.completeRelease(lease)).thenReturn(sandbox(SandboxState.EXPIRED));

    assertThat(dispatcher.reconcileExpired(expired, "expiry-owner", Duration.ofMinutes(1)))
        .get()
        .extracting(RunSandbox::state)
        .isEqualTo(SandboxState.EXPIRED);
    assertThat(strict.destroyed).containsExactly(expired.id());
  }

  @Test
  void retryOfAReleasingSandboxPreservesItsOriginalTerminalIntent() {
    RunSandbox retry = sandbox(SandboxState.RELEASING, SandboxState.TERMINATED, null);
    RunSandbox.Lease lease = lease(retry);
    when(store.tryAcquire(eq(retry.id()), anyString(), any())).thenReturn(Optional.of(lease));
    when(store.get(retry.id())).thenReturn(Optional.of(retry));
    when(store.completeRelease(lease)).thenReturn(sandbox(SandboxState.TERMINATED));

    assertThat(dispatcher.reconcileOrphan(retry, "retry-owner", Duration.ofMinutes(1)))
        .get()
        .extracting(RunSandbox::state)
        .isEqualTo(SandboxState.TERMINATED);
    verify(store, never()).beginRelease(any(), any(), any(), any());
    verify(store).completeRelease(lease);
  }

  @Test
  void failedRetryRemainsReleasingForTheNextFencedOwner() {
    RunSandbox retry = sandbox(SandboxState.RELEASING, SandboxState.TERMINATED, null);
    RunSandbox.Lease lease = lease(retry);
    strict.destroyFailure = new IllegalStateException("daemon unavailable");
    when(store.tryAcquire(eq(retry.id()), anyString(), any())).thenReturn(Optional.of(lease));
    when(store.get(retry.id())).thenReturn(Optional.of(retry));
    when(store.recordCleanupFailure(lease, "daemon unavailable")).thenReturn(retry);

    assertThat(dispatcher.reconcileOrphan(retry, "retry-owner", Duration.ofMinutes(1)))
        .get()
        .extracting(RunSandbox::state)
        .isEqualTo(SandboxState.RELEASING);
    verify(store, never()).completeRelease(any());
  }

  private static final UUID ID = UUID.randomUUID();

  private static RunSandbox.Lease lease(RunSandbox sandbox) {
    return new RunSandbox.Lease(sandbox.id(), "jvm-a", 1L, Instant.now().plusSeconds(60));
  }

  private static RunSandbox sandbox(SandboxState state) {
    return sandbox(state, spec().configDigest());
  }

  private static RunSandbox sandbox(SandboxState state, String configDigest) {
    return sandbox(
        state,
        configDigest,
        state == SandboxState.RELEASING ? SandboxState.TERMINATED : null,
        null);
  }

  private static RunSandbox sandbox(
      SandboxState state, SandboxState cleanupTarget, String cleanupFailureClass) {
    return sandbox(state, spec().configDigest(), cleanupTarget, cleanupFailureClass);
  }

  private static RunSandbox sandbox(
      SandboxState state,
      String configDigest,
      SandboxState cleanupTarget,
      String cleanupFailureClass) {
    return new RunSandbox(
        ID,
        "run-1",
        "project-1",
        null,
        "user-1",
        "org-1",
        "abc1234",
        "[]",
        SandboxPurpose.RUN,
        state,
        SandboxIsolationProfile.CONTAINER_STRICT_V1,
        1,
        "jvm-a",
        1L,
        Instant.now().plusSeconds(60),
        1L,
        Instant.now(),
        "es-sbx-1",
        "sha256:image",
        configDigest,
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS,
        Instant.now().plusSeconds(3600),
        null,
        null,
        cleanupTarget,
        cleanupFailureClass,
        null,
        0,
        null,
        SandboxRetentionState.RETAINED,
        null,
        "idem-1",
        Instant.now(),
        null,
        null,
        null,
        null);
  }

  private static SandboxRuntimeSpec spec() {
    return new SandboxRuntimeSpec(
        "img",
        "/workspace",
        List.of(),
        new SandboxRuntimeSpec.SandboxRuntimeLimits(
            2.0, 1024L * 1024 * 1024, 128, Duration.ofMinutes(15), 64L * 1024 * 1024),
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS);
  }

  /** A strict-profile stand-in that records calls instead of touching a container engine. */
  private static final class RecordingRuntime implements RunSandboxRuntime {
    private boolean available = true;
    private final List<UUID> provisioned = new ArrayList<>();
    private final List<UUID> destroyed = new ArrayList<>();
    private RuntimeException destroyFailure;
    private RuntimeException provisionFailure;

    @Override
    public SandboxIsolationProfile profile() {
      return SandboxIsolationProfile.CONTAINER_STRICT_V1;
    }

    @Override
    public boolean available() {
      return available;
    }

    @Override
    public Provisioned provision(RunSandbox sandbox, SandboxRuntimeSpec spec) {
      if (provisionFailure != null) {
        throw provisionFailure;
      }
      provisioned.add(sandbox.id());
      return new Provisioned("es-sbx-1", "sha256:image");
    }

    @Override
    public SandboxExecResult exec(RunSandbox sandbox, SandboxExecRequest request) {
      return SandboxExecResult.completed(0, "", "", false, Duration.ZERO);
    }

    @Override
    public void destroy(RunSandbox sandbox) {
      destroyed.add(sandbox.id());
      if (destroyFailure != null) {
        throw destroyFailure;
      }
    }

    @Override
    public boolean alive(RunSandbox sandbox) {
      return false;
    }
  }
}

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
    RunSandbox releasing = sandbox(SandboxState.RELEASING);
    when(store.beginRelease(lease)).thenReturn(releasing);
    when(store.finishRelease(lease, SandboxState.CANCELLED))
        .thenReturn(sandbox(SandboxState.CANCELLED));

    assertThat(dispatcher.dispatchEnabled()).isFalse();
    assertThat(dispatcher.release(sandbox, lease, SandboxState.CANCELLED).state())
        .isEqualTo(SandboxState.CANCELLED);
    assertThat(strict.destroyed).containsExactly(releasing.id());
  }

  @Test
  void aTeardownFailureIsRecordedAsFailedBecauseTheContainerMayStillBeLive() {
    RunSandbox sandbox = sandbox(SandboxState.READY);
    RunSandbox.Lease lease = lease(sandbox);
    when(store.beginRelease(lease)).thenReturn(sandbox(SandboxState.RELEASING));
    strict.destroyFailure = new IllegalStateException("container still running");

    assertThatThrownBy(() -> dispatcher.release(sandbox, lease, SandboxState.TERMINATED))
        .isInstanceOf(IllegalStateException.class);
    verify(store).fail(eq(lease), eq("TEARDOWN_FAILED"), anyString());
    verify(store, never()).finishRelease(any(), any());
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

  @Test
  void losingTheLeaseMidProvisionTearsDownWithoutWritingStateItNoLongerOwns() {
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_DISPATCH)).thenReturn(true);
    RunSandbox sandbox = sandbox(SandboxState.PROVISIONING);
    RunSandbox.Lease lease = lease(sandbox);
    when(store.markReady(any(), anyString(), anyString()))
        .thenThrow(new SandboxLeaseFencedException(sandbox.id()));

    assertThatThrownBy(() -> dispatcher.provision(sandbox, lease, spec()))
        .isInstanceOf(SandboxLeaseFencedException.class);
    assertThat(strict.destroyed).containsExactly(sandbox.id());
    verify(store, never()).fail(any(), anyString(), anyString());
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
    when(store.beginRelease(lease)).thenReturn(sandbox(SandboxState.RELEASING));
    when(store.finishRelease(lease, SandboxState.CANCELLED))
        .thenReturn(sandbox(SandboxState.CANCELLED));

    assertThat(dispatcher.reconcileOrphan(orphan, "jvm-b", Duration.ofMinutes(1)))
        .get()
        .extracting(RunSandbox::state)
        .isEqualTo(SandboxState.CANCELLED);
    assertThat(strict.destroyed).containsExactly(orphan.id());
  }

  private static final UUID ID = UUID.randomUUID();

  private static RunSandbox.Lease lease(RunSandbox sandbox) {
    return new RunSandbox.Lease(sandbox.id(), "jvm-a", 1L, Instant.now().plusSeconds(60));
  }

  private static RunSandbox sandbox(SandboxState state) {
    return sandbox(state, spec().configDigest());
  }

  private static RunSandbox sandbox(SandboxState state, String configDigest) {
    return new RunSandbox(
        ID,
        "run-1",
        "project-1",
        null,
        "user-1",
        "org-1",
        "abc1234",
        "[]",
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
      provisioned.add(sandbox.id());
      return new Provisioned("es-sbx-1", "sha256:image");
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

package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The three flags must be independent, and cleanup must never depend on any of them. */
class RunSandboxLifecycleServiceTest {

  private RunSandboxStore store;
  private RunSandboxRuntimeDispatcher dispatcher;
  private FeatureFlagService flags;
  private RunSandboxLifecycleService lifecycle;

  @BeforeEach
  void setUp() {
    store = mock(RunSandboxStore.class);
    dispatcher = mock(RunSandboxRuntimeDispatcher.class);
    flags = mock(FeatureFlagService.class);
    lifecycle = new RunSandboxLifecycleService(store, dispatcher, flags, new SandboxProperties());
  }

  @Test
  void creationIsRefusedWhileTheLifecycleFlagIsOff() {
    assertThatThrownBy(() -> lifecycle.create(admission(), spec()))
        .isInstanceOf(RunSandboxLifecycleService.SandboxLifecycleDisabledException.class);
    verify(store, never()).createOrGet(any());
  }

  @Test
  void theAdmittedIsolationProfileIsRecordedAtCreationTime() {
    // A later flag flip must not be able to claim retroactively that this sandbox ran under a
    // stronger profile than the one actually selected.
    when(flags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)).thenReturn(true);
    when(dispatcher.selectedProfile()).thenReturn(SandboxIsolationProfile.NOT_ENABLED);

    lifecycle.create(admission(), spec());

    ArgumentCaptor<RunSandbox.CreateRequest> captured =
        ArgumentCaptor.forClass(RunSandbox.CreateRequest.class);
    verify(store).createOrGet(captured.capture());
    assertThat(captured.getValue().isolationProfile())
        .isEqualTo(SandboxIsolationProfile.NOT_ENABLED);
    assertThat(captured.getValue().configDigest()).isEqualTo(spec().configDigest());
    assertThat(captured.getValue().networkPolicy()).isEqualTo(SandboxNetworkPolicy.DENY_ALL);
  }

  @Test
  void cancellationCleanupAndExpiryConsultNoFlagAtAll() {
    UUID id = UUID.randomUUID();
    when(store.requestCancellation(id)).thenReturn(Optional.empty());
    when(store.orphanCandidates(anyInt())).thenReturn(List.of());
    when(store.expiryCandidates(anyInt())).thenReturn(List.of());

    lifecycle.cancel(id);
    lifecycle.reconcile("jvm-a");
    lifecycle.expire();

    // Every flag lookup in this service belongs to create(); nothing else may be gated.
    verify(flags, never()).isEnabled(RunSandboxRuntimeDispatcher.FLAG_DISPATCH);
    verify(flags, never()).isEnabled(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1);
    verify(flags, never()).isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE);
  }

  @Test
  void oneUnsettleableOrphanDoesNotAbortTheSweep() {
    RunSandbox bad = orphan();
    RunSandbox good = orphan();
    when(store.orphanCandidates(anyInt())).thenReturn(List.of(bad, good));
    when(dispatcher.reconcileOrphan(any(), any(), any(Duration.class)))
        .thenThrow(new IllegalStateException("engine down"))
        .thenReturn(Optional.of(good));

    assertThat(lifecycle.reconcile("jvm-a")).isEqualTo(1);
  }

  @Test
  void expiryDelegatesToRuntimeTeardownBeforeSettlingTheRow() {
    RunSandbox expired = orphan();
    when(store.expiryCandidates(anyInt())).thenReturn(List.of(expired));
    when(dispatcher.reconcileExpired(any(), any(), any(Duration.class)))
        .thenReturn(Optional.of(expired));

    assertThat(lifecycle.expire()).isEqualTo(1);

    verify(dispatcher).reconcileExpired(any(), any(), any(Duration.class));
    verify(store, never()).forceTerminal(any(), any(), any());
  }

  @Test
  void retentionIsSeparateFromLifecycleSoTeardownNeverDestroysEvidence() {
    UUID id = UUID.randomUUID();
    when(store.markRetention(id, SandboxRetentionState.RETAINED, "gs://evidence")).thenReturn(true);
    assertThat(lifecycle.retain(id, SandboxRetentionState.RETAINED, "gs://evidence")).isTrue();
  }

  private static RunSandbox orphan() {
    return new RunSandbox(
        UUID.randomUUID(),
        "run-1",
        "project-1",
        null,
        "user-1",
        "org-1",
        "abc1234",
        "[]",
        SandboxState.READY,
        SandboxIsolationProfile.NOT_ENABLED,
        1,
        "jvm-dead",
        1L,
        Instant.now().minusSeconds(60),
        1L,
        Instant.now().minusSeconds(120),
        "es-sbx-1",
        null,
        "sha256:cfg",
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS,
        Instant.now().plusSeconds(600),
        null,
        null,
        null,
        null,
        null,
        0,
        null,
        SandboxRetentionState.RETAINED,
        null,
        "idem-" + UUID.randomUUID(),
        Instant.now().minusSeconds(300),
        null,
        null,
        null,
        null);
  }

  private static RunSandboxLifecycleService.SandboxAdmission admission() {
    return new RunSandboxLifecycleService.SandboxAdmission(
        "run-1", "project-1", null, "user-1", "org-1", "abc1234", "[]", "idem-1");
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
}
